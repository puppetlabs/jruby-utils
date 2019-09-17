(ns puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas ReferencePoolContext JRubyPoolContext)))

(schema/defn ^:always-validate
  create-pool-context :- jruby-schemas/PoolContext
  "Creates a new JRuby pool context with an empty pool. Once the JRuby
  pool object has been created, it will need to be filled using `prime-pool!`."
  [config :- jruby-schemas/JRubyConfig]
  (let [shutdown-on-error-fn (get-in config [:lifecycle :shutdown-on-error])
        pool-state (if (:multithreaded config)
                     (atom (jruby-internal/create-reference-pool-from-config config))
                     (atom (jruby-internal/create-pool-from-config config)))
        pool-context-internal {:modify-instance-agent (jruby-agents/pool-agent shutdown-on-error-fn)
                               :pool-state            pool-state
                               :event-callbacks       (atom [])}]
    (if (:multithreaded config)
      (ReferencePoolContext. config pool-context-internal)
      (JRubyPoolContext. config pool-context-internal))))

(schema/defn ^:always-validate
  create-pool :- jruby-schemas/PoolContext
  [config :- jruby-schemas/JRubyConfig]
  (let [pool-context (create-pool-context config)]
     (jruby-agents/send-prime-pool! pool-context)
     pool-context))
