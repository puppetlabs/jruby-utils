(ns puppetlabs.services.jruby-pool-manager.impl.reference-pool
  (:require [puppetlabs.services.protocols.jruby-pool :as pool-protocol]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas ReferencePool)))

(extend-type ReferencePool
  pool-protocol/JRubyPool

  (fill
    [this]
    (let [modify-instance-agent (jruby-agents/get-modify-instance-agent this)]
      (jruby-agents/send-agent modify-instance-agent
                               #(jruby-agents/add-instance this 1))))

  (shutdown
    [this]
    (let [pool (jruby-internal/get-pool this)
          cleanup-fn (get-in this [:config :lifecycle :cleanup])
          instance (.borrowItem pool)
          _ (.releaseItem pool instance)]
      (jruby-internal/insert-shutdown-poison-pill pool)
      ;; This will block until all borrows have been returned
      (jruby-internal/cleanup-pool-instance! instance cleanup-fn)))

  (lock
    [this]
    (let [pool (jruby-internal/get-pool this)]
      (.lock pool)))

  (lock-with-timeout
    [this timeout time-unit]
    (let [pool (jruby-internal/get-pool this)]
      (.lockWithTimeout pool timeout time-unit)))

  (unlock
    [this]
    (let [pool (jruby-internal/get-pool this)]
      (.unlock pool)))

  (borrow
    [this]
    (let [pool (jruby-internal/get-pool this)]
      (.borrowItem pool)))

  (return
    [this instance]
    (let [pool (jruby-internal/get-pool this)
          borrow-count (:borrow-count this)
          max-borrows (get-in instance [:internal :max-borrows])
          modify-instance-agent (jruby-agents/get-modify-instance-agent this)]
      (.releaseItem pool instance)
      (swap! borrow-count inc)
      ;; If max-borrows is 0, never flush the instance
      (when (and (pos? max-borrows)
                 (>= @borrow-count max-borrows)
                 ;; If the pool is already locked, there's a good chance
                 ;; a flush is already in progress, and we shouldn't queue
                 ;; another one. If the pool is locked for some other reason,
                 ;; we'll flush later. `borrow-count` does not get set back to
                 ;; 0 until a flush succeeds.
                 (not (.isLocked pool)))
        (jruby-agents/send-agent modify-instance-agent #(pool-protocol/flush-pool this)))))

  (flush-pool
    [this]
    (pool-protocol/lock this)
    (try
      (let [pool (jruby-internal/get-pool this)
            borrow-count (:borrow-count this)
            cleanup-fn (get-in this [:config :lifecycle :cleanup])
            old-instance (.borrowItem pool)
            prev-id (:id old-instance)
            _ (.releaseItem pool old-instance)]
        ;; This will block waiting for all borrows to be returned
        (jruby-internal/cleanup-pool-instance! old-instance cleanup-fn)
        (jruby-agents/add-instance this (inc prev-id))
        (reset! borrow-count 0))
      (finally
        (pool-protocol/unlock this)))))


