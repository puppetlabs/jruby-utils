(ns puppetlabs.services.jruby-pool-manager.jruby-reference-pool-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core :as jruby-pool-manager-core]
            [puppetlabs.kitchensink.core :as ks])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas JRubyInstance)))

(deftest collect-all-jrubies-test
  (testing "returns list of all the jruby instances"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-testutils/jruby-config-for-ref-pool {:max-active-instances 4})
      (let [pool (jruby-core/get-pool pool-context)
            jruby-list (jruby-agents/borrow-all-jrubies pool-context)]
        (try
          (is (= 4 (count jruby-list)))
          (is (every? #(instance? JRubyInstance %) jruby-list))
          (is (= (first jruby-list) (last jruby-list)))
          (is (= 0 (.size pool)))
          (finally
            (jruby-testutils/fill-drained-pool jruby-list)))))))

(deftest test-jruby-core-funcs
  (let [pool-size        2
        timeout          250
        config           (jruby-testutils/jruby-config-for-ref-pool {:max-active-instances pool-size
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

        (testing "Borrowing the instance increments its request count."
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
                    ;; The instance has been borrowed twice, since we allow two borrows (size of pool is 2)
                    ;; and we borrow up to our limit in `drain-via`
                    (is (= (+ 2 (counts k)) (new-counts k)))))))))
        (finally
          (jruby-core/flush-pool-for-shutdown! pool-context))))))

(def test-borrow-timeout 180000)

(defn set-constant-and-verify
  [instance]
  ;; here we set a variable called 'seen' in the instance
  (let [sc (:scripting-container instance)
        result (.runScriptlet sc "$seen = true")]
    ;; and validate that we can read that value back from each instance
    (= true (.runScriptlet sc "$seen"))))

(defn check-all-jrubies-for-constants
  [pool-context num-instances]
  (jruby-testutils/reduce-over-jrubies!
    pool-context
    num-instances
    (constantly "! $seen.nil?")))

(defn verify-no-constants
  [pool-context]
  ;; verify that the constants are cleared out from the instances by looping
  ;; over them and expecting a 'NameError' when we reference the constant by name.
  (every? false? (check-all-jrubies-for-constants pool-context 1)))

(deftest ^:integration max-borrows-flush-while-pool-flush-in-progress-test
  (testing "hitting max-borrows while flush in progress doesn't interfere with flush"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-testutils/jruby-config-for-ref-pool {:max-active-instances 4
                                                  :max-borrows-per-instance 10
                                                  :splay-instance-flush false
                                                  :borrow-timeout
                                                  test-borrow-timeout})
      (let [pool (jruby-core/get-pool pool-context)]
        ;; borrow the instance once and hold reference to it, to prevent
        ;; the flush operation from completing
        (let [reference1 (jruby-core/borrow-from-pool-with-timeout
                            pool-context
                            :max-borrows-flush-while-pool-flush-in-progress-test
                            [])]
          ;; Mark the instance so we can verify it gets flushed out later
          (is (true? (set-constant-and-verify reference1)))
          ;; we are going to borrow and return a second reference until we get its
          ;; request count up to max-borrows - 1, so that we can use it to test
          ;; flushing behavior the next time we return it.
          (is (true? (jruby-testutils/borrow-until-desired-borrow-count pool-context 9)))
          ;; now we grub a reference to that instance and hold onto it for later.
          (let [reference2 (jruby-core/borrow-from-pool-with-timeout
                              pool-context
                              :max-borrows-flush-while-pool-flush-in-progress-test
                              [])]
            (is (= 9 (:borrow-count (jruby-core/get-instance-state reference2))))
            (is (= 2 (jruby-core/free-instance-count pool)))

            ; Just to show that the pool is not locked yet
            (is (not (.isLocked pool)))
            ;; trigger a flush asynchronously
            (let [flush-future (future (jruby-core/flush-pool! pool-context))]
              ;; Once the lock is held this means that the flush is waiting
              ;; for all the instances to be returned before continuing
              (is (jruby-testutils/wait-for-pool-to-be-locked pool))

              ;; now we're going to return instance2 to the pool.  This would trigger a flush
              ;; (before the one we requested) but we're still holding on to another reference
              ;; to this instance, so it can't proceed.
              (jruby-core/return-to-pool reference2
                                         :max-borrows-flush-while-pool-flush-in-progress-test
                                         [])
              ;; Wait until instance2 is returned
              (is (jruby-testutils/wait-for-instances pool 3) "Timed out waiting for instance2 to return to pool")

              ;; and finally, we return the last instance we borrowed to the pool
              (jruby-core/return-to-pool reference1
                                         :max-borrows-flush-while-pool-flush-in-progress-test
                                         [])

              ;; wait until the flush is complete
              (is (deref flush-future 10000 false))
              (is (not (.isLocked pool)))

              (is (jruby-testutils/wait-for-instances pool 4) "Timed out waiting for the flush to finish"))))

        ;; we should have a fresh instance without the constant.
        (is (true? (verify-no-constants pool-context)))

        ;; The jruby return instance calls done within the previous
        ;; check jrubies call may cause an instance to be in the process of
        ;; being flushed when the server is shut down.  This ensures that
        ;; the flushing is all done before the server is shut down - since
        ;; that could otherwise cause an annoying error message about the
        ;; pool not being full at shut down to be displayed.
        (jruby-testutils/timed-await (jruby-agents/get-modify-instance-agent pool-context))))))
