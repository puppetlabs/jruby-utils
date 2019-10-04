(ns puppetlabs.services.jruby-pool-manager.impl.reference-pool
  (:require [puppetlabs.services.protocols.jruby-pool :as pool-protocol])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas ReferencePool)))

(extend-type ReferencePool
  pool-protocol/JRubyPool
  (fill
    [this])

  (shutdown
    [this])

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
