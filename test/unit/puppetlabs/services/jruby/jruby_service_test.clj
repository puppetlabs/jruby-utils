(ns puppetlabs.services.jruby.jruby-service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as services]
            [clojure.stacktrace :as stacktrace]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol]
            [puppetlabs.services.jruby.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas]
            [schema.test :as schema-test])
  (:import (puppetlabs.services.jruby.jruby_schemas JRubyInstance)))

(use-fixtures :once schema-test/validate-schemas)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Trapperkeeper Service

(tk/defservice jruby-pooled-test-service
               [[:ConfigService get-config]
                [:ShutdownService shutdown-on-error]
                [:PoolManagerService create-pool]]
  (init
    [this context]
    (let [initial-config (get-config)
          service-id (services/service-id this)
          agent-shutdown-fn (partial shutdown-on-error service-id)
          config (jruby-core/initialize-config (assoc-in initial-config
                                                   [:lifecycle :shutdown-on-error]
                                                   agent-shutdown-fn))]
      (let [pool-context (create-pool config)]
        (-> context
            (assoc :pool-context pool-context)
            (assoc :event-callbacks (atom []))))))
  (stop
   [this context]
   (let [{:keys [pool-context]} (services/service-context this)]
     (jruby-core/flush-pool-for-shutdown! pool-context))
   context))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests

(defn jruby-test-config
  [pool-size]
  (jruby-testutils/jruby-config {:max-active-instances pool-size}))

(deftest test-error-during-init
  (testing
   (str "If there is an exception while putting a JRubyInstance in "
        "the pool the application should shut down.")
    (logging/with-test-logging
     (let [got-expected-exception (atom false)]
       (try
         (bootstrap/with-app-with-config
          app
          (conj jruby-testutils/default-services jruby-pooled-test-service)
          (jruby-testutils/jruby-config
           {:max-active-instances 1
            :lifecycle {:initialize-pool-instance
                        (fn [_] (throw (Exception. "42")))}})
          (tk/run-app app))
         (catch Exception e
           (let [cause (stacktrace/root-cause e)]
             (is (= (.getMessage cause) "42"))
             (reset! got-expected-exception true))))
       (is (true? @got-expected-exception)
           "Did not get expected exception.")))))

(deftest test-pool-size
  (testing "The pool is created and the size is correctly reported"
    (let [pool-size 2
          config (jruby-test-config pool-size)]
      (bootstrap/with-app-with-config
        app
        jruby-testutils/default-services
        {}
        (let [pool-manager-service (app/get-service app :PoolManagerService)
              pool-context (pool-manager-protocol/create-pool pool-manager-service config)
              pool (jruby-core/get-pool pool-context)
              all-the-instances (mapv (fn [_]
                                        (jruby-core/borrow-from-pool-with-timeout
                                         pool-context :test-pool-size []))
                                      (range pool-size))]
          (is (= 0 (jruby-core/free-instance-count pool)))
          (is (= pool-size (count all-the-instances)))
          (doseq [instance all-the-instances]
            (is (not (nil? instance))
                "One of the JRubyInstances retrieved from the pool is nil")
            (jruby-core/return-to-pool instance :test-pool-size []))
          (is (= pool-size (jruby-core/free-instance-count pool))))))))

(deftest test-with-jruby-instance
  (testing "the `with-jruby-instance macro`"
    (bootstrap/with-app-with-config
     app
     jruby-testutils/default-services
     {}
     (let [config (jruby-testutils/jruby-config {:max-active-instances 1})
           pool-manager-service (app/get-service app :PoolManagerService)
           pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
       (jruby-core/with-jruby-instance
        jruby-instance
        pool-context
        :test-with-jruby-instance
        (atom [])
        (is (instance? JRubyInstance jruby-instance))
        (is (= 0 (jruby-core/free-instance-count (jruby-core/get-pool pool-context)))))

       (is (= 1 (jruby-core/free-instance-count (jruby-core/get-pool pool-context))))
       ;; borrow and return one more time: we're using `with-jruby-instance`
       ;; here even though it looks a bit strange, because that is what this
       ;; test is intended to cover.
       (jruby-core/with-jruby-instance
        jruby-instance
        pool-context
        :test-with-jruby-instance
        (atom []))
       (let [jruby (jruby-core/borrow-from-pool-with-timeout
                    pool-context :test-with-jruby-instance [])]
         ;; the counter gets incremented when the instance is returned to the
         ;; pool, so right now it should be at 2 since we've called
         ;; `with-jruby-instance` twice.
         (is (= 2 (:borrow-count (jruby-core/instance-state jruby))))
         (jruby-core/return-to-pool jruby :test-with-jruby-instance []))))))

(deftest test-jruby-events
  (testing "jruby service sends event notifications"
    (let [counter (atom 0)
          requested (atom {})
          borrowed (atom {})
          returned (atom {})
          callback (fn [{:keys [type reason requested-event instance] :as event}]
                     (case type
                       :instance-requested
                       (reset! requested {:sequence (swap! counter inc)
                                          :event event
                                          :reason reason})

                       :instance-borrowed
                       (reset! borrowed {:sequence (swap! counter inc)
                                         :reason reason
                                         :requested-event requested-event
                                         :instance instance})

                       :instance-returned
                       (reset! returned {:sequence (swap! counter inc)
                                         :reason reason
                                         :instance instance})))
          event-callbacks (atom [])]
      (bootstrap/with-app-with-config
        app
        jruby-testutils/default-services
        {}
        (let [config (jruby-test-config 1)
              pool-manager-service (app/get-service app :PoolManagerService)
              pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
          (jruby-core/register-event-handler event-callbacks callback)
          ;; We're making an empty call to `with-jruby-instance` here, because
          ;; we want to trigger a borrow/return via the same code path that
          ;; would be used in production.
          (jruby-core/with-jruby-instance
            jruby-instance
            pool-context
            :test-jruby-events
            event-callbacks)
          (is (= {:sequence 1 :reason :test-jruby-events}
                (dissoc @requested :event)))
          (is (= {:sequence 2 :reason :test-jruby-events}
                (dissoc @borrowed :instance :requested-event)))
          (is (jruby-schemas/jruby-instance? (:instance @borrowed)))
          (is (identical? (:event @requested) (:requested-event @borrowed)))
          (is (= {:sequence 3 :reason :test-jruby-events}
                (dissoc @returned :instance)))
          (is (= (:instance @borrowed) (:instance @returned)))
          (jruby-core/with-jruby-instance
            jruby-instance
            pool-context
            :test-jruby-events
            event-callbacks)
          (is (= 4 (:sequence @requested)))
          (is (= 5 (:sequence @borrowed)))
          (is (= 6 (:sequence @returned))))))))

(deftest test-borrow-timeout-configuration
  (testing "configured :borrow-timeout is honored by the borrow-instance service function"
    (let [timeout   250
          pool-size 1
          config (jruby-testutils/jruby-config {:max-active-instances pool-size
                                                :borrow-timeout timeout})]
      (bootstrap/with-app-with-config
       app
       jruby-testutils/default-services
       {}
       (let [pool-manager-service (app/get-service app :PoolManagerService)
             pool-context (pool-manager-protocol/create-pool pool-manager-service config)]
         (let [jrubies (jruby-testutils/drain-pool pool-context pool-size)]
           (is (= 1 (count jrubies)))
           (is (every? jruby-schemas/jruby-instance? jrubies))
           (let [test-start-in-millis (System/currentTimeMillis)]
             (is (nil?
                  (jruby-core/borrow-from-pool-with-timeout
                   pool-context
                   :test-borrow-timeout-configuration
                   [])))
             (is (>= (- (System/currentTimeMillis) test-start-in-millis) timeout))
             (is (= (:borrow-timeout (:config pool-context)) timeout)))
           ; Test cleanup. This instance needs to be returned so that the stop can complete.
           (doseq [inst jrubies]
             (jruby-core/return-to-pool inst :test [])))))))

  (testing (str ":borrow-timeout defaults to " jruby-core/default-borrow-timeout " milliseconds")
    (let [initial-config {:ruby-load-path ["foo"]
                          :gem-home "bar"}
          config (jruby-core/initialize-config initial-config) ]
      (is (= (:borrow-timeout config) jruby-core/default-borrow-timeout)))))
