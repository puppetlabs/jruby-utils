(ns puppetlabs.services.jruby-pool-manager.jruby-ref-pool-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.protocols.jruby-pool :as pool-protocol])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas ShutdownPoisonPill)))

(defn jruby-test-config
  ([max-borrows]
   (jruby-test-config max-borrows 1))
  ([max-borrows max-instances]
   (jruby-testutils/jruby-config {:max-active-instances max-instances
                                  :multithreaded true
                                  :max-borrows-per-instance max-borrows})))

(deftest borrow-while-no-instances-available-test
  (testing "when all instances are in use, borrow blocks until an instance becomes available"
    (let [pool-size 2]
      (jruby-testutils/with-pool-context
        pool-context
        jruby-testutils/default-services
        (jruby-test-config 0 pool-size)
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
              (jruby-core/return-to-pool pool-context
                                          (first drained-instances)
                                          :borrow-with-no-free-instances-test
                                          [])

              ;; now the borrow can complete
              (is (some? @borrow-instance)))
            (finally
              (jruby-testutils/fill-drained-pool pool-context drained-instances))))))))


(defn add-watch-for-flush-complete
  [pool-context]
  (let [flush-complete (promise)]
    (add-watch (:borrow-count pool-context) :flush-callback
               (fn [k a old-count new-count]
                 (when (and (= k :flush-callback ) (< new-count old-count))
                   (remove-watch a :flush-callback)
                   (deliver flush-complete true))))
    flush-complete))

(deftest flush-jruby-after-max-borrows
  (testing "JRubyInstance is not flushed if it has not exceeded max borrows"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-test-config 2 1)
      (let [instance (jruby-core/borrow-from-pool pool-context :test [])
            id (:id instance)]
        (jruby-core/return-to-pool pool-context instance :test [])
        (let [instance (jruby-core/borrow-from-pool pool-context :test [])
              flush-complete (add-watch-for-flush-complete pool-context)]
          (is (not (realized? flush-complete)))
          (is (= id (:id instance)))
          ;; This return will trigger the flush
          (jruby-core/return-to-pool pool-context instance :test [])
          (is @flush-complete)))
      (testing "Can lock pool after a flush via max borrows"
        (let [timeout 1
              new-pool-context (assoc-in pool-context [:config :borrow-timeout] timeout)]
          (pool-protocol/lock new-pool-context)
          (is (nil? @(future (jruby-core/borrow-from-pool-with-timeout
                               new-pool-context
                               :test
                               []))))
          (pool-protocol/unlock new-pool-context)
          (let [instance (jruby-core/borrow-from-pool new-pool-context :test [])]
            (is (= 2 (:id instance)))
            (jruby-core/return-to-pool pool-context instance :test []))))))
  (testing "flushing due to max borrows succeeds when we've over-borrowed"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      ;; We can check out the instance more times than `max-borrows` and return
      ;; them each in sequence, and nothing blocks or fails
      (jruby-test-config 2 3)
      (let [instance1 (jruby-core/borrow-from-pool pool-context :test [])
            instance2 (jruby-core/borrow-from-pool pool-context :test [])
            instance3 (jruby-core/borrow-from-pool pool-context :test [])
            flush-complete (add-watch-for-flush-complete pool-context)]
        (jruby-core/return-to-pool pool-context instance1 :test [])
        (is (not (realized? flush-complete)))
        ;; This return triggers the flush
        (jruby-core/return-to-pool pool-context instance2 :test [])
        ;; But it doesn't finish till the other borrow is returned
        (is (not (realized? flush-complete)))
        (jruby-core/return-to-pool pool-context instance3 :test [])
        (is @flush-complete))
      (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
        (is (= 2 (:id instance)))
        (jruby-core/return-to-pool pool-context instance :test []))))
  (testing "JRubyInstance is not flushed if max borrows setting is set to 0"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-test-config 0)
      (let [instance (jruby-core/borrow-from-pool pool-context :test [])
            id (:id instance)]
        (jruby-core/return-to-pool pool-context instance :test [])
        (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
          (is (= id (:id instance)))
          (jruby-core/return-to-pool pool-context instance :test []))))))

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
        (jruby-core/return-to-pool pool-context pill :test [])))))
