(ns puppetlabs.services.jruby.jruby-service
  (:require [clojure.tools.logging :as log]
            [puppetlabs.services.jruby.jruby-core :as core]
            [puppetlabs.services.jruby.jruby-agents :as jruby-agents]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby :as jruby]
            [slingshot.slingshot :as sling]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.jruby-internal :as jruby-internal]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; This service uses TK's normal config service instead of the
;; PuppetServerConfigService.  This is because that service depends on this one.

(trapperkeeper/defservice jruby-pooled-service
                          jruby/JRubyService
                          [[:ConfigService get-config]
                           [:ShutdownService shutdown-on-error]]
  (init
    [this context]
    (let [config (core/initialize-config (get-config))
          lifecycle-fns (-> (get-in (get-config) [:jruby :lifecycle-fns])
                            (update-in [:initialize] #(or % identity))
                            (update-in [:shutdown] #(or % identity))
                            (update-in [:initialize-env-variables]
                                       #(or % jruby-internal/default-initialize-env-variables)))
          service-id (tk-services/service-id this)
          agent-shutdown-fn (partial shutdown-on-error service-id)]
      (core/verify-config-found! config)
      (log/info "Initializing the JRuby service")
      (let [lifecycle-fns (assoc lifecycle-fns :shutdown-on-error agent-shutdown-fn)
            pool-context (core/create-pool-context config lifecycle-fns)]
        (jruby-agents/send-prime-pool! pool-context)
        (-> context
            (assoc :pool-context pool-context)
            (assoc :borrow-timeout (:borrow-timeout config))
            (assoc :event-callbacks (atom []))))))
  (stop
   [this context]
   (let [{:keys [pool-context]} (tk-services/service-context this)
         on-complete (promise)]
     (log/debug "Beginning flush of JRuby pools for shutdown")
     (jruby-agents/send-flush-pool-for-shutdown! pool-context on-complete)
     @on-complete
     (log/debug "Finished flush of JRuby pools for shutdown"))
   context)

  (borrow-instance
    [this reason]
    (let [{:keys [pool-context borrow-timeout event-callbacks]} (tk-services/service-context this)]
      (core/borrow-from-pool-with-timeout pool-context borrow-timeout reason @event-callbacks)))

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
      (jruby-agents/send-flush-and-repopulate-pool! pool-context)))

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
