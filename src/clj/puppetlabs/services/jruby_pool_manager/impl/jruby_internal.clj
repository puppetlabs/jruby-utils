(ns puppetlabs.services.jruby-pool-manager.impl.jruby-internal
  (:require [clj-time.core :as time-core]
            [clj-time.format :as time-format]
            [clojure.java.io :as io]
            [clojure.java.jmx :as jmx]
            [clojure.string :refer [upper-case]]
            [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [schema.core :as schema])
  (:import (clojure.lang IFn)
           (com.puppetlabs.jruby_utils.pool JRubyPool ReferencePool)
           (com.puppetlabs.jruby_utils.jruby InternalScriptingContainer
                                             ScriptingContainer)
           (java.io File)
           (java.util.concurrent TimeUnit)
           (org.jruby CompatVersion Main Ruby RubyInstanceConfig RubyInstanceConfig$CompileMode RubyInstanceConfig$ProfilingMode)
           (org.jruby.embed LocalContextScope)
           (org.jruby.runtime.profile.builtin ProfileOutput)
           (org.jruby.util KCode)
           (puppetlabs.services.jruby_pool_manager.jruby_schemas JRubyInstance PoisonPill
                                                                 ShutdownPoisonPill)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(schema/defn ^:always-validate initialize-gem-path :- {schema/Keyword schema/Any}
  [{:keys [gem-path gem-home] :as jruby-config} :- {schema/Keyword schema/Any}]
  (if gem-path
    jruby-config
    (assoc jruby-config :gem-path nil)))

(defn instantiate-free-pool
  "Instantiate a new queue object to use as the pool of free JRuby's."
  [size]
  {:post [(instance? jruby-schemas/pool-queue-type %)]}
  (JRubyPool. size))

(defn instantiate-reference-pool
  "Instantiate a new pool object to be used as the JRuby reference pool.
  In this model, a single JRuby instance will be created that can be simultaneously
  borrowed up to the specified number of times."
  [maxBorrowCount]
  {:post [(instance? jruby-schemas/pool-queue-type %)]}
  (ReferencePool. maxBorrowCount))

(schema/defn ^:always-validate get-compile-mode :- RubyInstanceConfig$CompileMode
  [config-compile-mode :- jruby-schemas/SupportedJRubyCompileModes]
  (case config-compile-mode
    :jit RubyInstanceConfig$CompileMode/JIT
    :force RubyInstanceConfig$CompileMode/FORCE
    :off RubyInstanceConfig$CompileMode/OFF))

(schema/defn ^:always-validate get-profiling-mode :- RubyInstanceConfig$ProfilingMode
  [config-profiling-mode :- jruby-schemas/SupportedJRubyProfilingModes]
  (RubyInstanceConfig$ProfilingMode/valueOf (upper-case (name config-profiling-mode))))

(schema/defn ^:always-validate setup-profiling
  "Takes a jruby and sets profiling mode and profiler output, appending the
  current time to the filename for uniqueness and notifying the user via log
  message of the profile file name."
  [jruby :- jruby-schemas/ConfigurableJRuby
   profiler-output-file :- schema/Str
   profiling-mode :- schema/Keyword]
  (when (and profiler-output-file (not= :off profiling-mode))
    (let [current-time-string (time-format/unparse (time-format/formatters :basic-date-time-no-ms) (time-core/now))
          real-profiler-output-file (io/as-file (str profiler-output-file "-" (.hashCode jruby) "-" current-time-string))]
      (doto jruby
        (.setProfileOutput (ProfileOutput. ^File real-profiler-output-file))
        (.setProfilingMode (get-profiling-mode profiling-mode)))
      (log/info
       (i18n/trs "Writing jruby profiling output to ''{0}''" real-profiler-output-file)))))

(schema/defn ^:always-validate set-config-encoding :- RubyInstanceConfig
  "Sets the K code, source encoding, and external encoding of the JRuby config to
  the supplied encoding."
  [kcode :- KCode
   jruby :- RubyInstanceConfig]
  (let [encoding-string (str (.getEncoding kcode))]
    (doto jruby
      (.setKCode kcode)
      (.setSourceEncoding encoding-string)
      (.setExternalEncoding encoding-string))))

(schema/defn ^:always-validate set-ruby-encoding :- jruby-schemas/ConfigurableJRuby
  [kcode :- KCode
   jruby :- jruby-schemas/ConfigurableJRuby]
  (if (instance? RubyInstanceConfig jruby)
    (set-config-encoding kcode jruby)
    (set-config-encoding kcode (.getRubyInstanceConfig (.getProvider jruby)))))

(schema/defn ^:always-validate init-jruby :- jruby-schemas/ConfigurableJRuby
  "Applies configuration to a JRuby... thing.  See comments in `ConfigurableJRuby`
  schema for more details."
  [jruby :- jruby-schemas/ConfigurableJRuby
   config :- jruby-schemas/JRubyConfig]
  (let [{:keys [ruby-load-path compile-mode lifecycle profiling-mode profiler-output-file]} config
        initialize-scripting-container-fn (:initialize-scripting-container lifecycle)]
    (doto jruby
      (.setLoadPaths ruby-load-path)
      (.setCompileMode (get-compile-mode compile-mode)))
    (set-ruby-encoding KCode/UTF8 jruby)
    (setup-profiling jruby profiler-output-file profiling-mode)
    (initialize-scripting-container-fn jruby config)))

(schema/defn ^:always-validate empty-scripting-container :- ScriptingContainer
  "Creates a clean instance of a JRuby `ScriptingContainer` with no code loaded."
  [config :- jruby-schemas/JRubyConfig]
  (-> (InternalScriptingContainer. LocalContextScope/SINGLETHREAD)
      (init-jruby config)))

(schema/defn ^:always-validate create-scripting-container :- ScriptingContainer
  "Creates an instance of `org.jruby.embed.ScriptingContainer`."
  [config :- jruby-schemas/JRubyConfig]
  ;; for information on other legal values for `LocalContextScope`, there
  ;; is some documentation available in the JRuby source code; e.g.:
  ;; https://github.com/jruby/jruby/blob/1.7.11/core/src/main/java/org/jruby/embed/LocalContextScope.java#L58
  ;; I'm convinced that this is the safest and most reasonable value
  ;; to use here, but we could potentially explore optimizations in the future.
  (doto (empty-scripting-container config)
    ;; As of JRuby 1.7.20 (and the associated 'jruby-openssl' it pulls in),
    ;; we need to explicitly require 'jar-dependencies' so that it is used
    ;; to manage jar loading.  We do this so that we can instruct
    ;; 'jar-dependencies' to not actually load any jars.  See the environment
    ;; variable configuration in 'init-jruby-config' for more
    ;; information.
    (.runScriptlet "require 'jar-dependencies'")))

(schema/defn borrow-with-timeout-fn :- jruby-schemas/JRubyInternalBorrowResult
  [timeout :- schema/Int
   pool :- jruby-schemas/pool-queue-type]
  (.borrowItemWithTimeout pool timeout TimeUnit/MILLISECONDS))

(schema/defn insert-shutdown-poison-pill
  [pool :- jruby-schemas/pool-queue-type]
  (.insertPill pool (ShutdownPoisonPill. pool)))

(schema/defn insert-poison-pill
  [pool :- jruby-schemas/pool-queue-type
   error :- Throwable]
  (.insertPill pool (PoisonPill. error)))

(schema/defn ^:always-validate
  get-jruby-runtime :- Ruby
  "Get the org.jruby.Ruby instance associated with member of the pool."
  [{:keys [scripting-container]} :- JRubyInstance]
  (-> scripting-container
      .getProvider
      .getRuntime))

(schema/defn ^:always-validate
  management-enabled? :- schema/Bool
  [instance :- JRubyInstance]
  (-> (get-jruby-runtime instance)
      .getInstanceConfig
      .isManagementEnabled))

(def
  JRubyMBeanName
  "Enumeration of available JMX MBeans for a JRubyInstance."
  (schema/enum "Caches"
               "Config"
               "JITCompiler"
               "ParserStats"
               "Runtime"))

(schema/defn ^:always-validate
  jmx-bean-name :- schema/Str
  "Get the fully-qualified name of a JMX MBean attached to a JRubyInstance."
  [instance :- JRubyInstance
   service-name :- JRubyMBeanName]
  (-> (get-jruby-runtime instance)
      .getBeanManager
      .base
      (str "service=" service-name)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  create-reference-pool-from-config :- jruby-schemas/PoolState
  "Create a new pool of handles to a JRuby instance, based on
  the config input."
  [{maxBorrows :max-active-instances} :- jruby-schemas/JRubyConfig]
  {:pool (instantiate-reference-pool maxBorrows)
   :size 1
   :flush-pending false})

(schema/defn ^:always-validate
  create-pool-from-config :- jruby-schemas/PoolState
  "Create a new PoolState based on the config input."
  [{size :max-active-instances} :- jruby-schemas/JRubyConfig]
  {:pool (instantiate-free-pool size)
   :size size
   :flush-pending false})

(schema/defn ^:always-validate
  cleanup-pool-instance!
  "Cleans up and cleanly terminates a JRubyInstance and removes it from the pool."
  [{:keys [scripting-container] :as instance} :- JRubyInstance
   cleanup-fn :- IFn]
  (let [pool (get-in instance [:internal :pool])]
    (.unregister pool instance)
    (cleanup-fn instance)
    (.terminate scripting-container)
    (log/info (i18n/trs "Cleaned up old JRubyInstance with id {0}."
                         (:id instance)))))

(schema/defn ^:always-validate
  initial-borrows-value :- (schema/maybe schema/Int)
  "Determines how many borrows before instance of given id should be flushed
  in order to best splay it. Returns nil if not applicable (either not
  `initial-jruby?` or there are more instances than max-borrows)."
  [id :- schema/Int
   total-instances :- schema/Int
   total-borrows :- schema/Int
   initial-jruby? :- schema/Bool]
  (when initial-jruby?
    (let [which-step (inc (mod id total-instances))
          step-size (quot total-borrows total-instances)]
      ;; If total-instances is larger than total-borrows then step-size will
      ;; be 0. For example, if a user has configured their pool with 4 JRuby
      ;; instances but a max-requests-per-instance of 1. Users shouldn't need
      ;; splaying if they are never re-using JRuby instances, or if they are
      ;; re-using them fewer times than the number of JRubies in the pool.
      (when-not (= 0 step-size)
        (* step-size which-step)))))

(schema/defn ^:always-validate
  create-pool-instance! :- JRubyInstance
  "Creates a new JRubyInstance and adds it to the pool."
  ([pool :- jruby-schemas/pool-queue-type
    id :- schema/Int
    config :- jruby-schemas/JRubyConfig
    flush-instance-fn :- IFn]
   (create-pool-instance! pool id config flush-instance-fn false))
  ([pool :- jruby-schemas/pool-queue-type
    id :- schema/Int
    config :- jruby-schemas/JRubyConfig
    flush-instance-fn :- IFn
    initial-jruby? :- schema/Bool]
   (let [{:keys [ruby-load-path lifecycle
                  max-active-instances max-borrows-per-instance]} config
          initialize-pool-instance-fn (:initialize-pool-instance lifecycle)
          initial-borrows (initial-borrows-value id
                                                 max-active-instances
                                                 max-borrows-per-instance
                                                 initial-jruby?)]
      (when-not ruby-load-path
        (throw (Exception.
                 (i18n/trs "JRuby service missing config value 'ruby-load-path'"))))
      (log/info (i18n/trs "Creating JRubyInstance with id {0}." id))
      (let [scripting-container (create-scripting-container
                                  config)]
        (let [instance (jruby-schemas/map->JRubyInstance
                         {:scripting-container scripting-container
                          :id id
                          :internal {:pool pool
                                     :max-borrows max-borrows-per-instance
                                     :initial-borrows initial-borrows
                                     :flush-instance-fn flush-instance-fn
                                     :state (atom {:borrow-count 0})}})
              modified-instance (initialize-pool-instance-fn instance)]
          (.register pool modified-instance)
          modified-instance)))))

(schema/defn ^:always-validate
  get-pool-state-container :- jruby-schemas/PoolStateContainer
  "Gets the PoolStateContainer from the pool context."
  [context :- jruby-schemas/PoolContext]
  (get-in context [:internal :pool-state]))

(schema/defn ^:always-validate
  get-pool-state :- jruby-schemas/PoolState
  "Gets the PoolState from the pool context."
  [context :- jruby-schemas/PoolContext]
  @(get-pool-state-container context))

(schema/defn ^:always-validate
  get-pool :- jruby-schemas/pool-queue-type
  "Gets the JRuby pool object from the pool context."
  [context :- jruby-schemas/PoolContext]
  (:pool (get-pool-state context)))

(schema/defn ^:always-validate
  get-pool-size :- schema/Int
  "Gets the number of allowed simultaneous borrows of
   the JRuby pool from the pool context. For the JRubyPool,
   this is equivalent to the number of active instances.
   For the ReferencePool, it is the number of references to
   the instance that we are allowed to hand out at once."
  [context :- jruby-schemas/PoolContext]
  (get-in context [:config :max-active-instances]))

(schema/defn ^:always-validate
  get-instance-count :- schema/Int
  "Gets the number of JRuby instances in the pool. For the
  JRubyPool, this is equivalent to the number of allowed
  simultaneous borrows. For the ReferencePool, it is always 1."
  [context :- jruby-schemas/PoolContext]
  (:size (get-pool-state context)))

(schema/defn ^:always-validate
  get-flush-timeout :- schema/Int
  "Gets the size of the JRuby pool from the pool context."
  [context :- jruby-schemas/PoolContext]
  (get-in context [:config :flush-timeout]))

(schema/defn ^:always-validate
  get-instance-state-container :- jruby-schemas/JRubyInstanceStateContainer
  "Gets the InstanceStateContainer (atom) from the instance."
  [instance :- JRubyInstance]
  (get-in instance [:internal :state]))

(schema/defn borrow-without-timeout-fn :- jruby-schemas/JRubyInternalBorrowResult
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
                    (i18n/tru "Unable to borrow JRubyInstance from pool")
                    (:err instance))))

          (jruby-schemas/jruby-instance? instance)
          instance

          (jruby-schemas/shutdown-poison-pill? instance)
          instance

          (nil? instance)
          instance

          :else
          (throw (IllegalStateException.
                  (i18n/tru "Borrowed unrecognized object from pool!: {0}"
                            instance))))))

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
  "Return a borrowed pool instance to its free pool.
  Also check if the borrow count has exceeded, and flush it if needed.
  If the instance is not a JRubyInstance, it must be a poison pill, in
  which case this function is a noop"
  [instance :- jruby-schemas/JRubyInstanceOrPill]
  (when (jruby-schemas/jruby-instance? instance)
    (let [new-state (swap! (get-instance-state-container instance)
                           update-in [:borrow-count] inc)
          {:keys [initial-borrows max-borrows flush-instance-fn pool]} (:internal instance)
          borrow-limit (or initial-borrows max-borrows)]
      (if (and (pos? borrow-limit)
               (>= (:borrow-count new-state) borrow-limit))
        (do
          (log/info
           (i18n/trs "Flushing JRubyInstance {0} because it has exceeded its borrow limit of ({1})"
                     (:id instance)
                     borrow-limit))
          (flush-instance-fn instance))
        (.releaseItem pool instance)))))

(schema/defn ^:always-validate
  get-instance-thread-dump
  [instance :- JRubyInstance]
  (if (management-enabled? instance)
    (try
      {:thread-dump (jmx/invoke (jmx-bean-name instance "Runtime")
                                :threadDump)}
      (catch Exception e
        (let [system_error (i18n/trs "Exception raised while generating thread dump")
              user_error (i18n/tru "Exception raised while generating thread dump")]
          (log/error e system_error)
          {:error (str user_error ": " (.toString e))})))
    {:error (i18n/tru "JRuby management interface not enabled. Add ''-Djruby.management.enabled=true'' to JAVA_ARGS to enable thread dumps.")}))

(schema/defn ^:always-validate new-main :- jruby-schemas/JRubyMain
  "Return a new JRubyMain instance which should only be used for CLI purposes,
  e.g. for the ruby, gem, and irb subcommands.  Internal core services should
  use `create-scripting-container` instead of `new-main`."
  [config :- jruby-schemas/JRubyConfig]
  (let [jruby-config (init-jruby
                      (RubyInstanceConfig.)
                      config)]
    (Main. jruby-config)))
