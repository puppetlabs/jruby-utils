(ns puppetlabs.services.jruby.jruby-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.services.jruby.jruby-core :as core]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby :as jruby]
            [slingshot.slingshot :as sling]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(trapperkeeper/defservice jruby-pooled-service
                          jruby/JRubyService
                          [[:ConfigService get-config]
                           [:ShutdownService shutdown-on-error]
                           [:PoolManagerService create-pool]]
  (init
    [this context]
    (let [initial-config (get-config)
          service-id (tk-services/service-id this)
          agent-shutdown-fn (partial shutdown-on-error service-id)
          config (core/initialize-config (assoc-in initial-config
                                                   [:jruby :lifecycle :shutdown-on-error]
                                                   agent-shutdown-fn))]
      (let [pool-context (create-pool config)]
        (-> context
            (assoc :pool-context pool-context)
            (assoc :borrow-timeout (:borrow-timeout config))
            (assoc :event-callbacks (atom []))))))
  (stop
   [this context]
   (let [{:keys [pool-context]} (tk-services/service-context this)]
     (core/flush-pool-for-shutdown! pool-context))
   context)

  (borrow-instance
    [this reason]
    (let [{:keys [pool-context event-callbacks]} (tk-services/service-context this)]
      (core/borrow-from-pool-with-timeout pool-context reason @event-callbacks)))

  (return-instance
    [this jruby-instance reason]
    (let [event-callbacks (:event-callbacks (tk-services/service-context this))]
      (core/return-to-pool jruby-instance reason @event-callbacks)))

  (free-instance-count
    [this]
    (let [pool-context (:pool-context (tk-services/service-context this))
          pool         (core/get-pool pool-context)]
      (core/free-instance-count pool)))

  (flush-jruby-pool!
    [this]
    (let [service-context (tk-services/service-context this)
          {:keys [pool-context]} service-context]
      (core/flush-pool! pool-context)))

  (register-event-handler
    [this callback-fn]
    (let [event-callbacks (:event-callbacks (tk-services/service-context this))]
      (swap! event-callbacks conj callback-fn))))

(defmacro with-jruby-instance
  "Encapsulates the behavior of borrowing and returning a JRubyInstance.
  Example usage:

  (let [jruby-service (get-service :JRubyService)]
    (with-jruby-instance
      jruby-instance
      jruby-service
      (do-something-with-a-jruby-instance jruby-instance)))

  Will throw an IllegalStateException if borrowing a JRubyInstance times out."
  [jruby-instance jruby-service reason & body]
  `(loop [pool-instance# (jruby/borrow-instance ~jruby-service ~reason)]
     (if (nil? pool-instance#)
       (sling/throw+
        {:type    ::jruby-timeout
         :message (str "Attempt to borrow a JRubyInstance from the pool timed out.")}))
     (when (jruby-schemas/shutdown-poison-pill? pool-instance#)
       (jruby/return-instance ~jruby-service pool-instance# ~reason)
       (sling/throw+
        {:type    ::service-unavailable
         :message (str "Attempted to borrow a JRubyInstance from the pool "
                       "during a shutdown. Please try again.")}))
     (if (jruby-schemas/retry-poison-pill? pool-instance#)
       (do
         (jruby/return-instance ~jruby-service pool-instance# ~reason)
         (recur (jruby/borrow-instance ~jruby-service ~reason)))
       ;; TODO rename stuff
       (let [~jruby-instance pool-instance#]
         (try
           ~@body
           (finally
             (jruby/return-instance ~jruby-service pool-instance# ~reason)))))))


(defmacro with-lock
  "Acquires a lock on the pool, executes the body, and releases the lock."
  [jruby-service reason & body]
  `(let [context# (-> ~jruby-service
                      tk-services/service-context)
         pool# (-> context#
                   :pool-context
                   core/get-pool)
         event-callbacks# (:event-callbacks context#)]
     (core/lock-pool pool# ~reason @event-callbacks#)
     (try
      ~@body
      (finally
        (core/unlock-pool pool# ~reason @event-callbacks#)))))
