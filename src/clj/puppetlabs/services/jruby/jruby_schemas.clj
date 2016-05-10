(ns puppetlabs.services.jruby.jruby-schemas
  (:require [schema.core :as schema])
  (:import (clojure.lang Atom Agent IFn PersistentArrayMap PersistentHashMap)
           (com.puppetlabs.jruby_utils.pool LockablePool)
           (org.jruby Main Main$Status RubyInstanceConfig)
           (com.puppetlabs.jruby_utils.jruby ScriptingContainer)))

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

(defrecord RetryPoisonPill
  ;; A sentinel object to put into an old pool when we swap in a new pool.
  ;; This can be used to build `borrow` functionality that will detect the
  ;; case where we're trying to borrow from an old pool, so that we can retry
  ;; with the new pool.
  [pool])

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

(def JRubyConfig
  "Schema defining the config map for the JRuby pooling functions.

  The keys should have the following values:

    * :ruby-load-path - a vector of file paths, containing the locations of ruby source code.

    * :gem-home - The location that JRuby gems are stored

    * :compile-mode - The value to use for JRuby's CompileMode setting.  Legal
        values are `:jit`, `:force`, and `:off`.  Defaults to `:off`.

    * :max-active-instances - The maximum number of JRubyInstances that
        will be pooled."
  {:ruby-load-path [schema/Str]
   :gem-home schema/Str
   :compile-mode SupportedJRubyCompileModes
   :borrow-timeout schema/Int
   :max-active-instances schema/Int
   :max-requests-per-instance schema/Int})

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

(def LifecycleFns
  {:initialize IFn
   :shutdown IFn
   :shutdown-on-error IFn
   :initialize-env-variables IFn})

(def PoolContext
  "The data structure that stores all JRuby pools and the original configuration."
  {:config                JRubyConfig
   :pool-agent            JRubyPoolAgent
   :flush-instance-agent  JRubyPoolAgent
   :pool-state            PoolStateContainer
   :lifecycle LifecycleFns})

(def JRubyInstanceState
  "State metadata for an individual JRubyInstance"
  {:borrow-count schema/Int})

(def JRubyInstanceStateContainer
  "An atom containing the current state of a given JRubyInstance."
  (schema/pred #(and (instance? Atom %)
                     (nil? (schema/check JRubyInstanceState @%)))
               'JRubyInstanceState))

(def JRubyInstanceSchema
  {:pool pool-queue-type
   :id schema/Int
   :max-requests schema/Int
   :flush-instance-fn IFn
   :state JRubyInstanceStateContainer
   :scripting-container ScriptingContainer
   schema/Keyword schema/Any})

(schema/defrecord JRubyInstance
  [pool :- pool-queue-type
   id :- schema/Int
   max-requests :- schema/Int
   flush-instance-fn :- IFn
   state :- JRubyInstanceStateContainer
   scripting-container :- ScriptingContainer])

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

(defn retry-poison-pill?
  [x]
  (instance? RetryPoisonPill x))

(defn shutdown-poison-pill?
  [x]
  (instance? ShutdownPoisonPill x))

(def JRubyInstanceOrPill
  (schema/conditional
   jruby-instance? (schema/pred jruby-instance?)
   retry-poison-pill? (schema/pred retry-poison-pill?)
   shutdown-poison-pill? (schema/pred shutdown-poison-pill?)))

(def JRubyBorrowResult
  (schema/pred (some-fn nil?
                        retry-poison-pill?
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
