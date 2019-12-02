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
    [this]
    (let [modify-instance-agent (jruby-agents/get-modify-instance-agent this)]
      (jruby-agents/send-agent modify-instance-agent
                               #(jruby-agents/prime-pool! this))))

  (shutdown
    [this]
    (jruby-agents/flush-pool-for-shutdown! this))

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
    (jruby-internal/borrow-from-pool this))

  (borrow-with-timeout
    [this timeout]
    (jruby-internal/borrow-from-pool-with-timeout this timeout))

  (return
    [this instance]
    (when (jruby-schemas/jruby-instance? instance)
      (let [new-state (swap! (jruby-internal/get-instance-state-container instance)
                             update-in [:borrow-count] inc)
            {:keys [initial-borrows max-borrows pool]} (:internal instance)
            borrow-limit (or initial-borrows max-borrows)]
        (if (and (pos? borrow-limit)
                 (>= (:borrow-count new-state) borrow-limit))
          (do
            (log/info
                (i18n/trs "Flushing JRubyInstance {0} because it has exceeded its borrow limit of {1}"
                          (:id instance)
                          borrow-limit))
            (jruby-agents/send-flush-instance! this instance))
          (.releaseItem pool instance)))))

  (flush-pool
    [this]
    (jruby-agents/flush-and-repopulate-pool! this)))

