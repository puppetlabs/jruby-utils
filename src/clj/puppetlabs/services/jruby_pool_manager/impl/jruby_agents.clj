(ns puppetlabs.services.jruby-pool-manager.impl.jruby-agents
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas])
  (:import (clojure.lang IFn IDeref)
           (puppetlabs.services.jruby_pool_manager.jruby_schemas PoisonPill JRubyInstance)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn ^:always-validate
  next-instance-id :- schema/Int
  [id :- schema/Int
   pool-context :- jruby-schemas/PoolContext]
  (let [pool-size (jruby-internal/get-pool-size pool-context)
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

(schema/defn ^:always-validate
  prime-pool!
  "Sequentially fill the pool with new JRubyInstances.  NOTE: this
  function should never be called except by the modify-instance-agent."
  [{:keys [config] :as pool-context} :- jruby-schemas/PoolContext]
  (let [pool (jruby-internal/get-pool pool-context)]
    (log/debug (str "Initializing JRubyInstances with the following settings:\n"
                    (ks/pprint-to-string config)))
    (try
      (let [count (.remainingCapacity pool)]
        (dotimes [i count]
          (let [id (inc i)]
            (log/debugf "Priming JRubyInstance %d of %d" id count)
            (jruby-internal/create-pool-instance! pool id config
                                                  (partial send-flush-instance! pool-context))
            (log/infof "Finished creating JRubyInstance %d of %d"
                       id count))))
      (catch Exception e
        (.clear pool)
        (jruby-internal/insert-poison-pill pool e)

        (throw (IllegalStateException. "There was a problem adding a JRubyInstance to the pool." e))))))

(schema/defn ^:always-validate
  flush-instance!
  "Flush a single JRubyInstance.  Create a new replacement instance
  and insert it into the specified pool. Should only be called from
  the modify-instance-agent"
  [pool-context :- jruby-schemas/PoolContext
   instance :- JRubyInstance
   new-id :- schema/Int
   config :- jruby-schemas/JRubyConfig]
  (let [cleanup-fn (get-in pool-context [:config :lifecycle :cleanup])
        pool (jruby-internal/get-pool pool-context)]
    (jruby-internal/cleanup-pool-instance! instance cleanup-fn)
    (jruby-internal/create-pool-instance! pool new-id config
                                          (partial send-flush-instance! pool-context))))

(schema/defn borrow-all-jrubies :- [JRubyInstance]
  "Locks the pool and borrows all the instances"
  [pool-context :- jruby-schemas/PoolContext]
  (let [pool-size (jruby-internal/get-pool-size pool-context)
        pool (jruby-internal/get-pool pool-context)
        borrow-fn (partial jruby-internal/borrow-from-pool pool-context)]
    (.lock pool)
    (try
      (into [] (repeatedly pool-size borrow-fn))
      (catch Exception e
        (.clear pool)
        (jruby-internal/insert-poison-pill pool e)
        (throw (IllegalStateException.
                "There was a problem borrowing a JRubyInstance from the pool."
                e)))
      (finally
        (.unlock pool)))))

(schema/defn cleanup-and-refill-pool
  "Cleans up the given instances and optionally refills the pool with
  new instances. Should only be called from the modify-instance-agent"
  [pool-context :- jruby-schemas/PoolContext
   old-instances :- [JRubyInstance]
   refill? :- schema/Bool
   on-complete :- IDeref]
  (let [pool (jruby-internal/get-pool pool-context)
        pool-size (jruby-internal/get-pool-size pool-context)
        new-instance-ids (map inc (range pool-size))
        config (:config pool-context)
        cleanup-fn (get-in config [:lifecycle :cleanup])]
    (doseq [[old-instance new-id] (zipmap old-instances new-instance-ids)]
      (try
        (jruby-internal/cleanup-pool-instance! old-instance cleanup-fn)
        (when refill?
          (jruby-internal/create-pool-instance! pool new-id config
                                                (partial send-flush-instance! pool-context))
          (log/infof "Finished creating JRubyInstance %d of %d"
                     new-id pool-size))
        (catch Exception e
          (.clear pool)
          (jruby-internal/insert-poison-pill pool e)
          (throw (IllegalStateException.
                  "There was a problem creating a JRubyInstance for the pool."
                  e))))))
  (if refill?
    (log/info "Finished draining and refilling pool.")
    (log/info "Finished draining pool."))
  (deliver on-complete true))

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
     (log/info "Draining and refilling JRuby pool.")
     (log/info "Draining JRuby pool."))
   (let [shutdown-on-error (get-shutdown-on-error-fn pool-context)
         old-instances (shutdown-on-error #(borrow-all-jrubies pool-context))]
     (log/info "Borrowed all JRuby instances, proceeding with cleanup.")
     (send-agent (get-modify-instance-agent pool-context)
                 #(cleanup-and-refill-pool pool-context old-instances refill? on-complete)))))

(schema/defn ^:always-validate
  flush-pool-for-shutdown!
  "Flush of the current JRuby pool when shutting down during a stop.
  Delivers the on-complete promise when the pool has been flushed."
  ;; Since the drain-pool! function takes the pool lock, we know that if we
  ;; receive multiple flush requests before the first one finishes, they will
  ;; be queued up waiting for the lock, which will never be granted because this
  ;; function does not refill the pool, but instead inserts a shutdown poison pill
  [pool-context :- jruby-schemas/PoolContext
   on-complete :- IDeref]
  (log/debug "Beginning flush of JRuby pools for shutdown")
  (let [pool-state (jruby-internal/get-pool-state pool-context)
        pool (:pool pool-state)]
    (drain-and-refill-pool! pool-context false on-complete)
    (jruby-internal/insert-shutdown-poison-pill pool))
  (log/debug "Finished flush of JRuby pools for shutdown")
  @on-complete)

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
  been borrowed from the pool, and before the pool has been refilled"
  [pool-context :- jruby-schemas/PoolContext]
  ;; Since the drain-and-refill-pool! function takes the pool lock, we know that if we
  ;; receive multiple flush requests before the first one finishes, they will
  ;; be queued up waiting for the lock, which can't be granted until all the instances
  ;; are returned to the pool, which won't be done until sometimes after
  ;; this function exits
  (log/info "Flush request received; flushing old JRuby instances.")
  (drain-and-refill-pool! pool-context true))

(schema/defn ^:always-validate
  send-flush-instance! :- jruby-schemas/JRubyPoolAgent
  "Sends requests to the flush-instance agent to flush the instance and create a new one."
  [pool-context :- jruby-schemas/PoolContext
   instance :- JRubyInstance]
  ;; We use an agent to syncronize jruby creation and destruction to migitage
  ;; any possible race conditions in the underlying jruby scripting container
  (let [{:keys [config]} pool-context
        modify-instance-agent (get-modify-instance-agent pool-context)
        id (next-instance-id (:id instance) pool-context)]
    (send-agent modify-instance-agent #(flush-instance! pool-context instance id config))))
