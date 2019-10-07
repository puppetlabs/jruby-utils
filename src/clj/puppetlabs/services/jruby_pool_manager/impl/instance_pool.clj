(ns puppetlabs.services.jruby-pool-manager.impl.instance-pool
  (:require [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.protocols.jruby-pool :as pool-protocol]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal])
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

  (add-instance
    [this instance])

  (remove-instance
    [this instance])

  (borrow
    [this])

  (return
    [this instance]))
