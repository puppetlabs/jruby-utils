(ns puppetlabs.services.jruby.jruby-internal
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks])
  (:import (com.puppetlabs.jruby_utils.pool JRubyPool)
           (puppetlabs.services.jruby.jruby_schemas JRubyInstance PoisonPill ShutdownPoisonPill)
           (java.util HashMap)
           (org.jruby CompatVersion Main RubyInstanceConfig RubyInstanceConfig$CompileMode)
           (org.jruby.embed LocalContextScope)
           (java.util.concurrent TimeUnit)
           (clojure.lang IFn)
           (com.puppetlabs.jruby_utils.jruby ScriptingContainer)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Definitions

(def compat-version
  "The JRuby compatibility version to use for all ruby components, e.g. the
  master service and CLI tools."
  (CompatVersion/RUBY1_9))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def JRubyInternalBorrowResult
  (schema/pred (some-fn nil?
                        jruby-schemas/poison-pill?
                        jruby-schemas/retry-poison-pill?
                        jruby-schemas/shutdown-poison-pill?
                        jruby-schemas/jruby-instance?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn get-system-env :- jruby-schemas/EnvPersistentMap
  "Same as System/getenv, but returns a clojure persistent map instead of a
  Java unmodifiable map."
  []
  (into {} (System/getenv)))

(defn instantiate-free-pool
  "Instantiate a new queue object to use as the pool of free JRuby's."
  [size]
  {:post [(instance? jruby-schemas/pool-queue-type %)]}
  (JRubyPool. size))

(schema/defn ^:always-validate managed-environment :- jruby-schemas/EnvMap
  "The environment variables that should be passed to the JRuby interpreters.

  We don't want them to read any ruby environment variables, like $RUBY_LIB or
  anything like that, so pass it an empty environment map - except - Puppet
  needs HOME and PATH for facter resolution, so leave those, along with GEM_HOME
  which is necessary for third party extensions that depend on gems.

  We need to set the JARS..REQUIRE variables in order to instruct JRuby's
  'jar-dependencies' to not try to load any dependent jars.  This is being
  done specifically to avoid JRuby trying to load its own version of Bouncy
  Castle, which may not the same as the one that 'puppetlabs/ssl-utils'
  uses. JARS_NO_REQUIRE was the legacy way to turn off jar loading but is
  being phased out in favor of JARS_REQUIRE.  As of JRuby 1.7.20, only
  JARS_NO_REQUIRE is honored.  Setting both of those here for forward
  compatibility."
  [env :- jruby-schemas/EnvMap
   gem-home :- schema/Str]
  (let [whitelist ["HOME" "PATH"]
        clean-env (select-keys env whitelist)]
    (assoc clean-env
      "GEM_HOME" gem-home
      "JARS_NO_REQUIRE" "true"
      "JARS_REQUIRE" "false")))

(schema/defn ^:always-validate get-compile-mode :- RubyInstanceConfig$CompileMode
  [config-compile-mode :- jruby-schemas/SupportedJRubyCompileModes]
  (case config-compile-mode
    :jit RubyInstanceConfig$CompileMode/JIT
    :force RubyInstanceConfig$CompileMode/FORCE
    :off RubyInstanceConfig$CompileMode/OFF))

(schema/defn ^:always-validate init-jruby-config :- jruby-schemas/ConfigurableJRuby
  "Applies configuration to a JRuby... thing.  See comments in `ConfigurableJRuby`
  schema for more details."
  [jruby-config :- jruby-schemas/ConfigurableJRuby
   ruby-load-path :- [schema/Str]
   gem-home :- schema/Str
   compile-mode :- jruby-schemas/SupportedJRubyCompileModes]
  (doto jruby-config
    (.setLoadPaths ruby-load-path)
    (.setCompatVersion compat-version)
    (.setCompileMode (get-compile-mode compile-mode))
    (.setEnvironment (managed-environment (get-system-env) gem-home))))

(schema/defn ^:always-validate empty-scripting-container :- ScriptingContainer
  "Creates a clean instance of a JRuby `ScriptingContainer` with no code loaded."
  [ruby-load-path :- [schema/Str]
   gem-home :- schema/Str
   compile-mode :- jruby-schemas/SupportedJRubyCompileModes]
  (-> (ScriptingContainer. LocalContextScope/SINGLETHREAD)
      (init-jruby-config ruby-load-path gem-home compile-mode)))

(schema/defn ^:always-validate create-scripting-container :- ScriptingContainer
  "Creates an instance of `org.jruby.embed.ScriptingContainer`."
  [ruby-load-path :- [schema/Str]
   gem-home :- schema/Str
   compile-mode :- jruby-schemas/SupportedJRubyCompileModes]
  ;; for information on other legal values for `LocalContextScope`, there
  ;; is some documentation available in the JRuby source code; e.g.:
  ;; https://github.com/jruby/jruby/blob/1.7.11/core/src/main/java/org/jruby/embed/LocalContextScope.java#L58
  ;; I'm convinced that this is the safest and most reasonable value
  ;; to use here, but we could potentially explore optimizations in the future.
  (doto (empty-scripting-container ruby-load-path gem-home compile-mode)
    ;; As of JRuby 1.7.20 (and the associated 'jruby-openssl' it pulls in),
    ;; we need to explicitly require 'jar-dependencies' so that it is used
    ;; to manage jar loading.  We do this so that we can instruct
    ;; 'jar-dependencies' to not actually load any jars.  See the environment
    ;; variable configuration in 'init-jruby-config' for more
    ;; information.
    (.runScriptlet "require 'jar-dependencies'")))

(schema/defn borrow-with-timeout-fn :- JRubyInternalBorrowResult
  [timeout :- schema/Int
   pool :- jruby-schemas/pool-queue-type]
  (.borrowItemWithTimeout pool timeout TimeUnit/MILLISECONDS))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  create-pool-from-config :- jruby-schemas/PoolState
  "Create a new PoolState based on the config input."
  [{size :max-active-instances} :- jruby-schemas/JRubyConfig]
  {:pool (instantiate-free-pool size)
   :size size})

(schema/defn ^:always-validate
  cleanup-pool-instance!
  "Cleans up and cleanly terminates a JRubyInstance and removes it from the pool."
  [{:keys [scripting-container pool] :as instance} :- JRubyInstance]
  (.unregister pool instance)
  ;; TODO: need to add support for a callback hook, so that consumers like
  ;; puppet-server can do their own cleanup.
  ;(.terminate jruby-puppet)
  (.terminate scripting-container)
  (log/infof "Cleaned up old JRubyInstance with id %s." (:id instance)))

(schema/defn ^:always-validate
  create-pool-instance! :- JRubyInstance
  "Creates a new JRubyInstance and adds it to the pool."
  [pool :- jruby-schemas/pool-queue-type
   id :- schema/Int
   config :- jruby-schemas/JRubyConfig
   flush-instance-fn :- IFn]
  (let [{:keys [ruby-load-path gem-home compile-mode]} config]
    (when-not ruby-load-path
      (throw (Exception.
               "JRuby service missing config value 'ruby-load-path'")))
    (log/infof "Creating JRubyInstance with id %s." id)
    (let [scripting-container (create-scripting-container
                               ruby-load-path
                               gem-home
                               compile-mode)]
      (let [instance (jruby-schemas/map->JRubyInstance
                      {:pool pool
                       :id id
                       :max-requests (:max-requests-per-instance config)
                       :flush-instance-fn flush-instance-fn
                       :state (atom {:borrow-count 0})
                       :scripting-container scripting-container})]
        (.register pool instance)
        instance))))

(schema/defn ^:always-validate
  get-pool-state :- jruby-schemas/PoolState
  "Gets the PoolState from the pool context."
  [context :- jruby-schemas/PoolContext]
  @(:pool-state context))

(schema/defn ^:always-validate
  get-pool :- jruby-schemas/pool-queue-type
  "Gets the JRuby pool object from the pool context."
  [context :- jruby-schemas/PoolContext]
  (:pool (get-pool-state context)))

(schema/defn ^:always-validate
  get-pool-size :- schema/Int
  "Gets the size of the JRuby pool from the pool context."
  [context :- jruby-schemas/PoolContext]
  (get-in context [:config :max-active-instances]))

(schema/defn borrow-without-timeout-fn :- JRubyInternalBorrowResult
  [pool :- jruby-schemas/pool-queue-type]
  (.borrowItem pool))

(schema/defn borrow-from-pool!* :- jruby-schemas/JRubyBorrowResult
  "Given a borrow function and a pool, attempts to borrow a JRubyInstance from a pool.
  If successful, updates the state information and returns the JRubyInstance.
  Returns nil if the borrow function returns nil; throws an exception if
  the borrow function's return value indicates an error condition."
  [borrow-fn :- (schema/pred ifn?)
   pool :- jruby-schemas/pool-queue-type]
  (let [instance (borrow-fn pool)]
    (cond (instance? PoisonPill instance)
          (do
            (.releaseItem pool instance)
            (throw (IllegalStateException.
                     "Unable to borrow JRubyInstance from pool"
                     (:err instance))))

          (jruby-schemas/jruby-instance? instance)
          instance

          ((some-fn nil? jruby-schemas/retry-poison-pill?) instance)
          instance

          (instance? ShutdownPoisonPill instance)
          instance

          :else
          (throw (IllegalStateException.
                   (str "Borrowed unrecognized object from pool!: " instance))))))

(schema/defn ^:always-validate
  borrow-from-pool :- jruby-schemas/JRubyInstanceOrPill
  "Borrows a JRuby interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool-context :- jruby-schemas/PoolContext]
  (borrow-from-pool!* borrow-without-timeout-fn
                      (get-pool pool-context)))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- jruby-schemas/JRubyBorrowResult
  "Borrows a JRuby interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [pool-context :- jruby-schemas/PoolContext
   timeout :- schema/Int]
  {:pre  [(>= timeout 0)]}
  (borrow-from-pool!* (partial borrow-with-timeout-fn timeout)
                      (get-pool pool-context)))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool."
  [instance :- jruby-schemas/JRubyInstanceOrPill]
  (if (jruby-schemas/jruby-instance? instance)
    (let [new-state (swap! (:state instance)
                           update-in [:borrow-count] inc)
          {:keys [max-requests flush-instance-fn pool]} instance]
      (if (and (pos? max-requests)
               (>= (:borrow-count new-state) max-requests))
        (do
          (log/infof (str "Flushing JRubyInstance %s because it has exceeded the "
                          "maximum number of requests (%s)")
                     (:id instance)
                     max-requests)
          (try
            (flush-instance-fn pool instance)
            (finally
              (.releaseItem pool instance false))))
        (.releaseItem pool instance)))
    ;; if we get here, it was from a borrow and we got a Retry, so we just
    ;; return it to the pool.
    (.releaseItem (:pool instance) instance)))

(schema/defn ^:always-validate new-main :- jruby-schemas/JRubyMain
  "Return a new JRubyMain instance which should only be used for CLI purposes,
  e.g. for the ruby, gem, and irb subcommands.  Internal core services should
  use `create-scripting-container` instead of `new-main`."
  [config :- jruby-schemas/JRubyConfig]
  (let [{:keys [ruby-load-path gem-home compile-mode]} config
        jruby-config (init-jruby-config
                      (RubyInstanceConfig.)
                      ruby-load-path
                      gem-home
                      compile-mode)]
    (Main. jruby-config)))
