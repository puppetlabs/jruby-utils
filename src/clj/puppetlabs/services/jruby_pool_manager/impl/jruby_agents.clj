(ns puppetlabs.services.jruby-pool-manager.impl.jruby-agents
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas])
  (:import (clojure.lang IFn IDeref)
           (puppetlabs.services.jruby_pool_manager.jruby_schemas PoisonPill RetryPoisonPill
                                                                 JRubyInstance ShutdownPoisonPill)))

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

(schema/defn get-pool-agent :- jruby-schemas/JRubyPoolAgent
  [pool-context :- jruby-schemas/PoolContext]
  (get-in pool-context [:internal :pool-agent]))

(schema/defn get-modify-instance-agent :- jruby-schemas/JRubyPoolAgent
  [pool-context :- jruby-schemas/PoolContext]
  (get-in pool-context [:internal :flush-instance-agent]))

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
        (.insertPill pool (PoisonPill. e))
        (throw (IllegalStateException. "There was a problem adding a JRubyInstance to the pool." e))))))

(schema/defn ^:always-validate
  flush-instance!
  "Flush a single JRubyInstance.  Create a new replacement instance
  and insert it into the specified pool. Should only be called from
  the modify-instance-agent"
  [pool-context :- jruby-schemas/PoolContext
   instance :- JRubyInstance
   new-pool :- jruby-schemas/pool-queue-type
   new-id :- schema/Int
   config :- jruby-schemas/JRubyConfig]
  (let [cleanup-fn (get-in pool-context [:config :lifecycle :cleanup])]
    (jruby-internal/cleanup-pool-instance! instance cleanup-fn)
    (jruby-internal/create-pool-instance! new-pool new-id config
                                          (partial send-flush-instance! pool-context))))

(schema/defn ^:always-validate
  pool-initialized? :- schema/Bool
  "Determine if the current pool has been fully initialized."
  [expected-pool-size :- schema/Int
   pool :- jruby-schemas/pool-queue-type]
  (= expected-pool-size (count (.getRegisteredElements pool))))

(schema/defn collect-all-jrubies :- [JRubyInstance]
  "Locks the pool and borrows all the instances"
  [pool-state :- jruby-schemas/PoolState]
  (let [pool-size (:size pool-state)
        pool (:pool pool-state)
        borrow-fn (fn [_] (jruby-internal/borrow-from-pool!*
                           jruby-internal/borrow-without-timeout-fn
                           pool))]
    (.lock pool)
    (try
      (mapv borrow-fn (range pool-size))
      (catch Exception e
        (.clear pool)
        (.insertPill pool (PoisonPill. e))
        (throw (IllegalStateException.
                "There was a problem borrowing a JRubyInstance from the pool."
                e)))
      (finally
        (.unlock pool)))))

(schema/defn cleanup-and-refill-pool
  "Cleans up the given instances and optionally refills the pool with
  new instances. Should only be called from the modify-instance-agent"
  [pool-context :- jruby-schemas/PoolContext
   pool-state :- jruby-schemas/PoolState
   old-instances :- [JRubyInstance]
   refill? :- schema/Bool]
  (let [pool (:pool pool-state)
        pool-size (:size pool-state)
        new-instance-ids (map inc (range pool-size))
        config (:config pool-context)
        cleanup-fn (get-in config [:lifecycle :cleanup])]
    (doseq [[old-instance new-id] (zipmap old-instances new-instance-ids)]
      (jruby-internal/cleanup-pool-instance! old-instance cleanup-fn)
      (when refill?
        (try
          (jruby-internal/create-pool-instance! pool new-id config
                                                (partial send-flush-instance! pool-context))
          (log/infof "Finished creating JRubyInstance %d of %d"
                     new-id pool-size)
          (catch Exception e
            (.clear pool)
            (.insertPill pool (PoisonPill. e))
            (throw (IllegalStateException.
                    "There was a problem creating a JRubyInstance for the pool."
                    e)))))))
  (log/info "Finished draining pool."))

(schema/defn ^:always-validate
  drain-pool!
  "Borrow and destroy all the jruby instances, optionally refilling the
  pool with fresh jrubies. Locks the pool in order to drain it, but releases
  the lock before destroying the instances and refilling the pool"
  [pool-context :- jruby-schemas/PoolContext
   pool-state :- jruby-schemas/PoolState
   refill? :- schema/Bool]
  (log/info "Draining JRuby pool.")
  (let [old-instances (collect-all-jrubies pool-state)]
    (log/info "Borrowed all JRuby instances, proceeding with cleanup.")
    (send-agent (get-modify-instance-agent pool-context)
                #(cleanup-and-refill-pool pool-context pool-state old-instances refill?))))

(schema/defn ^:always-validate
  flush-pool-for-shutdown!
  "Flush of the current JRuby pool when shutting down during a stop.
  Delivers the on-complete promise when the pool has been flushed."
  ;; Since this function is only called by the pool-agent, we know that if we
  ;; receive multiple flush requests before the first one finishes, they will
  ;; be queued up and we don't need to worry about race conditions between the
  ;; steps we perform here in the body.
  [pool-context :- jruby-schemas/PoolContext
   on-complete :- IDeref]
  (try
    (log/info "Flush request received; creating new JRuby pool.")
    (let [pool-state (jruby-internal/get-pool-state pool-context)
          pool (:pool pool-state)
          pool-size (:size pool-state)]
      (when-not (pool-initialized? pool-size pool)
        (throw (IllegalStateException. "Attempting to flush a pool that does not appear to have successfully initialized. Aborting.")))
      (drain-pool! pool-context pool-state false)
      (.insertPill pool (ShutdownPoisonPill. pool)))
    (finally
      (deliver on-complete true))))

(schema/defn ^:always-validate
  flush-and-repopulate-pool!
  "Flush of the current JRuby pool."
  [pool-context :- jruby-schemas/PoolContext]
  ;; Since this function is only called by the pool-agent, we know that if we
  ;; receive multiple flush requests before the first one finishes, they will
  ;; be queued up and we don't need to worry about race conditions between the
  ;; steps we perform here in the body.
  (log/info "Flush request received; flushing old JRuby instances.")
  (let [pool-state (jruby-internal/get-pool-state pool-context)]
    (drain-pool! pool-context pool-state true)))

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
  send-flush-and-repopulate-pool! :- jruby-schemas/JRubyPoolAgent
  "Sends requests to the agent to flush the existing pool and create a new one."
  [pool-context :- jruby-schemas/PoolContext]
  (flush-and-repopulate-pool! pool-context))

(schema/defn ^:always-validate
  send-flush-pool-for-shutdown!
  "Sends requests to the agent to flush the existing pool to prepare for shutdown."
  [pool-context :- jruby-schemas/PoolContext
   on-complete :- IDeref]
  (flush-pool-for-shutdown! pool-context on-complete))

(schema/defn ^:always-validate
  send-flush-instance! :- jruby-schemas/JRubyPoolAgent
  "Sends requests to the flush-instance agent to flush the instance and create a new one."
  [pool-context :- jruby-schemas/PoolContext
   pool :- jruby-schemas/pool-queue-type
   instance :- JRubyInstance]
  ;; We use a separate agent from the main `pool-agent` here, because there is a possibility for deadlock otherwise.
  ;; e.g.:
  ;; 1. A flush-pool request comes in, and we start using the main pool agent to flush the pool.  We do that by
  ;;    borrowing all of the instances from the pool as they are returned to it, and the agent doesn't return
  ;;    control until it has borrowed the correct number of instances.
  ;; 2. While that is happening, an individual instance reaches the 'max-borrows' value.  This instance will never
  ;;    be returned to the pool; it is handled by sending a function to an agent, which will flush the individual
  ;;    instance, create a replacement one, and return that to the pool.
  ;;
  ;; If we use the same agent for both of these operations, then step 2 will never begin until step 1 completes, and
  ;; step 1 will never complete because the `max-borrows` instance will never be returned to the pool.
  ;;
  ;; Using a separate agent for the 'max-borrows' instance flush alleviates this issue.
  (let [{:keys [config]} pool-context
        modify-instance-agent (get-modify-instance-agent pool-context)
        id (next-instance-id (:id instance) pool-context)]
    (send-agent modify-instance-agent #(flush-instance! pool-context instance pool id config))))
