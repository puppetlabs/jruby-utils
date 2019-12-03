(ns puppetlabs.services.jruby-pool-manager.impl.reference-pool
  (:require [puppetlabs.services.protocols.jruby-pool :as pool-protocol]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas ReferencePool)))

(extend-type ReferencePool
  pool-protocol/JRubyPool

  (fill
    [pool-context]
    (jruby-agents/add-instance pool-context 1))

  (shutdown
    [pool-context]
    (let [pool (jruby-internal/get-pool pool-context)
          cleanup-fn (get-in pool-context [:config :lifecycle :cleanup])
          instance (.borrowItem pool)
          _ (.releaseItem pool instance)]
      (jruby-internal/insert-shutdown-poison-pill pool)
      ;; This will block until all borrows have been returned
      (jruby-internal/cleanup-pool-instance! instance cleanup-fn)))

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
        ;; If max-borrows is 0, never flush the instance
        (when (and (pos? max-borrows)
                   (>= @borrow-count max-borrows)
                   ;; If the pool is already locked, there's a good chance
                   ;; a flush is already in progress, and we shouldn't queue
                   ;; another one. If the pool is locked for some other reason,
                   ;; we'll flush later. `borrow-count` does not get set back to
                   ;; 0 until a flush succeeds.
                   ;; THIS IS RACY
                   (not (.isLocked pool)))
          (jruby-agents/send-agent modify-instance-agent #(pool-protocol/flush-pool pool-context))))))

  (flush-pool
    [pool-context]
    (pool-protocol/lock pool-context)
    (try
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
        (reset! borrow-count 0))
      (finally
        (pool-protocol/unlock pool-context)))))


