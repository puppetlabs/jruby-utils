(ns puppetlabs.services.jruby-pool-manager.jruby-ref-pool-test
  (:require [clojure.test :refer :all]
            [slingshot.test :refer :all]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.protocols.jruby-pool :as pool-protocol]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core :as jruby-pool-manager-core]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas ShutdownPoisonPill)
            (java.util.concurrent TimeoutException)))

(defn jruby-test-config
  ([max-borrows]
   (jruby-test-config max-borrows 1))
  ([max-borrows max-instances]
   (jruby-testutils/jruby-config {:max-active-instances max-instances
                                  :multithreaded true
                                  :max-borrows-per-instance max-borrows}))
  ([max-borrows max-instances options]
    (jruby-testutils/jruby-config (merge {:max-active-instances max-instances
                                          :multithreaded true
                                          :max-borrows-per-instance max-borrows}
                                         options))))

(deftest worker-id-persists
  (testing "worker id is the same at borrow time and return time"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-test-config 2)
      (let [[instance borrowed-id] (pool-protocol/borrow pool-context)
            returned-id (pool-protocol/return pool-context instance)]
        (is (= (.getId (Thread/currentThread)) borrowed-id))
        (is (= (.getId (Thread/currentThread)) returned-id))))))

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

(deftest flush-times-out-on-return
  (testing "Attempt to flush times out if flush-timeout is reached"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      ;; Set max-instances higher than max-borrows in order to over-borrow
      (jruby-test-config 1 2 {:flush-timeout 1})
      ;; Borrow instance1 so we can trigger a refresh when we return it
      ;; Borrow instance2 to prevent lock from being acquired when we return instance1
      (let [instance1 (first (pool-protocol/borrow pool-context))
            instance2 (first (pool-protocol/borrow pool-context))
            flush-complete (add-watch-for-flush-complete pool-context)]
        (logutils/with-test-logging
          ;; Return to trigger flush, which should block since instance2 is still borrowed
          (pool-protocol/return pool-context instance1)
          (is (not (realized? flush-complete)))
          ;; Wait for 2 seconds to make sure the flush really times out before proceeding
          (Thread/sleep 2000)
          (is (logged? "Max borrows reached, but JRubyPool could not be flushed because lock could not be acquired. Will try again later." :warn))
          ;; Return instance2 so that flush can proceed
          (pool-protocol/return pool-context instance2)
          (is @flush-complete))))))

(deftest flush-times-out
  (testing "Attempt to flush times out if flush-timeout is reached"
    (jruby-testutils/with-pool-context
      pool-context
      jruby-testutils/default-services
      (jruby-test-config 2 1 {:flush-timeout 0})
      ;; Borrow an instance so that the lock can't be acquired
      (let [instance (first (pool-protocol/borrow pool-context))]
        (is (thrown+? [:kind :puppetlabs.services.jruby-pool-manager.impl.jruby-internal/jruby-lock-timeout
                       :msg "An attempt to lock the JRubyPool failed with a timeout"]
                      (pool-protocol/flush-pool pool-context)))
        ;; Return the instance so that shutdown can proceed (otherwise this hangs)
        (pool-protocol/return pool-context instance)))))

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

(deftest shutdown-prevents-flush
  (testing "Triggering a flush after shutdown has been requested does not break shutdown"
    (let [config (jruby-test-config 0 2)
          ;; Set up the pool manually, since we trigger a shutdown in this test,
          ;; and the macro would also try to shutdown. It's not a valid operation
          ;; to shutdown twice.
          pool-context (jruby-pool-manager-core/create-pool-context config)
          _ (jruby-agents/prime-pool! pool-context)
          pool (jruby-core/get-pool pool-context)
          _ (jruby-testutils/wait-for-jrubies-from-pool-context pool-context)
          ;; Set up test
          instance (first (pool-protocol/borrow pool-context))
          shutdown-complete? (promise)
          _ (future
              (pool-protocol/shutdown pool-context)
              (deliver shutdown-complete? true))
          _ (jruby-testutils/wait-for-pool-to-be-locked pool)
          ;; When the shutdown inserts the pill, it should interrupt the
          ;; pending lock from the flush. So we catch that exception and return it.
          flush-thread (future
                         (try (pool-protocol/flush-pool pool-context)
                              (catch InterruptedException e
                                e)))]
      ;; Shutdown should be blocked because an instance has been borrowed
      (is (not (realized? shutdown-complete?)))
      (is (not (realized? flush-thread)))
      (pool-protocol/return pool-context instance)
      @shutdown-complete?
      (let [exception @flush-thread]
        (is (= InterruptedException (type exception)))
        (is (= "Lock can't be granted because a pill has been inserted"
               (.getMessage exception)))))))

(deftest shutdown-times-out
  (testing "Attempt to shutdown times out if flush-timeout is reached"
    (let [config (jruby-test-config 3 1 {:flush-timeout 0})
          ;; Set up the pool manually, since we trigger a shutdown in this test,
          ;; and the macro would also try to shutdown. It's not a valid operation
          ;; to shutdown twice.
          pool-context (jruby-pool-manager-core/create-pool-context config)
          _ (jruby-agents/prime-pool! pool-context)
          _ (jruby-core/get-pool pool-context)
          _ (jruby-testutils/wait-for-jrubies-from-pool-context pool-context)]
      ;; Borrow an instance so that the lock can't be acquired
      (pool-protocol/borrow pool-context)
      (is (thrown+? [:kind :puppetlabs.services.jruby-pool-manager.impl.jruby-internal/jruby-lock-timeout
                     :msg "An attempt to lock the JRubyPool failed with a timeout"]
                    (pool-protocol/shutdown pool-context))))))
