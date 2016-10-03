(ns puppetlabs.services.jruby-pool-manager.jruby-pool-test
  (:import (clojure.lang ExceptionInfo))
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core :as jruby-pool-manager-core]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests

(deftest configuration-validation
  (testing "malformed configuration fails"
    (let [malformed-config {:illegal-key [1 2 3]}]
      (is (thrown-with-msg? ExceptionInfo
                            #"Input to create-pool-context does not match schema"
                            (jruby-pool-manager-core/create-pool-context malformed-config)))))
  (let [minimal-config {:gem-home "/dev/null"
                        :ruby-load-path ["/dev/null"]}
        config        (jruby-core/initialize-config minimal-config)]
    (testing "max-active-instances is set to default if not specified"
      (is (= (jruby-core/default-pool-size (ks/num-cpus)) (:max-active-instances config))))
    (testing "max-borrows-per-instance is set to 0 if not specified"
      (is (= 0 (:max-borrows-per-instance config))))
    (testing "max-borrows-per-instance is honored if specified"
      (is (= 5 (-> minimal-config
                   (assoc :max-borrows-per-instance 5)
                   (jruby-core/initialize-config)
                   :max-borrows-per-instance))))
    (testing "compile-mode is set to default if not specified"
      (is (= jruby-core/default-jruby-compile-mode
             (:compile-mode config))))
    (testing "compile-mode is honored if specified"
      (is (= :off (-> minimal-config
                      (assoc :compile-mode "off")
                      (jruby-core/initialize-config)
                      :compile-mode)))
      (is (= :jit (-> minimal-config
                      (assoc :compile-mode "jit")
                      (jruby-core/initialize-config)
                      :compile-mode))))
    (testing "gem-path is set to nil if not specified"
      (is (nil? (-> minimal-config
                    jruby-core/initialize-config
                    :gem-path))))
    (testing "gem-path is respected if specified"
      (is (= "/tmp/foo:/dev/null"
             (-> minimal-config
                 (assoc :gem-path "/tmp/foo:/dev/null")
                 jruby-core/initialize-config
                 :gem-path))))))

(deftest test-jruby-core-funcs
  (let [pool-size        2
        timeout          250
        config           (jruby-testutils/jruby-config {:max-active-instances pool-size
                                                        :borrow-timeout timeout})
        pool-context (jruby-pool-manager-core/create-pool-context config)
        pool             (jruby-core/get-pool pool-context)]

    (testing "The pool should not yet be full as it is being primed in the
             background."
      (is (= (jruby-core/free-instance-count pool) 0)))

    (jruby-agents/prime-pool! pool-context)

    (testing "Borrowing all instances from a pool while it is being primed and
             returning them."
      (let [all-the-jrubys (jruby-testutils/drain-pool pool-context pool-size)]
        (is (= 0 (jruby-core/free-instance-count pool)))
        (doseq [instance all-the-jrubys]
          (is (not (nil? instance)) "One of JRubyInstances is nil"))
        (jruby-testutils/fill-drained-pool all-the-jrubys)
        (is (= pool-size (jruby-core/free-instance-count pool)))))

    (testing "Borrowing from an empty pool with a timeout returns nil within the
             proper amount of time."
      (let [all-the-jrubys       (jruby-testutils/drain-pool pool-context pool-size)
            test-start-in-millis (System/currentTimeMillis)]
        (is (nil? (jruby-core/borrow-from-pool-with-timeout pool-context :test [])))
        (is (>= (- (System/currentTimeMillis) test-start-in-millis) timeout)
            "The timeout value was not honored.")
        (jruby-testutils/fill-drained-pool all-the-jrubys)
        (is (= (jruby-core/free-instance-count pool) pool-size)
            "All JRubyInstances were not returned to the pool.")))

    (testing "Removing an instance decrements the pool size by 1."
      (let [jruby-instance (jruby-core/borrow-from-pool pool-context :test [])]
        (is (= (jruby-core/free-instance-count pool) (dec pool-size)))
        (jruby-core/return-to-pool jruby-instance :test [])))

    (testing "Borrowing an instance increments its request count."
      (let [drain-via   (fn [borrow-fn] (doall (repeatedly pool-size borrow-fn)))
            assoc-count (fn [acc jruby]
                          (assoc acc (:id jruby)
                                     (:borrow-count (jruby-core/get-instance-state jruby))))
            get-counts  (fn [jrubies] (reduce assoc-count {} jrubies))]
        (doseq [drain-fn [#(jruby-core/borrow-from-pool pool-context :test [])
                          #(jruby-core/borrow-from-pool-with-timeout pool-context :test [])]]
          (let [jrubies (drain-via drain-fn)
                counts  (get-counts jrubies)]
            (jruby-testutils/fill-drained-pool jrubies)
            (let [jrubies    (drain-via drain-fn)
                  new-counts (get-counts jrubies)]
              (jruby-testutils/fill-drained-pool jrubies)
              (is (= (ks/keyset counts) (ks/keyset new-counts)))
              (doseq [k (keys counts)]
                (is (= (inc (counts k)) (new-counts k)))))))))))

(deftest borrow-while-pool-is-being-initialized-test
  (testing "borrow will block until an instance is available while the pool is coming online"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
    (let [pool-initialized? (promise)
          init-fn (fn [instance] @pool-initialized? instance)
          pool-size 1
          config (jruby-testutils/jruby-config
                  {:max-active-instances pool-size
                   :lifecycle {:initialize-pool-instance init-fn}})
          pool-manager-service (tk-app/get-service app :PoolManagerService)
          ;; start a pool initialization, which will block on the `initialize-pool-instance`
          ;; function's deref of the promise
          pool-context (pool-manager-protocol/create-pool pool-manager-service config)]

      ;; start a borrow, which should block until an instance becomes available
      (let [borrow-instance (future (jruby-core/borrow-from-pool-with-timeout
                                     pool-context
                                     :borrow-during-pool-init-test
                                     []))]

        (is (not (realized? borrow-instance)))

        ;; deliver the promise, allowing the pool initialization to complete
        (deliver pool-initialized? true)

        ;; now the borrow can complete
        (is (jruby-schemas/jruby-instance? @borrow-instance)))))))

(deftest borrow-while-no-instances-available-test
  (testing "when all instances are in use, borrow blocks until an instance becomes available"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [pool-size 2
           config (jruby-testutils/jruby-config {:max-active-instances pool-size})
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)]

       ;; borrow both instances from the pool
       (let [drained-instances (jruby-testutils/drain-pool pool-context pool-size)]
         (is (= 2 (count drained-instances)))

         ;; attempt a borrow, which will block because no instances are free
         (let [borrow-instance (future (jruby-core/borrow-from-pool-with-timeout
                                        pool-context
                                        :borrow-with-no-free-instances-test
                                        []))]
           (is (not (realized? borrow-instance)))

           ;; return an instance to the pool
           (jruby-core/return-to-pool
            (first drained-instances)
            :borrow-with-no-free-instances-test
            [])

           ;; now the borrow can complete
           (is (some? @borrow-instance))))))))

(deftest prime-pools-failure
  (let [pool-size 2
        config        (jruby-testutils/jruby-config {:max-active-instances pool-size})
        pool-context (jruby-pool-manager-core/create-pool-context config)
        err-msg       (re-pattern "Unable to borrow JRubyInstance from pool")]
   (is (thrown? IllegalStateException (jruby-agents/prime-pool!
                                       (assoc-in pool-context [:config :lifecycle :initialize-pool-instance]
                                                 (fn [_] (throw (IllegalStateException. "BORK!")))))))
    (testing "borrow and borrow-with-timeout both throw an exception if the pool failed to initialize"
      (is (thrown-with-msg? IllegalStateException
            err-msg
            (jruby-core/borrow-from-pool pool-context :test [])))
      (is (thrown-with-msg? IllegalStateException
            err-msg
            (jruby-core/borrow-from-pool-with-timeout pool-context :test []))))
    (testing "borrow and borrow-with-timeout both continue to throw exceptions on subsequent calls"
      (is (thrown-with-msg? IllegalStateException
          err-msg
          (jruby-core/borrow-from-pool pool-context :test [])))
      (is (thrown-with-msg? IllegalStateException
          err-msg
          (jruby-core/borrow-from-pool-with-timeout pool-context :test []))))))

(deftest test-default-pool-size
  (logutils/with-test-logging
    (let [config (jruby-testutils/jruby-config)
          pool (jruby-pool-manager-core/create-pool-context config)
          pool-state (jruby-core/get-pool-state pool)]
      (is (= (jruby-core/default-pool-size (ks/num-cpus)) (:size pool-state))))))

(defn jruby-test-config
  ([max-borrows]
   (jruby-test-config max-borrows 1))
  ([max-borrows max-instances]
   (jruby-testutils/jruby-config {:max-active-instances max-instances
                                  :max-borrows-per-instance max-borrows})))

(deftest flush-jruby-after-max-borrows
  (testing "JRubyInstance is not flushed if it has not exceeded max borrows"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-test-config 2)
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)
           instance (jruby-core/borrow-from-pool pool-context :test [])
           id (:id instance)]
       (jruby-core/return-to-pool instance :test [])
       (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
         (is (= id (:id instance)))))))
  (testing "JRubyInstance is flushed after exceeding max borrows"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-test-config 2)
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
       (jruby-testutils/wait-for-jrubies-from-pool-context pool-context)
       (is (= 1 (count (jruby-core/registered-instances pool-context))))
       (let [instance (jruby-core/borrow-from-pool pool-context :test [])
             id (:id instance)]
         (jruby-core/return-to-pool instance :test [])
         (jruby-core/borrow-from-pool pool-context :test [])
         (jruby-core/return-to-pool instance :test [])
         (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
           (is (not= id (:id instance)))
           (jruby-core/return-to-pool instance :test []))
         (testing "instance is removed from registered elements after flushing"
           (is (= 1 (count (jruby-core/registered-instances pool-context))))))
       (testing "Can lock pool after a flush via max borrows"
         (let [timeout 1
               new-pool-context (assoc-in pool-context [:config :borrow-timeout] timeout)
               pool (jruby-internal/get-pool new-pool-context)]
           (.lock pool)
           (is (nil? @(future (jruby-core/borrow-from-pool-with-timeout
                               new-pool-context
                               :test
                               []))))
           (.unlock pool)
           (is (not (nil? @(future (jruby-core/borrow-from-pool-with-timeout
                                    new-pool-context
                                    :test
                                    []))))))))))
  (testing "JRubyInstance is not flushed if max borrows setting is set to 0"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-test-config 0)
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)
           instance (jruby-core/borrow-from-pool pool-context :test [])
           id (:id instance)]
       (jruby-core/return-to-pool instance :test [])
       (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
         (is (= id (:id instance)))))))
  (testing "Can flush a JRubyInstance that is not the first one in the pool"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-test-config 2 3)
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)
           instance1 (jruby-core/borrow-from-pool pool-context :test [])
           instance2 (jruby-core/borrow-from-pool pool-context :test [])
           id (:id instance2)]
       (jruby-core/return-to-pool instance2 :test [])
       ;; borrow it a second time and confirm we get the same instance
       (let [instance2 (jruby-core/borrow-from-pool pool-context :test [])]
         (is (= id (:id instance2)))
         (jruby-core/return-to-pool instance2 :test []))
       ;; borrow it a third time and confirm that we get a different instance
       (let [instance2 (jruby-core/borrow-from-pool pool-context :test [])]
         (is (not= id (:id instance2)))
         (jruby-core/return-to-pool instance2 :test []))
       (jruby-core/return-to-pool instance1 :test [])))))
