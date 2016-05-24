(ns puppetlabs.services.jruby.jruby-testutils
  (:require [puppetlabs.services.jruby.jruby-core :as jruby-core]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby.jruby-pool-manager-service :as pool-manager]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-service]
            [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants

(def ruby-load-path [])
(def gem-home "./target/jruby-gem-home")
(def compile-mode :off)

(def default-services
  [pool-manager/jruby-pool-manager-service])
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; JRuby Test util functions

(defn jruby-tk-config
  "Create a JRuby pool config with the given pool config.  Suitable for use
  in bootstrapping trapperkeeper (in other words, returns a representation of the
  config that matches what would be read directly from the config files on disk,
  as opposed to a version that has been processed and transformed to comply
  with the JRubyConfig schema)."
  [pool-config]
  {:jruby pool-config})

(schema/defn ^:always-validate
  jruby-config :- jruby-schemas/JRubyConfig
  "Create a JRubyConfig for testing. The optional map argument `options` may
  contain a map, which, if present, will be merged into the final JRubyConfig
  map.  (This function differs from `jruby-tk-config` in that it returns a map
  that complies with the JRubyConfig schema, which differs slightly from the raw
  format that would be read from config files on disk.)"
  ([]
    (jruby-core/initialize-config
      {:jruby
       {:ruby-load-path  ruby-load-path
        :gem-home        gem-home}}))
  ([options]
   (jruby-core/initialize-config
    {:jruby
     (merge {:ruby-load-path ruby-load-path
             :gem-home gem-home}
            options)})))

(def default-flush-fn
  identity)

(defn create-pool-instance
  ([]
   (create-pool-instance (jruby-config {:max-active-instances 1})))
  ([config]
   (let [pool (jruby-internal/instantiate-free-pool 1)]
     (jruby-internal/create-pool-instance! pool 1 config default-flush-fn))))

(defn drain-pool
  "Drains the JRuby pool and returns each instance in a vector."
  [pool-context size]
  (mapv (fn [_] (jruby-core/borrow-from-pool pool-context :test [])) (range size)))

(defn fill-drained-pool
  "Returns a list of JRubyInstances back to their pool."
  [instance-list]
  (doseq [instance instance-list]
    (jruby-core/return-to-pool instance :test [])))

(defn reduce-over-jrubies!
  "Utility function; takes a JRuby pool and size, and a function f from integer
  to string.  For each JRubyInstance in the pool, f will be called, passing in
  an integer offset into the jruby array (0..size), and f is expected to return
  a string containing a script to run against the jruby instance.

  Returns a vector containing the results of executing the scripts against the
  JRubyInstances."
  [pool-context size f]
  (let [jrubies (drain-pool pool-context size)
        result  (reduce
                  (fn [acc jruby-offset]
                    (let [sc (:scripting-container (nth jrubies jruby-offset))
                          script (f jruby-offset)
                          result (.runScriptlet sc script)]
                      (conj acc result)))
                  []
                  (range size))]
    (fill-drained-pool jrubies)
    result))

(defn wait-for-jrubies
  "Wait for all jrubies to land in the JRubyService's pool"
  [app]
  (let [pool-context (-> app
                         (tk-app/get-service :JRubyService)
                         tk-service/service-context
                         :pool-context)
        num-jrubies (-> pool-context
                        :pool-state
                        deref
                        :size)]
    (while (< (count (jruby-core/registered-instances pool-context))
              num-jrubies)
      (Thread/sleep 100))))

(defn wait-for-jrubies-from-pool-context
  "Wait for all jrubies to land in the pool"
  [pool-context]
  (let [num-jrubies (-> pool-context
                        :pool-state
                        deref
                        :size)]
    (while (< (count (jruby-core/registered-instances pool-context))
              num-jrubies)
      (Thread/yield))))
