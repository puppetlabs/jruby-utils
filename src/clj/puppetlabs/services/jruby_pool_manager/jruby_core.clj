(ns puppetlabs.services.jruby-pool-manager.jruby-core
  (:require [clojure.tools.logging :as log]
            [schema.core :as schema]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.ring-middleware.utils :as ringutils]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-agents :as jruby-agents]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-events :as jruby-events]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :as sling]
            [puppetlabs.i18n.core :as i18n]
            [me.raynes.fs :as fs])
  (:import (puppetlabs.services.jruby_pool_manager.jruby_schemas JRubyInstance)
           (clojure.lang IFn)
           (java.util.concurrent TimeUnit)
           (org.jruby CompatVersion)
           (org.jruby.util.cli OutputStrings)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def default-jruby-compile-mode
  "Default for JRuby's CompileMode setting. Defaults to JIT for Jruby
   9k."
    :jit)

(def default-borrow-timeout
  "Default timeout when borrowing instances from the JRuby pool in
   milliseconds. Current value is 1200000ms, or 20 minutes."
  1200000)

(def default-flush-timeout
  "Default timeout when flushing the JRuby pool in milliseconds.
  Current value is 1200000ms, or 20 minutes."
  1200000)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Functions

(defn default-pool-size
  "Calculate the default size of the JRuby pool, based on the number of cpus."
  [num-cpus]
  (->> (- num-cpus 1)
       (max 1)
       (min 4)))

(schema/defn ^:always-validate
  get-pool-state :- jruby-schemas/PoolState
  "Gets the PoolState from the pool context."
  [context :- jruby-schemas/PoolContext]
  (jruby-internal/get-pool-state context))

(schema/defn ^:always-validate
  get-pool :- jruby-schemas/pool-queue-type
  "Gets the JRuby pool object from the pool context."
  [context :- jruby-schemas/PoolContext]
  (jruby-internal/get-pool context))

(schema/defn ^:always-validate
  registered-instances :- [JRubyInstance]
  [context :- jruby-schemas/PoolContext]
  (-> (get-pool context)
      .getRegisteredElements
      .iterator
      iterator-seq
      vec))

(schema/defn ^:always-validate free-instance-count
  "Returns the number of JRubyInstances available in the pool."
  [pool :- jruby-schemas/pool-queue-type]
  {:post [(>= % 0)]}
  (.size pool))

(schema/defn ^:always-validate
  get-instance-state :- jruby-schemas/JRubyInstanceState
  "Get the state metadata for a JRubyInstance."
  [jruby-instance :- JRubyInstance]
  @(jruby-internal/get-instance-state-container jruby-instance))

(schema/defn get-event-callbacks :- [IFn]
  "Gets the vector of event callbacks from the pool context."
  [pool-context :- jruby-schemas/PoolContext]
  @(get-in pool-context [:internal :event-callbacks]))

(schema/defn get-system-env :- jruby-schemas/EnvPersistentMap
  "Same as System/getenv, but returns a clojure persistent map instead of a
  Java unmodifiable map."
  []
  (into {} (System/getenv)))

(schema/defn ^:always-validate add-gem-path
  [env :- {schema/Str schema/Str}
   config :- jruby-schemas/JRubyConfig]
  (if-let [gem-path (:gem-path config)]
    (assoc env "GEM_PATH" gem-path)
    env))

(def proxy-vars-allowed-list
  "A list of proxy-related variables that are allowed to be passed the environment"
  ["HTTP_PROXY" "http_proxy" "HTTPS_PROXY" "https_proxy" "NO_PROXY" "no_proxy"])

(def env-vars-allowed-list
  "A list of environment variables that are allowed to be passed through from the environment."
  (concat proxy-vars-allowed-list ["HOME" "PATH"]))

(schema/defn ^:always-validate managed-environment :- jruby-schemas/EnvMap
  "The environment variables that should be passed to the JRuby interpreters.

  We don't want them to read any ruby environment variables, like $RUBY_LIB or
  anything like that, so pass it an empty environment map - except - most things
  needs HOME and PATH to work, so leave those, along with GEM_HOME and GEM_PATH,
  which are necessary for extensions that depend on gems.

  We need to set the JARS..REQUIRE variables in order to instruct JRuby's
  'jar-dependencies' to not try to load any dependent jars.  This is being
  done specifically to avoid JRuby trying to load its own version of Bouncy
  Castle, which may not the same as the one that 'puppetlabs/ssl-utils'
  uses. JARS_NO_REQUIRE was the legacy way to turn off jar loading but is
  being phased out in favor of JARS_REQUIRE.  As of JRuby 1.7.20, only
  JARS_NO_REQUIRE is honored.  Setting both of those here for forward
  compatibility.

  We also merge an environment-vars map with the config to allow for configured
  environment variables to be visible to the Ruby code. This map is by default
  set to {} if the user does not specify it in the configuration file."
  [env :- jruby-schemas/EnvMap
   config :- jruby-schemas/JRubyConfig]
  (let [clean-env (select-keys env env-vars-allowed-list)]
    (merge (-> (assoc clean-env
                 "GEM_HOME" (:gem-home config)
                 "JARS_NO_REQUIRE" "true"
                 "JARS_REQUIRE" "false")
               (add-gem-path config))
           (clojure.walk/stringify-keys (:environment-vars config)))))

(schema/defn ^:always-validate default-initialize-scripting-container :- jruby-schemas/ConfigurableJRuby
  "Default lifecycle fn for initializing the settings on the scripting
  container. Currently it just sets the environment variables."
  [scripting-container :- jruby-schemas/ConfigurableJRuby
   config :- jruby-schemas/JRubyConfig]
  (.setEnvironment scripting-container (managed-environment (get-system-env) config))
  scripting-container)

(schema/defn ^:always-validate
  initialize-lifecycle-fns :- jruby-schemas/LifecycleFns
  [config :- (schema/maybe {(schema/optional-key :initialize-pool-instance) IFn
                            (schema/optional-key :cleanup) IFn
                            (schema/optional-key :shutdown-on-error) IFn
                            (schema/optional-key :initialize-scripting-container) IFn})]
  (-> config
      (update-in [:initialize-pool-instance] #(or % identity))
      (update-in [:cleanup] #(or % identity))
      (update-in [:shutdown-on-error] #(or % (fn [f] (f))))
      (update-in [:initialize-scripting-container]
                 #(or % default-initialize-scripting-container))))

(schema/defn ^:always-validate
  initialize-config :- jruby-schemas/JRubyConfig
  "Initialize keys with default settings if they are not given a value.
  The config is validated after these defaults are set."
  [config :- {schema/Keyword schema/Any}]
  (-> config
      (update-in [:compile-mode] #(keyword (or % default-jruby-compile-mode)))
      (update-in [:profiling-mode] #(keyword (or % :off)))
      (update-in [:profiler-output-file] #(or % (str (fs/absolute (fs/temp-name "jruby-profiler")))))
      (update-in [:borrow-timeout] #(or % default-borrow-timeout))
      (update-in [:flush-timeout] #(or % default-flush-timeout))
      (update-in [:max-active-instances] #(or % (default-pool-size (ks/num-cpus))))
      (update-in [:max-borrows-per-instance] #(or % 0))
      (update-in [:splay-instance-flush] #(if (nil? %) true %))
      (update-in [:environment-vars] #(or % {}))
      (update-in [:lifecycle] initialize-lifecycle-fns)
      (update-in [:multithreaded] #(if (nil? %) false %))
      jruby-internal/initialize-gem-path))

(schema/defn register-event-handler
  "Register the callback function by adding it to the event callbacks atom on the pool context."
  [pool-context :- jruby-schemas/PoolContext
   callback-fn :- IFn]
  (swap! (get-in pool-context [:internal :event-callbacks]) conj callback-fn))

(schema/defn ^:always-validate
  get-jruby-thread-dump
  "Get thread dumps from JRuby instances in the pool."
  [pool-context :- jruby-schemas/PoolContext]
  (reduce (fn [result instance]
            (assoc result (:id instance)
                          (jruby-internal/get-instance-thread-dump instance)))
          {}
          (registered-instances pool-context)))

(schema/defn ^:always-validate
  borrow-from-pool :- jruby-schemas/JRubyInstanceOrPill
  "Borrows a JRuby interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool-context :- jruby-schemas/PoolContext
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (let [requested-event (jruby-events/instance-requested event-callbacks reason)
        instance (jruby-internal/borrow-from-pool pool-context)]
    (jruby-events/instance-borrowed event-callbacks requested-event instance)
    instance))

;; TODO: consider adding a second arity that allows for passing in a
;; borrow-timeout, rather than relying on what is in the config.
(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- jruby-schemas/JRubyBorrowResult
  "Borrows a JRuby interpreter from the pool, like borrow-from-pool but a
  blocking timeout is taken from the config in the context. If an instance is
  available then it will be immediately returned to the caller, if not then
  this function will block waiting for an instance to be free for the number
  of milliseconds given in timeout. If the timeout runs out then nil will be
  returned, indicating that there were no instances available."
  [pool-context :- jruby-schemas/PoolContext
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (let [timeout (get-in pool-context [:config :borrow-timeout])
        requested-event (jruby-events/instance-requested event-callbacks reason)
        instance (jruby-internal/borrow-from-pool-with-timeout
                   pool-context
                   timeout)]
    (jruby-events/instance-borrowed event-callbacks requested-event instance)
    instance))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool."
  [instance :- jruby-schemas/JRubyInstanceOrPill
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (jruby-events/instance-returned event-callbacks instance reason)
  (jruby-internal/return-to-pool instance))

(schema/defn ^:always-validate
  flush-pool!
  "Flush all the current JRubyInstances and repopulate the pool."
  [pool-context]
  (jruby-agents/flush-and-repopulate-pool! pool-context))

(schema/defn ^:always-validate
  flush-pool-for-shutdown!
  "Flush all the current JRubyInstances so that the pool can be shutdown
  without any instances being active."
  [pool-context]
  (jruby-agents/flush-pool-for-shutdown! pool-context))

(schema/defn ^:always-validate
  lock-pool
  "Locks the JRuby pool for exclusive access."
  [pool :- jruby-schemas/pool-queue-type
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (log/debug (i18n/trs "Acquiring lock on JRubyPool..."))
  (jruby-events/lock-requested event-callbacks reason)
  (.lock pool)
  (jruby-events/lock-acquired event-callbacks reason)
  (log/debug (i18n/trs "Lock acquired")))

(schema/defn ^:always-validate
  lock-pool-with-timeout
  "Locks the JRuby pool for exclusive access using a timeout in milliseconds.
  If the timeout is exceeded, a TimeoutException will be thrown and
  the pool will remain unlocked"
  [pool :- jruby-schemas/pool-queue-type
   timeout-ms :- schema/Int
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (log/debug (i18n/trs "Acquiring lock on JRubyPool..."))
  (jruby-events/lock-requested event-callbacks reason)
  (.lockWithTimeout pool timeout-ms TimeUnit/MILLISECONDS)
  (jruby-events/lock-acquired event-callbacks reason)
  (log/debug (i18n/trs "Lock acquired")))

(schema/defn ^:always-validate
  unlock-pool
  "Unlocks the JRuby pool, restoring concurernt access."
  [pool :- jruby-schemas/pool-queue-type
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (.unlock pool)
  (jruby-events/lock-released event-callbacks reason)
  (log/debug (i18n/trs "Lock on JRubyPool released")))

(schema/defn ^:always-validate cli-ruby! :- jruby-schemas/JRubyMainStatus
  "Run JRuby as though native `ruby` were invoked with args on the CLI"
  [config :- jruby-schemas/JRubyConfig
   args :- [schema/Str]]
  (let [main (jruby-internal/new-main config)
        argv (into-array String (concat ["-rjar-dependencies"] args))]
    (.run main argv)))

(schema/defn ^:always-validate cli-run! :- (schema/maybe jruby-schemas/JRubyMainStatus)
  "Run a JRuby CLI command, e.g. gem, irb, etc..."
  [config :- jruby-schemas/JRubyConfig
   command :- schema/Str
   args :- [schema/Str]]
  (let [bin-dir "META-INF/jruby.home/bin"
        load-path (format "%s/%s" bin-dir command)
        url (io/resource load-path (.getClassLoader org.jruby.Main))]
    (if url
      (cli-ruby! config
        (concat ["-e" (format "load '%s'" url) "--"] args))
      (log/error (i18n/trs "command {0} could not be found in {1}"
                            command bin-dir)))))

(defmacro with-jruby-instance
  "Encapsulates the behavior of borrowing and returning a JRubyInstance.
  Example usage:

  (let [pool-manager-service (tk-app/get-service app :PoolManagerService)
        pool-context (pool-manager/create-pool pool-manager-service config)]
    (with-jruby-instance
      jruby-instance
      pool-context
      reason

      (do-something-with-a-jruby-instance jruby-instance)))

  Will throw an IllegalStateException if borrowing a JRubyInstance times out."
  [jruby-instance pool-context reason & body]
  `(let [event-callbacks# (get-event-callbacks ~pool-context)]
     (loop [pool-instance# (borrow-from-pool-with-timeout ~pool-context ~reason event-callbacks#)]
       (if (nil? pool-instance#)
         (sling/throw+
          {:kind ::jruby-timeout
           :msg (i18n/tru "Attempt to borrow a JRubyInstance from the pool timed out.")}))
       (when (jruby-schemas/shutdown-poison-pill? pool-instance#)
         (return-to-pool pool-instance# ~reason event-callbacks#)
         (ringutils/throw-service-unavailable!
          (format "%s %s"
                  (i18n/tru "Attempted to borrow a JRubyInstance from the pool during a shutdown.")
                  (i18n/tru "Please try again."))))
       (let [~jruby-instance pool-instance#]
         (try
           ~@body
           (finally
             (return-to-pool pool-instance# ~reason event-callbacks#)))))))

(defmacro with-lock
  "Acquires a lock on the pool, executes the body, and releases the lock."
  [pool-context reason & body]
  `(let [pool# (get-pool ~pool-context)
         event-callbacks# (get-event-callbacks ~pool-context)]
     (lock-pool pool# ~reason event-callbacks#)
     (try
       ~@body
       (finally
         (unlock-pool pool# ~reason event-callbacks#)))))

(defmacro with-lock-with-timeout
  "Acquires a lock on the pool with a timeout in milliseconds,
  executes the body, and releases the lock. If the timeout is exceeded,
  a TimeoutException will be thrown"
  [pool-context timeout-ms reason & body]
  `(let [pool# (get-pool ~pool-context)
         event-callbacks# (get-event-callbacks ~pool-context)]
     (lock-pool-with-timeout pool# ~timeout-ms ~reason event-callbacks#)
     (try
       ~@body
       (finally
         (unlock-pool pool# ~reason event-callbacks#)))))

(def jruby-version-info
  "Default version info string for jruby"
  (OutputStrings/getVersionString))
