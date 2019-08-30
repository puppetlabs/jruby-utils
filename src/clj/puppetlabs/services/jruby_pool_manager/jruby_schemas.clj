(ns puppetlabs.services.jruby-pool-manager.jruby-schemas
  (:require [schema.core :as schema])
  (:import (clojure.lang Atom Agent IFn PersistentArrayMap PersistentHashMap)
           (com.puppetlabs.jruby_utils.pool LockablePool)
           (org.jruby Main Main$Status RubyInstanceConfig)
           (com.puppetlabs.jruby_utils.jruby ScriptingContainer)
           (org.jruby.runtime Constants)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def pool-queue-type
  "The Java datastructure type used to store JRubyInstances which are
  free to be borrowed."
  LockablePool)

(defrecord PoisonPill
  ;; A sentinel object to put into a pool in case an error occurs while we're trying
  ;; to populate it.  This can be used by the `borrow` functions to detect error
  ;; state in a thread-safe manner.
  [err])

(defrecord ShutdownPoisonPill
  ;; A sentinel object to put into a pool when we are shutting down.
  ;; This can be used to build `borrow` functionality that will detect the
  ;; case where we're trying to borrow during a shutdown, so we can return
  ;; a sane error code.
  [pool])

(def supported-jruby-compile-modes
  #{:jit :force :off})

(def SupportedJRubyCompileModes
  "Schema defining the supported values for the JRuby CompileMode setting."
  (apply schema/enum supported-jruby-compile-modes))

(def SupportedJRubyProfilingModes
  "Schema defining the supported values for the JRuby ProfilingMode setting."
  (schema/enum :api :flat :graph :html :json :off :service))

(def LifecycleFns
  {:initialize-pool-instance IFn
   :cleanup IFn
   :shutdown-on-error IFn
   :initialize-scripting-container IFn})

(def JRubyConfig
  "Schema defining the config map for the JRuby pooling functions.

  The keys should have the following values:

    * :ruby-load-path - a vector of file paths, containing the locations of ruby source code.

    * :gem-home - The location that JRuby gems will be installed

    * :gem-path - The full path where JRuby should look for gems

    * :compile-mode - The value to use for JRuby's CompileMode setting.  Legal
        values are `:jit`, `:force`, and `:off`.  Defaults to `:off`.

    * :max-active-instances - The maximum number of JRubyInstances that
        will be pooled.

    * :splay-instance-flush - Whether or not to splay flushing of instances

    * :environment-vars - A map of environment variables and their values to be
        passed through to the JRuby scripting container and visible to any Ruby code.

    * :profiling-mode - The value to use for JRuby's ProfilerMode setting. Legal
        values are `:api`, `:flat`, `:graph`, `:html`, `:json`, `:off`, and
        `:service`. Defaults to `:off`.

    * :profiler-output-file - A target file to direct profiler output to. If
        not set, defaults to a random file relative to the working directory
        of the service."
  {:ruby-load-path [schema/Str]
   :gem-home schema/Str
   :gem-path (schema/maybe schema/Str)
   :compile-mode SupportedJRubyCompileModes
   :borrow-timeout schema/Int
   :flush-timeout schema/Int
   :max-active-instances schema/Int
   :max-borrows-per-instance schema/Int
   :splay-instance-flush schema/Bool
   :lifecycle LifecycleFns
   :environment-vars {schema/Keyword schema/Str}
   :profiling-mode SupportedJRubyProfilingModes
   :profiler-output-file schema/Str
   :multithreaded schema/Bool})

(def JRubyPoolAgent
  "An agent configured for use in managing JRuby pools"
  (schema/both Agent
               (schema/pred
                 (fn [a]
                   (let [state @a]
                     (and
                       (map? state)
                       (ifn? (:shutdown-on-error state))))))))

(def PoolState
  "A map that describes all attributes of a particular JRuby pool."
  {:pool         pool-queue-type
   :size schema/Int})

(def PoolStateContainer
  "An atom containing the current state of all of the JRuby pool."
  (schema/pred #(and (instance? Atom %)
                     (nil? (schema/check PoolState @%)))
               'PoolStateContainer))

(def PoolContext
  "The data structure that stores all JRuby pools and the original configuration."
  {:config JRubyConfig
   :internal {:modify-instance-agent JRubyPoolAgent
              :pool-state PoolStateContainer
              :event-callbacks Atom}})

(def JRubyInstanceState
  "State metadata for an individual JRubyInstance"
  {:borrow-count schema/Int})

(def JRubyInstanceStateContainer
  "An atom containing the current state of a given JRubyInstance."
  (schema/pred #(and (instance? Atom %)
                     (nil? (schema/check JRubyInstanceState @%)))
               'JRubyInstanceState))

(def JRubyPuppetInstanceInternal
  {:flush-instance-fn IFn
   :pool pool-queue-type
   :initial-borrows (schema/maybe schema/Int)
   :max-borrows schema/Int
   :state JRubyInstanceStateContainer})

(schema/defrecord JRubyInstance
  [internal :- JRubyPuppetInstanceInternal
   id :- schema/Int
   scripting-container :- ScriptingContainer]
  {schema/Keyword schema/Any})

(defn jruby-instance?
  [x]
  (instance? JRubyInstance x))

(defn jruby-main-instance?
  [x]
  (instance? Main x))

(defn jruby-main-status-instance?
  [x]
  (instance? Main$Status x))

(defn jruby-scripting-container?
  [x]
  (instance? ScriptingContainer x))

(defn jruby-instance-config?
  [x]
  (instance? RubyInstanceConfig x))

(defn poison-pill?
  [x]
  (instance? PoisonPill x))

(defn shutdown-poison-pill?
  [x]
  (instance? ShutdownPoisonPill x))

(def JRubyInstanceOrPill
  (schema/conditional
   jruby-instance? (schema/pred jruby-instance?)
   shutdown-poison-pill? (schema/pred shutdown-poison-pill?)))

(def JRubyInternalBorrowResult
  ;; Result of calling `.borrowItem` on the pool
  (schema/pred (some-fn nil?
                        poison-pill?
                        shutdown-poison-pill?
                        jruby-instance?)))

(def JRubyBorrowResult
  ;; Result of doing some error handling after calling `.borrowItem` on the
  ;; pool. Specifically, if the item borrow was a poison pill, an error is
  ;; thrown, so `poison-pill?` is not part of this schema.
  (schema/pred (some-fn nil?
                        shutdown-poison-pill?
                        jruby-instance?)))

(def JRubyMain
  (schema/pred jruby-main-instance?))

(def JRubyMainStatus
  (schema/pred jruby-main-status-instance?))

(def ConfigurableJRuby
  ;; This schema is a bit weird.  We have some common configuration that we need
  ;; to apply to two different kinds of JRuby objects: `ScriptingContainer` and
  ;; `JRubyInstanceConfig`.  These classes both have the same signatures for
  ;; all of the setter methods that we need to call on them (see
  ;; `jruby-internal/init-jruby-config`), but unfortunately the JRuby API doesn't
  ;; define an interface for those methods.  So, rather than duplicating the logic
  ;; in multiple places in the code, we use this (gross) schema to enforce that
  ;; an object must be an instance of one of those two types.
  (schema/conditional
   jruby-scripting-container? (schema/pred jruby-scripting-container?)
   jruby-instance-config? (schema/pred jruby-instance-config?)))

(def EnvMap
  "System Environment variables have strings for the keys and values of a map"
  {schema/Str schema/Str})

(def EnvPersistentMap
  "Schema for a clojure persistent map for the system environment"
  (schema/both EnvMap
    (schema/either PersistentArrayMap PersistentHashMap)))

(defn event-type-requested?
  [e]
  (= :instance-requested (:type e)))

(defn event-type-borrowed?
  [e]
  (= :instance-borrowed (:type e)))

(defn event-type-returned?
  [e]
  (= :instance-returned (:type e)))

(defn event-type-lock-requested?
  [e]
  (= :lock-requested (:type e)))

(defn event-type-lock-acquired?
  [e]
  (= :lock-acquired (:type e)))

(defn event-type-lock-released?
  [e]
  (= :lock-released (:type e)))

(def JRubyEventReason
  schema/Any)

(def JRubyRequestedEvent
  {:type (schema/eq :instance-requested)
   :reason JRubyEventReason})

(def JRubyBorrowedEvent
  {:type (schema/eq :instance-borrowed)
   :reason JRubyEventReason
   :requested-event JRubyRequestedEvent
   :instance JRubyBorrowResult})

(def JRubyReturnedEvent
  {:type (schema/eq :instance-returned)
   :reason JRubyEventReason
   :instance JRubyInstanceOrPill})

(def JRubyLockRequestedEvent
  {:type (schema/eq :lock-requested)
   :reason JRubyEventReason})

(def JRubyLockAcquiredEvent
  {:type (schema/eq :lock-acquired)
   :reason JRubyEventReason})

(def JRubyLockReleasedEvent
  {:type (schema/eq :lock-released)
   :reason JRubyEventReason})

(def JRubyEvent
  (schema/conditional
    event-type-requested? JRubyRequestedEvent
    event-type-borrowed? JRubyBorrowedEvent
    event-type-returned? JRubyReturnedEvent
    event-type-lock-requested? JRubyLockRequestedEvent
    event-type-lock-acquired? JRubyLockAcquiredEvent
    event-type-lock-released? JRubyLockReleasedEvent))
