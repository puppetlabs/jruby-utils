(ns puppetlabs.services.jruby-pool-manager.impl.jruby-agents
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.i18n.core :as i18n]
            [slingshot.slingshot :as sling]
            [puppetlabs.services.protocols.jruby-pool :as pool-protocol])
  (:import (clojure.lang IFn IDeref)
           (puppetlabs.services.jruby_pool_manager.jruby_schemas PoisonPill
                                                                 JRubyInstance
                                                                 ReferencePoolContext
                                                                 JRubyPoolContext)
           (java.util.concurrent TimeUnit TimeoutException)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private


(schema/defn ^:always-validate
  next-instance-id :- schema/Int
  [id :- schema/Int
   pool-context :- jruby-schemas/PoolContext]
  (let [pool-size (jruby-internal/get-instance-count pool-context)
        next-id (+ id pool-size)]
    (if (> next-id Integer/MAX_VALUE)
      (mod next-id pool-size)
      next-id)))

(schema/defn get-shutdown-on-error-fn :- IFn
  [pool-context :- jruby-schemas/PoolContext]
  (get-in pool-context [:config :lifecycle :shutdown-on-error]))

(schema/defn get-modify-instance-agent :- jruby-schemas/JRubyPoolAgent
  [pool-context :- jruby-schemas/PoolContext]
  (get-in pool-context [:internal :modify-instance-agent]))

(schema/defn ^:always-validate
  send-agent :- jruby-schemas/JRubyPoolAgent
  "Utility function; given a JRubyPoolAgent, send the specified function.
  Ensures that the function call is wrapped in a `shutdown-on-error`."
  [jruby-agent :- jruby-schemas/JRubyPoolAgent
   f :- IFn]
  (letfn [(agent-fn [agent-ctxt]
                    (let [shutdown-on-error (:shutdown-on-error agent-ctxt)]
                      (shutdown-on-error f))
                    agent-ctxt)]
    (send jruby-agent agent-fn)))

(declare send-flush-instance!)

(schema/defn flush-reference-pool-instance!
  [pool-context instance cleanup-fn]
  (let [pool-state (jruby-internal/get-pool-state-container pool-context)
        pool (jruby-internal/get-pool pool-context)
        config (:config pool-context)
        _ (.releaseItem pool instance)]
    (when (not (:flush-pending @pool-state))
      ;; trigger a flush of the Jruby pool if one hasn't already
      ;; been started
      (try
        (do
          (swap! pool-state assoc :flush-pending true)
          (.lock pool)
          (.clear pool)
          (cleanup-fn instance)
          (.terminate (:scripting-container instance))
          (jruby-internal/create-pool-instance! pool 1 config
                                                (partial send-flush-instance! pool-context)
                                                (:splay-instance-flush config))
          (log/info (i18n/trs "Finished creating JRubyInstance 1 of 1"))
          (swap! pool-state assoc :flush-pending false)
          (.unlock pool))
        (catch Exception e
         (.clear pool)
         (jruby-internal/insert-poison-pill pool e)
         (throw (IllegalStateException.
                  (i18n/trs "There was a problem flushing and refilling the pool." e))))))))

(schema/defn flush-reference-pool!
  [pool-context
   refill?
   on-complete]
  (let [pool-state (jruby-internal/get-pool-state-container pool-context)
        pool (:pool (jruby-internal/get-pool-state pool-context))
        cleanup-fn (get-in pool-context [:config :lifecycle :cleanup])]
    (when (not (:flush-pending @pool-state))
      (try
        (swap! pool-state assoc :flush-pending true)
        (.lock pool)
        (let [instance (.borrowItem pool)]
          (cleanup-fn instance)
          (.terminate (:scripting-container instance))
          (.releaseItem pool instance))
        (.clear pool)
        (when refill?
          (jruby-internal/create-pool-instance! pool 1 (:config pool-context)
                                                (partial send-flush-instance! pool-context)))
        (log/info (i18n/trs "Finished creating JRubyInstance 1 of 1"))
        (swap! pool-state assoc :flush-pending false)
        (catch Exception e
          (.clear pool)
          (.unlock pool)
          (jruby-internal/insert-poison-pill pool e)
          (throw (IllegalStateException.
                   (i18n/trs "There was a problem flushing the pool.")
                   e)))
        (finally
          (.unlock pool)
          (deliver on-complete true))))))


(extend-type ReferencePoolContext
  pool-protocol/JRubyPool
  (clear-pool!
    [this refill? on-complete]
    (flush-reference-pool! this refill? on-complete))
  (clear-instance!
    [this instance cleanup-fn]
    (flush-reference-pool-instance! this instance cleanup-fn)))

(declare drain-and-refill-pool!)

(extend-type JRubyPoolContext
  pool-protocol/JRubyPool
  (clear-pool!
    [this refill? on-complete]
    (drain-and-refill-pool! this refill? on-complete))
  (clear-instance!
    [this instance cleanup-fn]
    (let [config (:config this)
          pool (jruby-internal/get-pool this)
          new-id (next-instance-id (:id instance) this)]
      (jruby-internal/cleanup-pool-instance! instance cleanup-fn)
      (jruby-internal/create-pool-instance! pool new-id config
                                            (partial send-flush-instance! this)))))

(schema/defn ^:always-validate
  prime-pool!
  "Sequentially fill the pool with new JRubyInstances.  NOTE: this
  function should never be called except by the modify-instance-agent
  to create a pool's initial jruby instances."
  [{:keys [config] :as pool-context} :- jruby-schemas/PoolContext]
  (let [pool (jruby-internal/get-pool pool-context)]
    (log/debug
     (format "%s\n%s"
             (i18n/trs "Initializing JRubyInstances with the following settings:")
             (ks/pprint-to-string config)))
    (try
      (let [count (.remainingCapacity pool)]
        (dotimes [i count]
          (let [id (inc i)]
            (log/debug (i18n/trs "Priming JRubyInstance {0} of {1}"
                                  id count))
            (jruby-internal/create-pool-instance! pool id config
                                                  (partial send-flush-instance! pool-context)
                                                  (:splay-instance-flush config))
            (log/info (i18n/trs "Finished creating JRubyInstance {0} of {1}"
                                 id count)))))
      (catch Exception e
        (.clear pool)
        (jruby-internal/insert-poison-pill pool e)

        (throw (IllegalStateException.
                (i18n/tru "There was a problem adding a JRubyInstance to the pool.")
                e))))))

(schema/defn ^:always-validate
  flush-instance!
  "Flush a single JRubyInstance.  Create a new replacement instance
  and insert it into the specified pool. Should only be called from
  the modify-instance-agent"
  [pool-context :- jruby-schemas/PoolContext
   instance :- JRubyInstance]
  (let [cleanup-fn (get-in pool-context [:config :lifecycle :cleanup])]
    (pool-protocol/clear-instance! pool-context instance cleanup-fn)))

(schema/defn borrow-all-jrubies*
  "The core logic for borrow-all-jrubies. Should only be called from borrow-all-jrubies"
  [pool-context :- jruby-schemas/PoolContext
   borrow-exception :- IDeref]
  (let [pool-size (jruby-internal/get-pool-size pool-context)
        pool (jruby-internal/get-pool pool-context)
        borrow-fn (partial jruby-internal/borrow-from-pool pool-context)]
    (try
      (into [] (repeatedly pool-size borrow-fn))

      ; We catch the exception here, place it in the borrow-exception atom
      ; for use in the calling fn, and then throw it again so that
      ; shutdown-on-error will also catch it and shutdown the app
      (catch Exception e
        (.clear pool)
        (jruby-internal/insert-poison-pill pool e)
        (let [exception (IllegalStateException.
                         (i18n/tru "There was a problem borrowing a JRubyInstance from the pool.")
                         e)]
          (reset! borrow-exception exception)
          (throw exception)))
      (finally
        (.unlock pool)))))

(schema/defn borrow-all-jrubies :- [JRubyInstance]
  "Locks the pool and borrows all the instances"
  [pool-context :- jruby-schemas/PoolContext]
  (let [pool (jruby-internal/get-pool pool-context)
        flush-timeout (jruby-internal/get-flush-timeout pool-context)
        shutdown-on-error (get-shutdown-on-error-fn pool-context)
        borrow-exception (atom nil)]
    ; If lock fails, abort and throw an exception
    (try
      (.lockWithTimeout pool flush-timeout TimeUnit/MILLISECONDS)
      (catch TimeoutException e
        (sling/throw+ {:kind ::jruby-lock-timeout
                       :msg (i18n/trs "An attempt to lock the JRubyPool failed with a timeout")}
                      e)))

    ; Bit of a hack to work around shutdown-on-error behavior:
    ; shutdown-on-error will either return the jrubies as expected,
    ; or if there was an error, it will shutdown the server and return a promise.
    ; We want to rethrow whatever exception it encountered so that it can bubble
    ; up to whatever code requested this flush, so borrow-all-jrubies* will put
    ; that exception into the borrow-exception atom
    (let [jrubies (shutdown-on-error #(borrow-all-jrubies* pool-context borrow-exception))]
      (if-let [exception @borrow-exception]
        (throw exception)
        jrubies))))

(schema/defn cleanup-and-refill-pool
  "Cleans up the given instances and optionally refills the pool with
  new instances. Should only be called from the modify-instance-agent"
  [pool-context :- jruby-schemas/PoolContext
   old-instances :- [JRubyInstance]
   refill? :- schema/Bool]
  (let [pool (jruby-internal/get-pool pool-context)
        instance-count (jruby-internal/get-instance-count pool-context)
        new-instance-ids (map inc (range instance-count))
        config (:config pool-context)
        cleanup-fn (get-in config [:lifecycle :cleanup])]
    (doseq [[old-instance new-id] (zipmap old-instances new-instance-ids)]
      (try
        (jruby-internal/cleanup-pool-instance! old-instance cleanup-fn)
        (when refill?
          (jruby-internal/create-pool-instance! pool new-id config
                                                (partial send-flush-instance! pool-context)
                                                (:splay-instance-flush config))
          (log/info (i18n/trs "Finished creating JRubyInstance {0} of {1}"
                              new-id instance-count)))
        (catch Exception e
          (.clear pool)
          (jruby-internal/insert-poison-pill pool e)
          (throw (IllegalStateException.
                  (i18n/trs "There was a problem creating a JRubyInstance for the pool.")
                  e))))))
  (if refill?
    (log/info (i18n/trs "Finished draining and refilling pool."))
    (log/info (i18n/trs "Finished draining pool."))))

(schema/defn ^:always-validate
  drain-and-refill-pool!
  "Borrow and destroy all the jruby instances, optionally refilling the
  pool with fresh jrubies. Locks the pool in order to drain it, but releases
  the lock before destroying the instances and refilling the pool

  If an on-complete promise is given, it can be used by the caller to make
  this function syncronous. Otherwise it only blocks until the pool instances
  have been borrowed and the cleanup-and-refill-pool fn is sent to the agent"
  ([pool-context :- jruby-schemas/PoolContext
    refill? :- schema/Bool]
   (drain-and-refill-pool! pool-context refill? (promise)))
  ([pool-context :- jruby-schemas/PoolContext
    refill? :- schema/Bool
    on-complete :- IDeref]
   (if refill?
     (log/info (i18n/trs "Draining and refilling JRuby pool."))
     (log/info (i18n/trs "Draining JRuby pool.")))
   (let [old-instances (borrow-all-jrubies pool-context)
         modify-instance-agent (get-modify-instance-agent pool-context)
         ; Make sure the promise is delivered even if cleanup fails
         try-cleanup-and-refill #(try
                                   (cleanup-and-refill-pool pool-context old-instances refill?)
                                   (finally (deliver on-complete true)))]
     (log/info (i18n/trs "Borrowed all JRuby instances, proceeding with cleanup."))
     (send-agent modify-instance-agent try-cleanup-and-refill))))

(schema/defn ^:always-validate
  flush-pool-for-shutdown!
  "Flush of the current JRuby pool when shutting down during a stop."
  ;; Since the drain-pool! function takes the pool lock, we know that if we
  ;; receive multiple flush requests before the first one finishes, they will
  ;; be queued up waiting for the lock, which will never be granted because this
  ;; function does not refill the pool, but instead inserts a shutdown poison pill
  [pool-context :- jruby-schemas/PoolContext]
  (log/debug (i18n/trs "Beginning flush of JRuby pools for shutdown"))
  (let [pool-state (jruby-internal/get-pool-state pool-context)
        pool (:pool pool-state)
        on-complete (promise)]
    (pool-protocol/clear-pool! pool-context false on-complete)
    (jruby-internal/insert-shutdown-poison-pill pool)
    ; Wait for flush to complete
    @on-complete
    (log/debug (i18n/trs "Finished flush of JRuby pools for shutdown"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  pool-agent :- jruby-schemas/JRubyPoolAgent
  "Given a shutdown-on-error function, create an agent suitable for use in managing
  JRuby pools."
  [shutdown-on-error-fn :- (schema/pred ifn?)]
  (agent {:shutdown-on-error shutdown-on-error-fn}))

(schema/defn ^:always-validate
  send-prime-pool! :- jruby-schemas/JRubyPoolAgent
  "Sends a request to the agent to prime the pool using the given pool context."
  [pool-context :- jruby-schemas/PoolContext]
  (let [modify-instance-agent (get-modify-instance-agent pool-context)]
    (send-agent modify-instance-agent #(prime-pool! pool-context))))

(schema/defn ^:always-validate
  flush-and-repopulate-pool!
  "Flush of the current JRuby pool. Blocks until all the instances have
  been borrowed from the pool, but does not wait for the instances
  to be flushed or recreated"
  [pool-context :- jruby-schemas/PoolContext]
  ;; Since the drain-and-refill-pool! function takes the pool lock, we know that if we
  ;; receive multiple flush requests before the first one finishes, they will
  ;; be queued up waiting for the lock, which can't be granted until all the instances
  ;; are returned to the pool, which won't be done until sometimes after
  ;; this function exits
  (log/info (i18n/trs "Flush request received; flushing old JRuby instances."))
  (pool-protocol/clear-pool! pool-context true (promise)))

(schema/defn ^:always-validate
  send-flush-instance! :- jruby-schemas/JRubyPoolAgent
  "Sends requests to the flush-instance agent to flush the instance and create a new one."
  [pool-context :- jruby-schemas/PoolContext
   instance :- JRubyInstance]
  ;; We use an agent to syncronize jruby creation and destruction to mitigate
  ;; any possible race conditions in the underlying jruby scripting container
  (let [modify-instance-agent (get-modify-instance-agent pool-context)]
    (send-agent modify-instance-agent #(flush-instance! pool-context instance))))
