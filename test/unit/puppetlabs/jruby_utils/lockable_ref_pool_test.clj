(ns puppetlabs.jruby_utils.lockable-ref-pool-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils])
  (:import (com.puppetlabs.jruby_utils.pool ReferencePool)
           (java.util.concurrent TimeUnit ExecutionException TimeoutException)))

(defn timed-deref
  [ref]
  (deref ref 10000 :timed-out))

(defn create-empty-pool
  ([] (create-empty-pool 10))
  ([maxBorrows]
   (ReferencePool. maxBorrows)))

(defn create-populated-pool
  ([] create-populated-pool 10)
  ([size]
   (let [pool (create-empty-pool size)]
     (.register pool (str "foo"))
     pool)))

(defn borrow-n-instances
  [pool n]
  (doall (for [_ (range n)]
           (.borrowItem pool))))

(defn return-instances
  [pool instances]
  (doseq [instance instances]
    (.releaseItem pool instance)))

(deftest pool-register-above-maximum-throws-exception-test
  (testing "attempt to register new instance with pool at max capacity fails"
    (let [pool (create-empty-pool)]
      (.register pool "foo ok")
      (is (thrown? IllegalStateException
                   (.register pool "foo bar"))))))

(deftest pool-lock-is-blocking-until-borrows-returned-test
  (let [pool (create-populated-pool 3)
        instances (borrow-n-instances pool 3)
        future-started? (promise)
        lock-acquired? (promise)
        unlock? (promise)]
    (is (= 3 (count instances)))
    (is (not (.isLocked pool)))
    (let [lock-thread (future (deliver future-started? true)
                              (.lock pool)
                              (deliver lock-acquired? true)
                              @unlock?
                              (.unlock pool)
                              true)]
      @future-started?
      (testing "pool.lock() blocks until all instances are returned to the pool"
        (is (not (realized? lock-acquired?)))

        (testing (str "other threads may successfully return instances while "
                      "pool.lock() is being executed")
          (.releaseItem pool (first instances))
          (is (not (realized? lock-acquired?)))
          (.releaseItem pool (second instances))
          (is (not (realized? lock-acquired?)))
          (.releaseItem pool (nth instances 2)))

        (is (true? (timed-deref lock-acquired?))
            "timed out waiting for the lock to be acquired")
        (is (not (realized? lock-thread)))
        (is (.isLocked pool))
        (deliver unlock? true)
        (is (true? (timed-deref lock-thread))
            "timed out waiting for the lock thread to finish")
        (is (not (.isLocked pool))))

      (testing "borrows may be resumed after unlock()"
        (let [instance (.borrowItem pool)]
          (.releaseItem pool instance))
        ;; make sure we got here
        (is (true? true))))))

(deftest pool-lock-blocks-borrows-test
  (testing "no other threads may borrow once pool.lock() has been invoked (before or after it returns)"
    (let [pool (create-populated-pool 2)
          instances (borrow-n-instances pool 2)
          lock-thread-started? (promise)
          lock-acquired? (promise)
          unlock? (promise)]
      (is (= 2 (count instances)))
      (is (not (.isLocked pool)))
      (let [lock-thread (future (deliver lock-thread-started? true)
                                (.lock pool)
                                (deliver lock-acquired? true)
                                @unlock?
                                (.unlock pool)
                                true)]
        @lock-thread-started?
        (is (not (realized? lock-acquired?)))
        (let [borrow-after-lock-requested-thread-started? (promise)
              borrow-after-lock-requested-instance-acquired? (promise)
              borrow-after-lock-requested-thread
              (future (deliver borrow-after-lock-requested-thread-started? true)
                      (let [instance (.borrowItem pool)]
                        (deliver borrow-after-lock-requested-instance-acquired? true)
                        (.releaseItem pool instance))
                      true)]
          @borrow-after-lock-requested-thread-started?
          (is (not (realized? borrow-after-lock-requested-instance-acquired?)))

          (return-instances pool instances)
          (is (true? (timed-deref lock-acquired?))
              "timed out waiting for the lock acquired thread to start")
          (is (.isLocked pool))
          (is (not (realized? borrow-after-lock-requested-instance-acquired?)))
          (is (not (realized? lock-thread)))

          (let [borrow-after-lock-acquired-thread-started? (promise)
                borrow-after-lock-acquired-instance-acquired? (promise)
                borrow-after-lock-acquired-thread
                (future (deliver borrow-after-lock-acquired-thread-started?
                                 true)
                        (let [instance (.borrowItem pool)]
                          (deliver borrow-after-lock-acquired-instance-acquired? true)
                          (.releaseItem pool instance))
                        true)]
            @borrow-after-lock-acquired-thread-started?
            (is (not (realized? borrow-after-lock-acquired-instance-acquired?)))

            (deliver unlock? true)

            (is (true? (timed-deref lock-thread))
                "timed out waiting for the lock thread to finish")
            (is (true? (timed-deref
                        borrow-after-lock-requested-instance-acquired?))
                (str "timed out waiting for the borrow after lock requested "
                     "to be completed"))
            (is (true? (timed-deref
                        borrow-after-lock-requested-thread))
                (str "timed out waiting for the borrow after lock requested "
                     "thread to finish"))
            (is (true? (timed-deref
                        borrow-after-lock-acquired-instance-acquired?))
                "timed out waiting for the borrow after lock acquired")
            (is (true? (timed-deref
                        borrow-after-lock-acquired-thread))
                (str "timed out waiting for the borrow after lock acquired "
                     "thread to finish")))))
            (is (not (.isLocked pool))))))

(deftest pool-lock-supersedes-existing-borrows-test
  (testing "if there are pending borrows when pool.lock() is called, they aren't fulfilled until after unlock()"
    (let [pool (create-populated-pool 2)
          instances (borrow-n-instances pool 2)
          blocked-borrow-thread-started? (promise)
          blocked-borrow-thread-borrowed? (promise)
          blocked-borrow-thread (future (deliver blocked-borrow-thread-started? true)
                                        (let [instance (.borrowItem pool)]
                                          (deliver blocked-borrow-thread-borrowed? true)
                                          (.releaseItem pool instance))
                                        true)
          lock-thread-started? (promise)
          lock-acquired? (promise)
          unlock? (promise)
          lock-thread (future (deliver lock-thread-started? true)
                              (.lock pool)
                              (deliver lock-acquired? true)
                              @unlock?
                              (.unlock pool)
                              true)]
      @blocked-borrow-thread-started?
      @lock-thread-started?
      (is (not (realized? blocked-borrow-thread-borrowed?)))
      (let [start (System/currentTimeMillis)]
        (while (and (not (.isLocked pool))
                    (< (- (System/currentTimeMillis) start) 10000))
          (Thread/yield)))
      (is (.isLocked pool))
      (is (not (realized? lock-acquired?)))

      (return-instances pool instances)
      (is (not (realized? blocked-borrow-thread-borrowed?)))
      (is (not (realized? lock-thread)))
      (is (true? (timed-deref lock-acquired?))
          "timed out waiting for the lock to be acquired")
      (is (.isLocked pool))

      (deliver unlock? true)
      (is (true? (timed-deref lock-thread))
          "timed out waiting for the lock thread to finish")
      (is (true? (timed-deref blocked-borrow-thread-borrowed?))
          "timed out waiting for the borrow to complete")
      (is (true? (timed-deref blocked-borrow-thread))
          "timed out waiting for the borrow thread to complete")
      (is (not (.isLocked pool))))))

(deftest pool-lock-reentrant-for-borrow-from-locking-thread
  (testing "the thread that holds the pool lock may borrow instances while holding the lock"
    (let [pool (create-populated-pool 2)]
      (is (not (.isLocked pool)))
      (.lock pool)
      (is (.isLocked pool))
      (let [instance (.borrowItem pool)]
        (is (true? true))
        (.releaseItem pool instance))
      (is (true? true))
      (.unlock pool)
      (is (not (.isLocked pool))))))

(deftest pool-lock-reentrant-with-many-borrows-test
  (testing "the thread that holds the pool lock may borrow instances while holding the lock, even with other borrows queued"
    (let [pool (create-populated-pool 2)]
      (is (not (.isLocked pool)))
      (.lock pool)
      (is (.isLocked pool))
      (let [borrow-thread-started-1? (promise)
            borrow-thread-started-2? (promise)
            borrow-thread-borrowed-1? (promise)
            borrow-thread-borrowed-2? (promise)
            borrow-thread-1 (future (deliver borrow-thread-started-1? true)
                                    (let [instance (.borrowItem pool)]
                                      (deliver borrow-thread-borrowed-1? true)
                                      (.releaseItem pool instance))
                                    true)
            borrow-thread-2 (future (deliver borrow-thread-started-2? true)
                                    (let [instance (.borrowItem pool)]
                                      (deliver borrow-thread-borrowed-2? true)
                                      (.releaseItem pool instance))
                                    true)]
        @borrow-thread-started-1?
        @borrow-thread-started-2?
        (is (not (realized? borrow-thread-borrowed-1?)))
        (is (not (realized? borrow-thread-borrowed-2?)))

        (let [instance (.borrowItem pool)]
          (is (true? true))
          (.releaseItem pool instance))

        (is (true? true))
        (.unlock pool)
        (is (true? (timed-deref borrow-thread-1))
            "timed out waiting for first borrow thread to finish")
        (is (true? (timed-deref borrow-thread-2))
            "timed out waiting for second borrow thread to finish"))
        (is (not (.isLocked pool))))))

(deftest pool-lock-reentrant-for-many-locks-test
  (testing "multiple threads cannot lock the pool while it is already locked"
    (let [pool (create-populated-pool 1)]
      (is (not (.isLocked pool)))
      (.lock pool)
      (is (.isLocked pool))
      (let [lock-thread-started-1? (promise)
            lock-thread-started-2? (promise)
            lock-thread-locked-1? (promise)
            lock-thread-locked-2? (promise)
            lock-thread-1 (future (deliver lock-thread-started-1? true)
                                  (.lock pool)
                                  (deliver lock-thread-locked-1? true)
                                  (.unlock pool)
                                  true)
            lock-thread-2 (future (deliver lock-thread-started-2? true)
                                  (.lock pool)
                                  (deliver lock-thread-locked-2? true)
                                  (.unlock pool)
                                  true)]
        @lock-thread-started-1?
        @lock-thread-started-2?
        (is (not (realized? lock-thread-locked-1?)))
        (is (not (realized? lock-thread-locked-2?)))
        (.unlock pool)
        (is (true? (timed-deref lock-thread-1))
            "timed out waiting for first lock thread to finish")
        (is (true? (timed-deref lock-thread-2))
            "timed out waiting for second lock thread to finish"))
        (is (not (.isLocked pool))))))

(deftest pool-lock-not-held-after-thread-interrupt
  (let [pool (create-populated-pool 1)
        item (.borrowItem pool)
        lock-thread-obj (promise)
        lock-thread-locked? (promise)
        lock-thread (future (deliver lock-thread-obj (Thread/currentThread))
                            (.lock pool)
                            (deliver lock-thread-locked? true))]
    (testing (str "write locker's thread can be interrupted while waiting for "
                  "instances to be returned")
      (.interrupt @lock-thread-obj)
      (is (thrown? ExecutionException (timed-deref lock-thread)))
      (is (not (realized? lock-thread-locked?))))
      (is (not (.isLocked pool)))

    (.releaseItem pool item)
    (testing "new write lock can be taken after prior write lock interrupted"
      (.lock pool)
      (is (.isLocked pool)))))

(deftest pool-unlock-from-thread-not-holding-lock-fails
  (testing "call to unlock pool when no lock held throws exception"
    (let [pool (create-populated-pool 1)]
      (is (thrown? IllegalStateException (.unlock pool)))))
  (testing "call to unlock pool from thread not holding lock throws exception"
    (let [pool (create-populated-pool 1)
          lock-started? (promise)
          unlock? (promise)
          lock-thread (future (.lock pool)
                              (deliver lock-started? true)
                              @unlock?
                              (.unlock pool)
                              true)]
      @lock-started?
      (is (.isLocked pool))
      (is (thrown? IllegalStateException (.unlock pool)))
      (deliver unlock? true)
      (is (true? (timed-deref lock-thread))
          "timed out waiting for lock thread to finish")
      (is (not (.isLocked pool))))))

(deftest pool-lock-with-timeout-test
  (testing "lock is granted if timeout is not exceeded"
    (let [pool (create-populated-pool 1)]
      (.lockWithTimeout pool 1 TimeUnit/NANOSECONDS)
      (is (.isLocked pool))))
  (testing "lock throws TimeoutException if timeout is exceeded"
    (let [pool (create-populated-pool 1)
          borrowed-instance (.borrowItem pool)]
      (is (thrown-with-msg?
           TimeoutException
           #"Timeout limit reached before lock could be granted"
           (.lockWithTimeout pool 1 TimeUnit/NANOSECONDS)
           (is (not (.isLocked pool))))))))

(deftest pool-release-item-test
  (testing "releaseItem returns item to pool and allows pool to still be lockable"
    (let [pool (create-populated-pool 2)
          instance (.borrowItem pool)]
      (is (= 1 (.size pool)))
      (.releaseItem pool instance)
      (is (= 2 (.size pool)))
      (is (not (.isLocked pool)))
      (.lock pool)
      (is (.isLocked pool))
      (is (nil? (timed-deref
                 (future (.borrowItemWithTimeout pool
                                                 1
                                                 TimeUnit/MICROSECONDS))))
          "timed out waiting for borrow with timeout in lock to finish")
      (.unlock pool)
      (is (not (nil? (timed-deref
                      (future (.borrowItemWithTimeout pool
                                                      1
                                                      TimeUnit/MICROSECONDS)))))
          "timed out waiting for borrow with timeout after unlock to finish")
      (is (not (.isLocked pool))))))

(deftest pool-borrow-blocks-borrow-when-pool-empty
  (testing "borrow from pool blocks while the pool is empty"
    (let [pool (create-populated-pool 1)
          item (.borrowItem pool)
          borrow-thread-started? (promise)
          borrow-thread-borrowed? (promise)
          borrow-thread (future (deliver borrow-thread-started? true)
                                (let [instance (.borrowItem pool)]
                                  (deliver borrow-thread-borrowed? true)
                                  (.releaseItem pool instance))
                                true)]
      @borrow-thread-started?
      (is (not (realized? borrow-thread-borrowed?)))
      (.releaseItem pool item)
      (is (true? (timed-deref borrow-thread))
          "timed out waiting for borrow thread to finish"))))

(deftest pool-timed-borrows-test
  (testing "pool borrows with timeout"
    (let [pool (create-populated-pool 1)
          item (.borrowItem pool)]
      (testing "borrow times out and returns nil when pool is empty"
        (is (nil? (timed-deref
                   (future (.borrowItemWithTimeout pool
                                                   1
                                                   TimeUnit/MICROSECONDS))))))
      (.releaseItem pool item)
      (testing "borrow succeeds when pool is non-empty"
        (is (identical? item (timed-deref
                              (future (.borrowItemWithTimeout
                                       pool
                                       1
                                       TimeUnit/MICROSECONDS)))))))))

(deftest pool-can-do-blocking-borrow-after-borrow-timed-out
  (testing "can do blocking borrow from pool after previous borrow timed out"
    (let [pool (create-populated-pool 1)
          item (.borrowItem pool)]
      (is (nil? (.borrowItemWithTimeout pool 1 TimeUnit/MICROSECONDS)))
      (let [borrow-thread-started? (promise)
            borrow-thread-borrowed? (promise)
            borrow-thread (future (deliver borrow-thread-started? true)
                                  (let [instance (.borrowItem pool)]
                                    (deliver borrow-thread-borrowed? true)
                                    (.releaseItem pool instance))
                                  true)]
        @borrow-thread-started?
        (is (not (realized? borrow-thread-borrowed?)))
        (.releaseItem pool item)
        (is (true? (timed-deref borrow-thread))
            "timed out waiting for borrow thread to finish")))))

(deftest pool-can-do-blocking-borrow-after-borrow-timed-out-during-lock
  (testing (str "can do a blocking borrow from pool after previous borrow "
                "timed out while a write lock was held")
    (let [pool (create-populated-pool 1)]
      (.lock pool)
      (is (.isLocked pool))
      (is (nil? (timed-deref
                 (future (.borrowItemWithTimeout pool
                                                 1
                                                 TimeUnit/MICROSECONDS))))
          "timed out waiting for borrow with timeout to finish")

      (let [borrow-thread-started? (promise)
            borrow-thread-borrowed? (promise)
            borrow-thread (future (deliver borrow-thread-started? true)
                                  (let [instance (.borrowItem pool)]
                                    (deliver borrow-thread-borrowed? true)
                                    (.releaseItem pool instance))
                                  true)]
        @borrow-thread-started?
        (is (not (realized? borrow-thread-borrowed?)))
        (.unlock pool)
        (is (true? (timed-deref borrow-thread))
            "timed out waiting for borrow thread to finish")
        (is (not (.isLocked pool)))))))

(deftest pool-can-borrow-after-borrow-interrupted-during-lock
  (testing (str "can do a borrow after another borrow was interrupted "
                "while a write lock was held")
    (let [pool (create-populated-pool 1)
          borrow-1 (.borrowItem pool)
          borrow-thread-started-2 (promise)
          borrow-thread-started-3? (promise)
          lock-thread-locked? (promise)
          borrow-thread-2 (future
                           (deliver borrow-thread-started-2
                                    (Thread/currentThread))
                           (timed-deref lock-thread-locked?)
                           (.borrowItem pool))
          borrow-thread-3 (future
                           (deliver borrow-thread-started-3? true)
                           (timed-deref lock-thread-locked?)
                           (.borrowItem pool))
          borrow-thread-obj-2 @borrow-thread-started-2
          _ @borrow-thread-started-3?
          lock-thread-started? (promise)
          unlock? (promise)
          lock-thread (future
                       (deliver lock-thread-started? true)
                       (.lock pool)
                       (deliver lock-thread-locked? true)
                       @unlock?
                       (.unlock pool)
                       true)]
      @lock-thread-started?
      (.releaseItem pool borrow-1)
      (is (true? (timed-deref lock-thread-locked?))
          "timed out waiting for the lock to be acquired")
      (is (true? (.isLocked pool)))
      ;; Interrupt the second borrow thread so that it will stop waiting for the
      ;; pool to be not empty and not locked.
      (.interrupt borrow-thread-obj-2)
      (is (thrown? ExecutionException (timed-deref borrow-thread-2))
          "second borrow could not be interrupted")
      (is (not (realized? borrow-thread-3)))
      (deliver unlock? true)
      (is (true? (timed-deref lock-thread))
          "timed out waiting for the lock thread to finish")
      (is (not (.isLocked pool)))
      ;; If the third borrow doesn't block indefinitely, we've confirmed that
      ;; the interruption of the second borrow while the write lock was
      ;; held did not adversely affect the ability of the third borrow call
      ;; to return.
      (is (identical? borrow-1 (timed-deref borrow-thread-3))
          (str "did not get back the same instance from the third borrow "
               "attempt as was returned from the first borrow attempt")))))

(deftest pool-can-borrow-after-borrow-timed-out-during-lock
  (testing (str "can do a timed borrow from pool after previous borrow "
                "timed out while a write lock was held")
    (let [pool (create-populated-pool 1)
          borrow-1 (.borrowItem pool)
          borrow-thread-started-2? (promise)
          borrow-thread-started-3? (promise)
          lock-thread-locked? (promise)
          borrow-thread-2 (future
                           (deliver borrow-thread-started-2? true)
                           (timed-deref lock-thread-locked?)
                           (.borrowItemWithTimeout
                            pool
                            50
                            TimeUnit/MILLISECONDS))
          borrow-thread-3 (future
                           (deliver borrow-thread-started-3? true)
                           (timed-deref lock-thread-locked?)
                           (.borrowItemWithTimeout pool
                                                   1
                                                   TimeUnit/SECONDS))
          _ @borrow-thread-started-2?
          _ @borrow-thread-started-3?
          unlock? (promise)
          lock-thread-started? (promise)
          lock-thread (future
                       (deliver lock-thread-started? true)
                       (.lock pool)
                       (deliver lock-thread-locked? true)
                       @unlock?
                       (.unlock pool)
                       true)]
      @lock-thread-started?
      (.releaseItem pool borrow-1)
      (is (true? (timed-deref lock-thread-locked?))
          "timed out waiting for the lock to be acquired")
      (is (.isLocked pool))
      (is (nil? (timed-deref borrow-thread-2))
          "second borrow attempt did not time out")
      (deliver unlock? true)
      (is (true? (timed-deref lock-thread))
          "timed out waiting for the lock thread to finish")
      (is (not (.isLocked pool)))
      (is (identical? borrow-1 (timed-deref borrow-thread-3))
          (str "did not get back the same instance from the third borrow"
               "attempt as was returned from the first borrow attempt")))))

(deftest pool-insert-pill-test
  (testing "inserted pill is next item borrowed"
    (let [pool (create-populated-pool 1)
          pill (str "i'm a pill")]
      (.insertPill pool pill)
      (is (identical? (.borrowItem pool) pill))
      (testing "subsequent borrows return the same pill"
        (is (identical? (.borrowItem pool) pill)))))
  (testing "when borrow is blocked, inserting a pill unblocks it"
    (let [pool (create-populated-pool 1)
          pill (str "I'm just a pill, yes I'm only a pill")
          _ (.borrowItem pool)
          blocked-borrow (future (.borrowItem pool))]
      (is (= 0 (.size pool)))

      ; Give future a chance to run and block
      (Thread/sleep 500)
      ; Pool is empty and the borrow future is blocked
      (is (not (realized? blocked-borrow)))
      ; inserting the pill should unblock the promise
      (.insertPill pool pill)
      ; borrow finishes and gives us back the pill
      (let [future-result (timed-deref blocked-borrow)]
        (is (identical? future-result pill)))))

  (testing "second insert doesn't change the pill"
    (let [pool (create-populated-pool 1)
          first-pill (str "pill clinton")
          second-pill (str "pillary clinton")]
      (.insertPill pool first-pill)
      (is (identical? first-pill (.borrowItem pool)))

      (.insertPill pool second-pill)
      ; Should still be equal to the first pill
      (is (identical? first-pill (.borrowItem pool)))
      (is (not (identical? second-pill (.borrowItem pool)))))))

(deftest release-item-exceptions-test
  (testing "releasing a different pill than the one that was inserted errors"
    (let [pool (create-populated-pool 1)
          first-pill (str "a city upon a pill")
          second-pill (str "capitol pill")]
      (.insertPill pool first-pill)
      ; Only to show that it does not error
      (is (nil? (.releaseItem pool first-pill)))
      (is (thrown-with-msg?
           IllegalArgumentException
           #"The item being released is not registered with the pool"
           (.releaseItem pool second-pill)))))

  (testing "releasing a jruby not registered with the pool errors"
    (let [pool (create-populated-pool 1)
          not-in-pool-instance "I was never registered"]
      (is (thrown-with-msg?
           IllegalArgumentException
           #"The item being released is not registered with the pool"
           (.releaseItem pool not-in-pool-instance))))))

(deftest lock-interrupted-by-pill-insertion-test
  (testing "a call to .lock will throw if there is a pill"
    (let [pool (create-populated-pool 1)
          pill "pilliam shakespeare"]
      (.insertPill pool pill)
      (is (thrown-with-msg?
           InterruptedException
           #"Lock can't be granted because a pill has been inserted"
           (.lock pool)))))

  (testing "a blocked .lock call throws an InterruptedException once a pill is inserted"
    (let [pool (create-populated-pool 1)
          pill "pillful ignorance"]
      ; Make it so the pool is not full
      (.borrowItem pool)

      ; Exceptions thrown from the future will be returned as InterruptedException,
      ; so we can't use thrown-with-msg?. We'll catch it, return it instead of
      ; throwing it, and inspect it manually below
      (let [blocked-lock-future (future (try (.lock pool)
                                             (catch InterruptedException e
                                               e)))]
        ; The future's thread will take the lock, and then block waiting for
        ; either the pool to fill up, or a pill to be inserted
        (jruby-testutils/wait-for-pool-to-be-locked pool)
        (.insertPill pool pill)
        (let [exception @blocked-lock-future]
          (is (= InterruptedException (type exception)))
          (is (= "Lock can't be granted because a pill has been inserted"
                 (.getMessage exception))))))))

(deftest pool-clear-test
  (testing (str "clear blocks waiting for all references to be returned")
    (let [pool (create-populated-pool 2)
          instance (.borrowItem pool)
          cleared? (promise)
          _ (future
              (.clear pool)
              (deliver cleared? true))]
      (is (= 1 (.size pool)))
      (is (= false (realized? cleared?)))
      (.releaseItem pool instance)
      (is (= true @cleared?))
      (is (= 0 (.size pool))))))

(deftest pool-remaining-capacity
  (testing "remaining capacity in pool correct per instances registered"
    (let [empty-pool (create-empty-pool)
          pool (create-populated-pool 5)]
      (is (= 1 (.remainingCapacity empty-pool)))
      (is (= 0 (.remainingCapacity pool))))))
