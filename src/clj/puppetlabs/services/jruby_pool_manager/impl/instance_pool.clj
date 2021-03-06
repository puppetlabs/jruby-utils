(ns puppetlabs.services.jruby-pool-manager.impl.instance-pool
  (:require [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.protocols.jruby-pool :as pool-protocol]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas InstancePool)))

(extend-type InstancePool
  pool-protocol/JRubyPool
  (fill
    [pool-context]
    (let [modify-instance-agent (jruby-agents/get-modify-instance-agent pool-context)]
      (jruby-agents/send-agent modify-instance-agent
                               #(jruby-agents/prime-pool! pool-context))))

  (shutdown
    [pool-context]
    (jruby-agents/flush-pool-for-shutdown! pool-context))

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

  (worker-id
    [pool-context instance]
    (:id instance))

  (borrow
    [pool-context]
    (let [instance (jruby-internal/borrow-from-pool pool-context)]
      [instance (pool-protocol/worker-id pool-context instance)]))

  (borrow-with-timeout
    [pool-context timeout]
    (let [instance (jruby-internal/borrow-from-pool-with-timeout pool-context timeout)]
      [instance (pool-protocol/worker-id pool-context instance)]))

  (return
    [pool-context instance]
    (when (jruby-schemas/jruby-instance? instance)
      (let [new-state (swap! (jruby-internal/get-instance-state-container instance)
                             #(update-in % [:borrow-count] inc))
            {:keys [initial-borrows max-borrows pool]} (:internal instance)
            borrow-limit (or initial-borrows max-borrows)
            worker-id (pool-protocol/worker-id pool-context instance)]
        (if (and (pos? borrow-limit)
                 (>= (:borrow-count new-state) borrow-limit))
          (do
            (log/info
                (i18n/trs "Flushing JRubyInstance {0} because it has exceeded its borrow limit of {1}"
                          worker-id
                          borrow-limit))
            (jruby-agents/send-flush-instance! pool-context instance))
          (.releaseItem pool instance))
        ;; Return the worker-id, to be used in metrics and event logging
        worker-id)))

  (flush-pool
    [pool-context]
    (jruby-agents/flush-and-repopulate-pool! pool-context)))

