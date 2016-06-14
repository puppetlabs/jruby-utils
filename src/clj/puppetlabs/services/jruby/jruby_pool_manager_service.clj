(ns puppetlabs.services.jruby.jruby-pool-manager-service
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol]
            [puppetlabs.services.jruby.impl.jruby-pool-manager-core :as jruby-pool-manager-core]
            [clojure.tools.logging :as log]))

(trapperkeeper/defservice jruby-pool-manager-service
                          pool-manager-protocol/PoolManagerService
                          []
  (create-pool
   [this config]
   (log/info "Initializing the JRuby service")
   (jruby-pool-manager-core/create-pool config)))
