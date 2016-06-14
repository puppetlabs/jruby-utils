(ns puppetlabs.services.jruby.jruby-agents-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-core :as jruby-core]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby.jruby-pool-manager-core :as jruby-pool-manager-core])
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol])
  (:import (puppetlabs.services.jruby.jruby_schemas RetryPoisonPill JRubyInstance)
           (com.puppetlabs.jruby_utils.pool JRubyPool)))

(use-fixtures :once schema-test/validate-schemas)

(deftest retry-poison-pill-test
  (testing "Flush puts a retry poison pill into the old pool"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-testutils/jruby-config {:max-active-instances 1})
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
       (let [old-pool (jruby-core/get-pool pool-context)
             pool-state-swapped (promise)
             pool-state-watch-fn (fn [key pool-state old-val new-val]
                                   (when (not= (:pool old-val) (:pool new-val))
                                     (remove-watch pool-state key)
                                     (deliver pool-state-swapped true)))]
         ; borrow an instance so we know that the pool is ready
         (jruby-core/with-jruby-instance jruby-instance pool-context :retry-poison-pill-test)
         (add-watch (jruby-internal/get-pool-state-container pool-context)
                    :pool-state-watch pool-state-watch-fn)
         (jruby-core/flush-pool! pool-context)
         ; wait until we know the new pool has been swapped in
         @pool-state-swapped
         ; wait until the flush is complete
         (jruby-testutils/timed-await (jruby-agents/get-pool-agent pool-context))
         (let [old-pool-instance (jruby-internal/borrow-from-pool!*
                                  jruby-internal/borrow-without-timeout-fn
                                  old-pool)]
           (is (jruby-schemas/retry-poison-pill? old-pool-instance))))))))

(deftest with-jruby-retry-test-via-mock-get-pool
  (testing "with-jruby-instance retries if it encounters a RetryPoisonPill"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-testutils/jruby-config {:max-active-instances 1})
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
       (let [real-pool (jruby-core/get-pool pool-context)
             retry-pool (JRubyPool. 1)
             _ (->> retry-pool
                    (RetryPoisonPill.)
                    (.insertPill retry-pool))
             mock-pools [retry-pool retry-pool retry-pool real-pool]
             num-borrows (atom 0)
             get-mock-pool (fn [_] (let [result (nth mock-pools @num-borrows)]
                                     (swap! num-borrows inc)
                                     result))]
         (with-redefs [jruby-internal/get-pool get-mock-pool]
           (jruby-core/with-jruby-instance
            jruby-instance
            pool-context
            :with-jruby-retry-test
            (is (instance? JRubyInstance jruby-instance))))
         (is (= 4 @num-borrows)))))))

(deftest next-instance-id-test
  (let [pool-context (jruby-pool-manager-core/create-pool-context
                      (jruby-testutils/jruby-config {:max-active-instances 8}))]
    (testing "next instance id should be based on the pool size"
      (is (= 10 (jruby-agents/next-instance-id 2 pool-context)))
      (is (= 100 (jruby-agents/next-instance-id 92 pool-context))))
    (testing "next instance id should wrap after max int"
      (let [id (- Integer/MAX_VALUE 1)]
        (is (= (mod id 8) (jruby-agents/next-instance-id id pool-context)))))))

(deftest custom-termination-test
  (testing "Flushing the pool causes cleanup hook to be called"
    (let [cleanup-atom (atom nil)
          config (assoc-in (jruby-testutils/jruby-config {:max-active-instances 1})
                           [:lifecycle :cleanup]
                           (fn [x] (reset! cleanup-atom "Hello from cleanup")))]
      (tk-bootstrap/with-app-with-config
       app
       jruby-testutils/default-services
       {}
       (let [pool-manager-service (tk-app/get-service app :PoolManagerService)
             pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
         (jruby-core/flush-pool! pool-context)
         ; wait until the flush is complete
         (jruby-testutils/timed-await (jruby-agents/get-pool-agent pool-context))
         (is (= "Hello from cleanup" (deref cleanup-atom))))))))
