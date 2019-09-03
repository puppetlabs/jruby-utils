(ns puppetlabs.services.jruby-pool-manager.jruby-testutils
  (:require [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby-pool-manager.jruby-pool-manager-service :as pool-manager]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [clojure.tools.logging :as log]
            [schema.core :as schema]
            [puppetlabs.services.protocols.pool-manager :as pool-manager-protocol]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap])
  (:import (clojure.lang IFn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants

(def ruby-load-path [])
(def gem-home "./target/jruby-gem-home")

(def default-services
  [pool-manager/jruby-pool-manager-service])
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; JRuby Test util functions

(schema/defn ^:always-validate
  jruby-config :- jruby-schemas/JRubyConfig
  "Create a JRubyConfig for testing. The optional map argument `options` may
  contain a map, which, if present, will be merged into the final JRubyConfig
  map.  (This function differs from `jruby-tk-config` in that it returns a map
  that complies with the JRubyConfig schema, which differs slightly from the raw
  format that would be read from config files on disk.)"
  ([]
   (jruby-core/initialize-config
    {:ruby-load-path ruby-load-path
     :gem-home gem-home}))
  ([options]
   (jruby-core/initialize-config
    (merge {:ruby-load-path ruby-load-path
            :gem-home gem-home
            :borrow-timeout 300000}
           options))))

(schema/defn ^:always-validate
 jruby-config-for-ref-pool :- jruby-schemas/JRubyConfig
  ([]
   (jruby-config {:multithreaded true}))
  ([options]
   (jruby-config (merge options {:multithreaded true}))))

(defn drain-pool
  "Drains the JRuby pool and returns each instance in a vector."
  [pool-context size]
  (mapv (fn [_] (jruby-core/borrow-from-pool pool-context :test [])) (range size)))

(defn fill-drained-pool
  "Returns a list of JRubyInstances back to their pool."
  [instance-list]
  (doseq [instance instance-list]
    (jruby-core/return-to-pool instance :test [])))

(schema/defn ^:always-validate
  drain-and-refill :- #{schema/Int}
  "Borrow all instances from a pool and then hand them back. Return a set
  (order is not important) of the instance ids borrowed/returned"
  [pool-context :- jruby-schemas/PoolContext]
  (let [instance-count (get-in pool-context [:config :max-active-instances])
        instances (drain-pool pool-context instance-count)
        ids (into #{} (map :id instances))]
    (fill-drained-pool instances)
    ids))

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

(defn wait-for-jrubies-from-pool-context
  "Wait for all jrubies to land in the pool"
  [pool-context]
  (let [num-jrubies (-> pool-context
                        jruby-core/get-pool-state
                        :size)]
    (while (< (count (jruby-core/registered-instances pool-context))
              num-jrubies)
      (Thread/yield))))

(defn timed-await
  [agent]
  (await-for 240000 agent))

(schema/defn wait-for-predicate :- schema/Bool
  "Wait for some predicate to return true
  defaults to 20 iterations of 500 ms, ~10 seconds
  Returns true if f ever returns true, false otherwise"
  ([f :- IFn]
   (wait-for-predicate f 20 500))
  ([f :- IFn
    max-iterations :- schema/Int
    sleep-time :- schema/Int]
   (loop [count 0]
     (cond (f)
           true

           (>= count max-iterations)
           (do
             (log/debugf "Waiting for predicate failed after %s tries" max-iterations)
             false)

           :default
           (do
             (Thread/sleep sleep-time)
             (recur (inc count)))))))

(schema/defn wait-for-instances :- schema/Bool
  [pool :- jruby-schemas/pool-queue-type
   num-instances :- schema/Int]
  (wait-for-predicate
   #(= num-instances (jruby-core/free-instance-count pool))))

(schema/defn wait-for-pool-to-be-locked :- schema/Bool
  [pool :- jruby-schemas/pool-queue-type]
  (wait-for-predicate #(.isLocked pool)))

(defmacro with-pool-context
  [pool-context services config & body]
  `(tk-bootstrap/with-app-with-config
    app#
    ~services
    {}
    (let [pool-manager-service# (tk-app/get-service app# :PoolManagerService)
          ~pool-context (pool-manager-protocol/create-pool
                         pool-manager-service# ~config)]
      (try
        ~@body
        (finally
          (jruby-core/flush-pool-for-shutdown! ~pool-context))))))

(defmacro with-scripting-container
  [container config & body]
  `(let [~container (jruby-internal/create-scripting-container ~config)]
     (try
       ~@body
       (finally
         (.terminate ~container)))))
