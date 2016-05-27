(ns puppetlabs.services.protocols.pool-manager)

(defprotocol PoolManagerService

  (create-pool
   [this config]
   "Create a pool and fill it with the number of JRubyInstances specified.
   Return the pool context."))
