(ns puppetlabs.services.jruby-pool-manager.jruby-locking-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby-pool-manager.jruby-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [schema.test :as schema-test]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol])
  (:import (java.util.concurrent TimeoutException)))

(use-fixtures :once schema-test/validate-schemas)

(defn jruby-test-config
  [pool-size]
  (jruby-testutils/jruby-config {:max-active-instances pool-size
                                 :borrow-timeout 1}))

(defn can-borrow-from-different-thread?
  [pool-context]
  @(future
    (if-let [instance (jruby-core/borrow-from-pool-with-timeout pool-context :test [])]
      (do
        (jruby-core/return-to-pool instance :test [])
        true))))

(deftest ^:integration with-lock-test
  (tk-bootstrap/with-app-with-config
   app
   jruby-testutils/default-services
   {}
   (let [config (jruby-test-config 1)
         pool-manager-service (tk-app/get-service app :PoolManagerService)
         pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
     (jruby-testutils/wait-for-jrubies-from-pool-context pool-context)
     (testing "initial state of write lock is unlocked"
       (is (can-borrow-from-different-thread? pool-context))
       (testing "with-lock macro holds write lock while executing body"
         (jruby-core/with-lock
          pool-context
          :with-lock-holds-lock-test
          (is (not (can-borrow-from-different-thread? pool-context)))))
       (testing "with-lock macro releases write lock after exectuing body"
         (is (can-borrow-from-different-thread? pool-context)))))))

(deftest ^:integration with-lock-exception-test
  (tk-bootstrap/with-app-with-config
   app
   jruby-testutils/default-services
   {}
   (let [config (jruby-test-config 1)
         pool-manager-service (tk-app/get-service app :PoolManagerService)
         pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
     (jruby-testutils/wait-for-jrubies-from-pool-context pool-context)
     (testing "initial state of write lock is unlocked"
       (is (can-borrow-from-different-thread? pool-context)))

     (testing "with-lock macro releases lock even if body throws exception"
       (is (thrown? IllegalStateException
                    (jruby-core/with-lock pool-context :with-lock-exception-test
                                          (is (not (can-borrow-from-different-thread?
                                              pool-context)))
                                          (throw (IllegalStateException. "exception")))))
       (is (can-borrow-from-different-thread? pool-context))))))

(deftest ^:integration with-lock-event-notification-test
  (testing "locking sends event notifications"
    (let [events (atom [])
          callback (fn [{:keys [type]}]
                     (swap! events conj type))]
      (tk-bootstrap/with-app-with-config
        app
        jruby-testutils/default-services
        {}
        (let [config (jruby-test-config 1)
              pool-manager-service (tk-app/get-service app :PoolManagerService)
              pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
          (jruby-testutils/wait-for-jrubies-from-pool-context pool-context)
          (jruby-core/register-event-handler pool-context callback)

          (testing "locking events trigger event notifications"
            (jruby-core/with-jruby-instance
             jruby-instance
             pool-context
             :with-lock-events-test
             (testing "borrowing a jruby triggers 'requested'/'borrow' events"
               (is (= [:instance-requested :instance-borrowed] @events))))
            (testing "returning a jruby triggers 'returned' event"
              (is (= [:instance-requested :instance-borrowed :instance-returned] @events)))
            (jruby-core/with-lock
             pool-context
             :with-lock-events-test
             (testing "acquiring a lock triggers 'lock-requested'/'lock-acquired' events"
               (is (= [:instance-requested :instance-borrowed :instance-returned
                       :lock-requested :lock-acquired] @events)))))
          (testing "releasing the lock triggers 'lock-released' event"
            (is (= [:instance-requested :instance-borrowed :instance-returned
                    :lock-requested :lock-acquired :lock-released] @events))))))))

(deftest ^:integration with-lock-and-borrow-contention-test
  (testing "contention for instances with borrows and locking handled properly"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-test-config 2)
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
       (jruby-testutils/wait-for-jrubies-from-pool-context pool-context)
       (let [instance (jruby-core/borrow-from-pool-with-timeout
                       pool-context
                       :with-lock-and-borrow-contention-test
                       [])
             lock-acquired? (promise)
             unlock-thread? (promise)
             lock-thread (future (jruby-core/with-lock
                                  pool-context
                                  :with-lock-and-borrow-contention-test
                                  (deliver lock-acquired? true)
                                  @unlock-thread?))]
         (testing "lock not granted yet when instance still borrowed"
           (is (not (realized?
                     lock-acquired?))))
         (jruby-core/return-to-pool instance :with-lock-and-borrow-contention-test [])
         @lock-acquired?
         (testing "cannot borrow from non-locking thread when locked"
           (is (not (can-borrow-from-different-thread? pool-context))))
         (deliver unlock-thread? true)
         @lock-thread
         (testing "can borrow from non-locking thread after lock released"
           (is (can-borrow-from-different-thread? pool-context))))))))

(deftest ^:integration with-lock-with-timeout-test
  (testing "can obtain lock when timeout is not exceeded"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-test-config 1)
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)
           pool (jruby-core/get-pool pool-context)]

       (jruby-core/with-lock-with-timeout
        pool-context
        10000000
        :with-lock-holds-lock-test
        (is (.isLocked pool)))
       (is (not (.isLocked pool))))))

  (testing "TimeoutException thrown when lock timeout is exceeded"
    (tk-bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-test-config 1)
           pool-manager-service (tk-app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)
           pool (jruby-core/get-pool pool-context)
           borrowed-instance (jruby-core/borrow-from-pool
                              pool-context
                              :with-lock-with-timeout-test
                              [])]

       ; Since an instance has been borrowed the lock won't be granted and should
       ; trigger the timeout immediately
       (is (thrown-with-msg?
            TimeoutException
            #"Timeout limit reached before lock could be granted"
            (jruby-core/with-lock-with-timeout
             pool-context
             1
             :with-lock-holds-lock-test
             ; should not reach here
             (is false))))
       (is (not (.isLocked pool)))
       ()))))
