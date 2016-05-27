(ns puppetlabs.services.jruby.jruby-pool-manager-service
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol]
            [puppetlabs.services.jruby.jruby-core :as core]
            [puppetlabs.services.jruby.jruby-agents :as jruby-agents]
            [clojure.tools.logging :as log]))

(trapperkeeper/defservice jruby-pool-manager-service
                          pool-manager-protocol/PoolManagerService
                          []
  (create-pool
   [this config]
   (log/info "Initializing the JRuby service")
   (let [pool-context (core/create-pool-context config)]
     (jruby-agents/send-prime-pool! pool-context)
     pool-context)))
