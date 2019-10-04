(ns puppetlabs.services.jruby-pool-manager.impl.instance-pool
  (:require [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.protocols.jruby-pool :as pool-protocol])
  (:import (puppetlabs.services.protocols.jruby_pool JRubyPool)
           (puppetlabs.services.jruby_pool_manager.jruby_schemas InstancePool)))

(extend-type InstancePool
  pool-protocol/JRubyPool
  (fill
    [this]
    (jruby-agents/send-prime-pool! this))

  (shutdown
    [this]
    (jruby-agents/flush-pool-for-shutdown! this))

  (lock
    [this])

  (unlock
    [this])

  (add-instance
    [this instance])

  (remove-instance
    [this instance])

  (borrow
    [this])

  (return
    [this instance]))
