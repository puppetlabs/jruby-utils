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

(defn add-watch-for-flush-complete
  [pool-context]
  (let [flush-complete (promise)]
    (add-watch (get-in pool-context [:internal :modify-instance-agent]) :flush-callback
               (fn [k a old-state new-state]
                 (when (= k :flush-callback)
                   (remove-watch a :flush-callback)
                   (deliver flush-complete true))))
    flush-complete))

(deftest more-borrows-than-mrpi
  (testing "flushing due to max borrows succeeds when we've over-borrowed")
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
      (jruby-core/return-to-pool pool-context instance2 :test [])
      (is (not (realized? flush-complete)))
      (jruby-core/return-to-pool pool-context instance3 :test [])
      (is @flush-complete))
    (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
      (is (= 2 (:id instance)))
      (jruby-core/return-to-pool pool-context instance :test []))))


(deftest flush-jruby-after-max-borrows
  (testing "JRubyInstance is not flushed if it has not exceeded max borrows"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-test-config 2)
      (let [instance (jruby-core/borrow-from-pool pool-context :test [])
            id (:id instance)]
        (jruby-core/return-to-pool pool-context instance :test [])
        (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
          (is (= id (:id instance)))
          (jruby-core/return-to-pool pool-context instance :test [])))))
  (testing "JRubyInstance is flushed after exceeding max borrows"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-test-config 2)
      (jruby-testutils/wait-for-jrubies-from-pool-context pool-context)
      (is (= 1 (count (jruby-core/registered-instances pool-context))))
      (let [instance (jruby-core/borrow-from-pool pool-context :test [])
            id (:id instance)]
        (jruby-core/return-to-pool pool-context instance :test [])
        (jruby-core/borrow-from-pool pool-context :test [])
        (jruby-core/return-to-pool pool-context instance :test [])
        (let [instance (jruby-core/borrow-from-pool pool-context :test [])]
          (is (not= id (:id instance)))
          (jruby-core/return-to-pool pool-context instance :test [])))
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
          (jruby-core/return-to-pool pool-context instance :test [])))))
  (testing "Cannot flush the JRubyInstance while it is borrowed"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-test-config 2 3)
      (let [reference1 (jruby-core/borrow-from-pool pool-context :test [])
            reference2 (jruby-core/borrow-from-pool pool-context :test [])
            id (:id reference2)]
        (jruby-core/return-to-pool pool-context reference2 :test [])
        ;; borrow a third time and confirm we get the same instance
        (let [reference3 (jruby-core/borrow-from-pool pool-context :test [])
              flushed (promise)]
          (is (= id (:id reference3)))
          (jruby-core/return-to-pool pool-context reference3 :test [])

          (jruby-core/return-to-pool pool-context reference1 :test [])
          (is (realized? flushed)))
        ;; Should be a new jruby instance
        (let [new-borrow (jruby-core/borrow-from-pool pool-context :test [])]
          (is (not= id (:id new-borrow)))
          (jruby-core/return-to-pool pool-context new-borrow :test []))))))
