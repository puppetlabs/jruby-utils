(ns puppetlabs.services.jruby-pool-manager.jruby-ref-pool-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.protocols.jruby-pool :as pool-protocol]))

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
        (jruby-testutils/jruby-config {:max-active-instances pool-size
                                       :multithreaded true})
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
    (add-watch (get-in pool-context [:internal :modify-instance-agent]) :flush-callback
               (fn [k a _ _]
                 (when (= k :flush-callback)
                   (remove-watch a :flush-callback)
                   (deliver flush-complete true))))
    flush-complete))

(deftest flush-jruby-after-max-borrows
  (testing "JRubyInstance is not flushed if it has not exceeded max borrows"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-test-config 2)
      (let [instance (jruby-core/borrow-from-pool pool-context :test [])
            id (:id instance)]
        (jruby-core/return-to-pool pool-context instance :test [])
        (let [instance (jruby-core/borrow-from-pool pool-context :test [])
              flush-complete (add-watch-for-flush-complete pool-context)]
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
          (let [instance @(future (jruby-core/borrow-from-pool-with-timeout
                                     new-pool-context
                                     :test
                                     []))]
            (is (not (nil? instance)))
            (jruby-core/return-to-pool pool-context instance :test []))))))
  (testing "flushing due to max borrows succeeds when we've over-borrowed"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      ;; We can check out the instance more times than `max-borrows` and return
      ;; them each in sequence, and nothing blocks or fails
      (jruby-testutils/jruby-config {:max-active-instances 3
                                     :multithreaded true
                                     :max-borrows-per-instance 2})
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
