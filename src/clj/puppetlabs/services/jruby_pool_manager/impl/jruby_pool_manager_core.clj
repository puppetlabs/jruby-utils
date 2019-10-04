(ns puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.protocols.jruby-pool :as pool-protocol]
            [puppetlabs.services.jruby-pool-manager.impl.reference-pool]
            [puppetlabs.services.jruby-pool-manager.impl.instance-pool]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas ReferencePool InstancePool)))

(schema/defn ^:always-validate
  create-pool-context :- jruby-schemas/PoolContext
  "Creates a new JRuby pool context with an empty pool. Once the JRuby
  pool object has been created, it will need to be filled using `prime-pool!`."
  [config :- jruby-schemas/JRubyConfig]
  (let [shutdown-on-error-fn (get-in config [:lifecycle :shutdown-on-error])
        internal {:modify-instance-agent (jruby-agents/pool-agent shutdown-on-error-fn)
                  :pool-state            (atom (jruby-internal/create-pool-from-config config))
                  :event-callbacks       (atom [])}]
    (if (:multithreaded config)
      (ReferencePool. config internal)
      (InstancePool. config internal))))

(schema/defn ^:always-validate
  create-pool :- jruby-schemas/PoolContext
  [config :- jruby-schemas/JRubyConfig]
  (let [pool-context (create-pool-context config)]
     (pool-protocol/fill pool-context)
     pool-context))
