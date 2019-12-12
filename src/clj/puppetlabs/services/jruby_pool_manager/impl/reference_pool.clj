(ns puppetlabs.services.jruby-pool-manager.impl.reference-pool
  (:require [puppetlabs.services.protocols.jruby-pool :as pool-protocol]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]
            [slingshot.slingshot :as sling]
            [schema.core :as schema])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas ReferencePool
                                                                 JRubyInstance)
           (java.util.concurrent TimeUnit TimeoutException)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Private

(schema/defn flush-pool*
  "Flushes the pool, assuming it has already been locked by the calling function.
  Do not call this without first locking the pool, or the flush may never complete,
  since it requires that all references be returned to proceed."
  [pool-context :- jruby-schemas/PoolContext]
  (let [pool (jruby-internal/get-pool pool-context)
        borrow-count (:borrow-count pool-context)
        cleanup-fn (get-in pool-context [:config :lifecycle :cleanup])
        old-instance (.borrowItem pool)
        id (inc (:id old-instance))
        _ (.releaseItem pool old-instance)]
    ;; This will block waiting for all borrows to be returned
    (jruby-internal/cleanup-pool-instance! old-instance cleanup-fn)
    (jruby-agents/add-instance pool-context id)
    (log/info (i18n/trs "Finished creating JRuby instance with id {0}" id))
    (reset! borrow-count 0)))

(schema/defn max-borrows-exceeded :- schema/Bool
  "Returns true if max-borrows is set and the current borrow count has
  exceeded the allowed maximum."
  [current-borrows :- schema/Int
   max-borrows :- schema/Int]
  (and (pos? max-borrows)
       (>= current-borrows max-borrows)))

(schema/defn flush-if-at-max-borrows
  [pool-context :- jruby-schemas/PoolContext
   instance :- JRubyInstance]
  (let [borrow-count (:borrow-count pool-context)
        max-borrows (get-in instance [:internal :max-borrows])
        flush-timeout (jruby-internal/get-flush-timeout pool-context)]
    (try
      ;; Lock will block until all references have been returned to the pool or
      ;; until flush-timeout is reached
      (pool-protocol/lock-with-timeout pool-context flush-timeout TimeUnit/MILLISECONDS)
      (try
        ;; Now that we've successfully acquired the lock, check the borrows again
        ;; to make sure the pool wasn't flushed while we were waiting.
        (when (max-borrows-exceeded @borrow-count max-borrows)
          (flush-pool* pool-context))
        (finally
          (pool-protocol/unlock pool-context)))
      (catch TimeoutException e
        (log/warn (i18n/trs "Max borrows reached, but JRubyPool could not be flushed because lock could not be acquired. Will try again later."))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ReferencePool definition

(extend-type ReferencePool
  pool-protocol/JRubyPool

  (fill
    [pool-context]
    (let [modify-instance-agent (jruby-agents/get-modify-instance-agent pool-context)]
      (jruby-agents/send-agent modify-instance-agent
                               #(jruby-agents/add-instance pool-context 1))))

  (shutdown
    [pool-context]
    (let [pool (jruby-internal/get-pool pool-context)
          cleanup-fn (get-in pool-context [:config :lifecycle :cleanup])
          flush-timeout (jruby-internal/get-flush-timeout pool-context)]
      ;; Lock the pool so no borrows or flushes can occur while we're shutting down
      (try
        (pool-protocol/lock-with-timeout pool-context flush-timeout TimeUnit/MILLISECONDS)
        (catch TimeoutException e
          (sling/throw+ {:kind ::jruby-lock-timeout
                         :msg (i18n/trs "An attempt to lock the JRubyPool failed with a timeout")}
                        e)))
      (try
        (let [instance (.borrowItem pool)
              _ (.releaseItem pool instance)]
          ;; This will block until all borrows have been returned
          (jruby-internal/cleanup-pool-instance! instance cleanup-fn))
        ;; Insert a shutdown pill to ensure that all pending borrows and locks
        ;; are rejected with the appropriate logging
        (jruby-internal/insert-shutdown-poison-pill pool)
        (finally
          (pool-protocol/unlock pool-context)))))

  (lock
    [pool-context]
    (let [pool (jruby-internal/get-pool pool-context)]
      (.lock pool)))

  (lock-with-timeout
    [pool-context timeout time-unit]
    (let [pool (jruby-internal/get-pool pool-context)]
      (.lockWithTimeout pool timeout time-unit)))

  (unlock
    [pool-context]
    (let [pool (jruby-internal/get-pool pool-context)]
      (.unlock pool)))

  (borrow
    [pool-context]
    (jruby-internal/borrow-from-pool pool-context))

  (borrow-with-timeout
    [pool-context timeout]
    (jruby-internal/borrow-from-pool-with-timeout pool-context timeout))

  (return
    [pool-context instance]
    (when (jruby-schemas/jruby-instance? instance)
      (let [pool (jruby-internal/get-pool pool-context)
            borrow-count (:borrow-count pool-context)
            max-borrows (get-in instance [:internal :max-borrows])
            modify-instance-agent (jruby-agents/get-modify-instance-agent pool-context)]
        (.releaseItem pool instance)
        (swap! borrow-count inc)
        (when (max-borrows-exceeded @borrow-count max-borrows)
          (jruby-agents/send-agent modify-instance-agent
                                   #(flush-if-at-max-borrows pool-context instance))))))

  (flush-pool
    [pool-context]
    (let [flush-timeout (jruby-internal/get-flush-timeout pool-context)]
    ;; Lock will block until all references have been returned to the pool or
    ;; until flush-timeout is reached
      (try
        (pool-protocol/lock-with-timeout pool-context flush-timeout TimeUnit/MILLISECONDS)
        (catch TimeoutException e
          (sling/throw+ {:kind ::jruby-lock-timeout
                         :msg (i18n/trs "An attempt to lock the JRubyPool failed with a timeout")}
                        e)))
      (try
        (flush-pool* pool-context)
        (finally
          (pool-protocol/unlock pool-context))))))
