(ns puppetlabs.services.jruby.impl.jruby-pool-manager-core
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby.impl.jruby-internal :as jruby-internal]))

(schema/defn ^:always-validate
  create-pool-context :- jruby-schemas/PoolContext
  "Creates a new JRuby pool context with an empty pool. Once the JRuby
  pool object has been created, it will need to be filled using `prime-pool!`."
  [config :- jruby-schemas/JRubyConfig]
  (let [agent-shutdown-fn (get-in config [:lifecycle :shutdown-on-error])]
    {:config config
     :internal {:pool-agent (jruby-agents/pool-agent agent-shutdown-fn)
                ;; For an explanation of why we need a separate agent for the `flush-instance`,
                ;; see the comments in puppetlabs.services.jruby.jruby-agents/send-flush-instance
                :flush-instance-agent (jruby-agents/pool-agent agent-shutdown-fn)
                :pool-state (atom (jruby-internal/create-pool-from-config config))
                :event-callbacks (atom [])}}))

(schema/defn ^:always-validate
  create-pool :- jruby-schemas/PoolContext
  [config :- jruby-schemas/JRubyConfig]
  (let [pool-context (create-pool-context config)]
     (jruby-agents/send-prime-pool! pool-context)
     pool-context))
