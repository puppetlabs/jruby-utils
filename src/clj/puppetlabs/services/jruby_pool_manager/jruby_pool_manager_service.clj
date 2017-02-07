(ns puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core :as jruby-pool-manager-core]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]))

(trapperkeeper/defservice jruby-pool-manager-service
                          pool-manager-protocol/PoolManagerService
                          []
  (create-pool
   [this config]
   (log/info (i18n/trs "Initializing the JRuby service"))
   (jruby-pool-manager-core/create-pool config)))
