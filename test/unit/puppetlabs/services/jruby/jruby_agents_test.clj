(ns puppetlabs.services.jruby.jruby-agents-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.services.jruby.jruby-service :as jruby]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-core :as jruby-core]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby :as jruby-protocol]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby.jruby-agents :as jruby-agents]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.services.jruby.jruby-core :as core]
            [clojure.tools.logging :as log])
  (:import (puppetlabs.services.jruby.jruby_schemas RetryPoisonPill JRubyInstance)
           (com.puppetlabs.jruby_utils.pool JRubyPool)))

(use-fixtures :once schema-test/validate-schemas)
(use-fixtures :each jruby-testutils/mock-pool-instance-fixture)

(def default-services
  [jruby/jruby-pooled-service])

(deftest basic-flush-test
  (testing "Flushing the pool results in all new JRubyInstances"
    (tk-testutils/with-app-with-config
      app
      default-services
      (-> (jruby-testutils/jruby-tk-config
           (jruby-testutils/jruby-config {:max-active-instances 4})))
      (let [jruby-service (tk-app/get-service app :JRubyService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)]
        (jruby-testutils/reduce-over-jrubies! pool-context 4 #(format "InstanceID = %s" %))
        (is (= #{0 1 2 3}
               (-> (jruby-testutils/reduce-over-jrubies! pool-context 4 (constantly "InstanceID"))
                   set)))
        (jruby-protocol/flush-jruby-pool! jruby-service)
        ; wait until the flush is complete
        (await (:pool-agent pool-context))
        (is (every? true?
                    (jruby-testutils/reduce-over-jrubies!
                      pool-context
                      4
                      (constantly
                        "begin; InstanceID; false; rescue NameError; true; end"))))))))

(deftest retry-poison-pill-test
  (testing "Flush puts a retry poison pill into the old pool"
    (tk-testutils/with-app-with-config
      app
      default-services
      (-> (jruby-testutils/jruby-tk-config
           (jruby-testutils/jruby-config {:max-active-instances 1})))
      (let [jruby-service (tk-app/get-service app :JRubyService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)
            old-pool (jruby-core/get-pool pool-context)
            pool-state-swapped (promise)
            pool-state-watch-fn (fn [key pool-state old-val new-val]
                                  (when (not= (:pool old-val) (:pool new-val))
                                    (remove-watch pool-state key)
                                    (deliver pool-state-swapped true)))]
        ; borrow an instance so we know that the pool is ready
        (jruby/with-jruby-instance jruby-instance jruby-service :retry-poison-pill-test)
        (add-watch (:pool-state pool-context) :pool-state-watch pool-state-watch-fn)
        (jruby-protocol/flush-jruby-pool! jruby-service)
        ; wait until we know the new pool has been swapped in
        @pool-state-swapped
        ; wait until the flush is complete
        (await (:pool-agent pool-context))
        (let [old-pool-instance (jruby-internal/borrow-from-pool!*
                                  jruby-internal/borrow-without-timeout-fn
                                  old-pool)]
          (is (jruby-schemas/retry-poison-pill? old-pool-instance)))))))

(deftest with-jruby-retry-test-via-mock-get-pool
  (testing "with-jruby-instance retries if it encounters a RetryPoisonPill"
    (tk-testutils/with-app-with-config
      app
      default-services
      (-> (jruby-testutils/jruby-tk-config
           (jruby-testutils/jruby-config {:max-active-instances 1})))
      (let [jruby-service (tk-app/get-service app :JRubyService)
            real-pool     (-> (tk-services/service-context jruby-service)
                              :pool-context
                              (jruby-core/get-pool))
            retry-pool    (JRubyPool. 1)
            _             (->> retry-pool
                              (RetryPoisonPill.)
                              (.insertPill retry-pool))
            mock-pools    [retry-pool retry-pool retry-pool real-pool]
            num-borrows   (atom 0)
            get-mock-pool (fn [_] (let [result (nth mock-pools @num-borrows)]
                                    (swap! num-borrows inc)
                                    result))]
        (with-redefs [jruby-internal/get-pool get-mock-pool]
          (jruby/with-jruby-instance
           jruby-instance
           jruby-service
           :with-jruby-retry-test
           (is (instance? JRubyInstance jruby-instance))))
        (is (= 4 @num-borrows))))))

(deftest next-instance-id-test
  (let [pool-context (jruby-core/create-pool-context
                      (jruby-testutils/jruby-config {:max-active-instances 8}))]
    (testing "next instance id should be based on the pool size"
      (is (= 10 (jruby-agents/next-instance-id 2 pool-context)))
      (is (= 100 (jruby-agents/next-instance-id 92 pool-context))))
    (testing "next instance id should wrap after max int"
      (let [id (- Integer/MAX_VALUE 1)]
        (is (= (mod id 8) (jruby-agents/next-instance-id id pool-context)))))))

(deftest custom-termination-test
  (testing "Flushing the pool causes shutdown hook to be called"
    (logutils/with-test-logging
     (let [config (assoc-in (jruby-testutils/jruby-tk-config
                             (jruby-testutils/jruby-config {:max-active-instances 1}))
                            [:jruby :lifecycle]
                            {:shutdown (fn [x] (log/error "Hello from shutdown") x)})]
        (tk-testutils/with-app-with-config
         app
         [jruby/jruby-pooled-service]
         config
         (let [jruby-service (tk-app/get-service app :JRubyService)
               context (tk-services/service-context jruby-service)]
           (jruby-protocol/flush-jruby-pool! jruby-service)
           ; wait until the flush is complete
           (await (get-in context [:pool-context :pool-agent]))
           (is (logged? #"Hello from shutdown"))))))))
