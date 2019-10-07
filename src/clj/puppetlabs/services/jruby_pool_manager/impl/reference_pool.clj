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
    [this])

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
