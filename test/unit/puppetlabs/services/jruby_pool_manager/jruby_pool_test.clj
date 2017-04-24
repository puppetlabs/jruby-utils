(ns puppetlabs.services.jruby-pool-manager.jruby-pool-test
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core :as jruby-pool-manager-core]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas])
  (:import (clojure.lang ExceptionInfo)
           (puppetlabs.services.jruby_pool_manager.jruby_schemas ShutdownPoisonPill)))


(defn- initialize-jruby-config-with-logging-suppressed
  [config]
  (logutils/with-test-logging
   (jruby-core/initialize-config config)))

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
        config        (initialize-jruby-config-with-logging-suppressed
                       minimal-config)]
    (testing "max-active-instances is set to default if not specified"
      (is (= (jruby-core/default-pool-size (ks/num-cpus)) (:max-active-instances config))))
    (testing "max-borrows-per-instance is set to 0 if not specified"
      (is (= 0 (:max-borrows-per-instance config))))
    (testing "max-borrows-per-instance is honored if specified"
      (is (= 5 (-> minimal-config
                   (assoc :max-borrows-per-instance 5)
                   (initialize-jruby-config-with-logging-suppressed)
                   :max-borrows-per-instance))))
    (testing "compile-mode is set to default if not specified"
      (is (= jruby-core/default-jruby-compile-mode
             (:compile-mode config))))
    (testing "compile-mode is honored if specified"
      (is (= :off (-> minimal-config
                      (assoc :compile-mode "off")
                      (initialize-jruby-config-with-logging-suppressed)
                      :compile-mode)))
      (is (= :jit (-> minimal-config
                      (assoc :compile-mode "jit")
                      (initialize-jruby-config-with-logging-suppressed)
                      :compile-mode))))
    (testing "compat-version is set to default if not specified"
      (is (= jruby-core/default-jruby-compat-version
             (:compat-version config))))
    (testing "compat-version is honored if specified as a string"
      (is (= "1.9" (-> minimal-config
                       (assoc :compat-version "1.9")
                       (initialize-jruby-config-with-logging-suppressed)
                       :compat-version)))
      (is (= "2.0" (-> minimal-config
                       (assoc :compat-version "2.0")
                       (initialize-jruby-config-with-logging-suppressed)
                       :compat-version))))
    (testing "compat-version is honored if specified as a double"
      ;; depending on how the setting is laid down in a HOCON file, it seems feasible that it might
      ;; be a string or a double. We should tolerate either.
      (is (= "1.9" (-> minimal-config
                       (assoc :compat-version 1.9)
                       (initialize-jruby-config-with-logging-suppressed)
                       :compat-version)))
      (is (= "2.0" (-> minimal-config
                       (assoc :compat-version 2.0)
                       (initialize-jruby-config-with-logging-suppressed)
                       :compat-version))))
    (testing "compat-version is honored if specified as an integer"
      ;; HOCON might parse doubles as integers in some cases? so we should tolerate it as an integer
      ;; too
      (is (= "2.0" (-> minimal-config
                       (assoc :compat-version 2)
                       (initialize-jruby-config-with-logging-suppressed)
                       :compat-version))))
    (testing "gem-path is set to nil if not specified"
      (is (nil? (-> minimal-config
                    initialize-jruby-config-with-logging-suppressed
                    :gem-path))))
    (testing "gem-path is respected if specified"
      (is (= "/tmp/foo:/dev/null"
             (-> minimal-config
                 (assoc :gem-path "/tmp/foo:/dev/null")
                 initialize-jruby-config-with-logging-suppressed
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
      (is (= (jruby-core/free-instance-count pool) 0))

      (jruby-agents/prime-pool! pool-context)
      (try
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
          (let [all-the-jrubys (jruby-testutils/drain-pool pool-context pool-size)
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
          (let [drain-via (fn [borrow-fn] (doall (repeatedly pool-size borrow-fn)))
                assoc-count (fn [acc jruby]
                              (assoc acc (:id jruby)
                                         (:borrow-count (jruby-core/get-instance-state jruby))))
                get-counts (fn [jrubies] (reduce assoc-count {} jrubies))]
            (doseq [drain-fn [#(jruby-core/borrow-from-pool pool-context :test [])
                              #(jruby-core/borrow-from-pool-with-timeout pool-context :test [])]]
              (let [jrubies (drain-via drain-fn)
                    counts (get-counts jrubies)]
                (jruby-testutils/fill-drained-pool jrubies)
                (let [jrubies (drain-via drain-fn)
                      new-counts (get-counts jrubies)]
                  (jruby-testutils/fill-drained-pool jrubies)
                  (is (= (ks/keyset counts) (ks/keyset new-counts)))
                  (doseq [k (keys counts)]
                    (is (= (inc (counts k)) (new-counts k)))))))))
        (finally
          (jruby-core/flush-pool-for-shutdown! pool-context))))))

(deftest borrow-while-pool-is-being-initialized-test
  (testing "borrow will block until an instance is available while the pool is coming online"
    (let [pool-initialized? (promise)
          init-fn (fn [instance] @pool-initialized? instance)
          pool-size 1]
     ;; start a pool initialization, which will block on the `initialize-pool-instance`
     ;; function's deref of the promise
     (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-testutils/jruby-config
       {:max-active-instances pool-size
        :lifecycle {:initialize-pool-instance init-fn}})

      ;; start a borrow, which should block until an instance becomes available
      (let [borrow-instance (future (jruby-core/borrow-from-pool-with-timeout
                                     pool-context
                                     :borrow-during-pool-init-test
                                     []))]
        (try
          (is (not (realized? borrow-instance)))

          ;; deliver the promise, allowing the pool initialization to complete
          (deliver pool-initialized? true)

          ;; now the borrow can complete
          (is (jruby-schemas/jruby-instance? @borrow-instance))
          (finally
            (jruby-core/return-to-pool
             @borrow-instance
             :borrow-during-pool-init-test
             []))))))))

(deftest borrow-while-no-instances-available-test
  (testing "when all instances are in use, borrow blocks until an instance becomes available"
    (let [pool-size 2]
      (jruby-testutils/with-pool-context
       pool-context
       jruby-testutils/default-services
       (jruby-testutils/jruby-config {:max-active-instances pool-size})
       ;; borrow both instances from the pool
       (let [drained-instances (jruby-testutils/drain-pool pool-context pool-size)]
         (try
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
             (is (some? @borrow-instance)))
           (finally
             (jruby-testutils/fill-drained-pool drained-instances))))))))

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
  (let [config (jruby-testutils/jruby-config)
        pool (jruby-pool-manager-core/create-pool-context config)
        pool-state (jruby-core/get-pool-state pool)]
    (is (= (jruby-core/default-pool-size (ks/num-cpus)) (:size pool-state)))))

(defn jruby-test-config
  ([max-borrows]
   (jruby-test-config max-borrows 1))
  ([max-borrows max-instances]
   (jruby-testutils/jruby-config {:max-active-instances max-instances
                                  :max-borrows-per-instance max-borrows})))

(deftest flush-jruby-after-max-borrows
  (testing "JRubyInstance is not flushed if it has not exceeded max borrows"
    (jruby-testutils/with-pool-context
     pool-context
     jruby-testutils/default-services
     (jruby-test-config 2)
     (let [instance (jruby-core/borrow-from-pool pool-context :test [])
           id (:id instance)]
       (jruby-core/return-to-pool instance :test [])
       (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
         (is (= id (:id instance)))
         (jruby-core/return-to-pool instance :test [])))))
  (testing "JRubyInstance is flushed after exceeding max borrows"
    (jruby-testutils/with-pool-context
     pool-context
     jruby-testutils/default-services
     (jruby-test-config 2)
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
         (let [instance @(future (jruby-core/borrow-from-pool-with-timeout
                                  new-pool-context
                                  :test
                                  []))]
           (is (not (nil? instance)))
           (jruby-core/return-to-pool instance :test []))))))
  (testing "JRubyInstance is not flushed if max borrows setting is set to 0"
    (jruby-testutils/with-pool-context
     pool-context
     jruby-testutils/default-services
     (jruby-test-config 0)
     (let [instance (jruby-core/borrow-from-pool pool-context :test [])
           id (:id instance)]
       (jruby-core/return-to-pool instance :test [])
       (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
         (is (= id (:id instance)))
         (jruby-core/return-to-pool instance :test [])))))
  (testing "Can flush a JRubyInstance that is not the first one in the pool"
    (jruby-testutils/with-pool-context
     pool-context
     jruby-testutils/default-services
     (jruby-test-config 2 3)
     (let [instance1 (jruby-core/borrow-from-pool pool-context :test [])
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

(deftest return-pill-to-pool-test
  (testing "Returning a pill to the pool does not throw"
    ; Essentially this test is insurance to make sure we aren't doing anything
    ; funky when we return a pill to the pool. Instances have some internal
    ; data that gets manipulated during a return that poison pills don't have,
    ; and we'd get null pointer exceptions if this code path tried to access
    ; those non-existent properties on the pill object
    (jruby-testutils/with-pool-context
     pool-context
     jruby-testutils/default-services
     (jruby-test-config 2)
     (let [pool (jruby-core/get-pool pool-context)
           pill (ShutdownPoisonPill. pool)]
       ; Returning a pill should be a noop
       (jruby-core/return-to-pool pill :test [])))))
