(ns puppetlabs.services.protocols.pool-manager)

(defprotocol PoolManagerService

  (create-pool
   [this config]
   "Create a pool and fill it with the number of JRubyInstances specified.
   Return the pool context.")

  (flush-instance!
    [this pool-context pool instance]
    "Flush the provided JRubyInstance and create a new one.")

  (flush-pool!
    [this pool-context]
    "Flush all the current JRubyInstances and repopulate the pool.")

  (flush-pool-for-shutdown!
    [this pool-context]
    "Flush all the current JRubyInstances so that the pool can be shutdown
    without any instances being active."))
