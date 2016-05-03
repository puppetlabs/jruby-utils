(ns puppetlabs.services.jruby.jruby-core
  (:require [clojure.tools.logging :as log]
            [schema.core :as schema]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.jruby-internal :as jruby-internal]
            [puppetlabs.services.jruby.jruby-agents :as jruby-agents]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import (puppetlabs.services.jruby.jruby_schemas JRubyInstance)
           (clojure.lang IFn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def default-jruby-compile-mode
  "Default value for JRuby's 'CompileMode' setting."
  :off)

(def default-borrow-timeout
  "Default timeout when borrowing instances from the JRuby pool in
   milliseconds. Current value is 1200000ms, or 20 minutes."
  1200000)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

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

(defn verify-config-found!
  [config]
  (if (or (not (map? config))
          (empty? config))
    (throw (IllegalArgumentException. (str "No configuration data found.  Perhaps "
                                           "you did not specify the --config option?")))))

(schema/defn create-requested-event :- jruby-schemas/JRubyRequestedEvent
  [reason :- jruby-schemas/JRubyEventReason]
  {:type :instance-requested
   :reason reason})

(schema/defn create-borrowed-event :- jruby-schemas/JRubyBorrowedEvent
  [requested-event :- jruby-schemas/JRubyRequestedEvent
   instance :- jruby-schemas/JRubyBorrowResult]
  {:type :instance-borrowed
   :reason (:reason requested-event)
   :requested-event requested-event
   :instance instance})

(schema/defn create-returned-event :- jruby-schemas/JRubyReturnedEvent
  [instance :- jruby-schemas/JRubyInstanceOrPill
   reason :- jruby-schemas/JRubyEventReason]
  {:type :instance-returned
   :reason reason
   :instance instance})

(schema/defn create-lock-requested-event :- jruby-schemas/JRubyLockRequestedEvent
  [reason :- jruby-schemas/JRubyEventReason]
  {:type :lock-requested
   :reason reason})

(schema/defn create-lock-acquired-event :- jruby-schemas/JRubyLockAcquiredEvent
  [reason :- jruby-schemas/JRubyEventReason]
  {:type :lock-acquired
   :reason reason})

(schema/defn create-lock-released-event :- jruby-schemas/JRubyLockReleasedEvent
  [reason :- jruby-schemas/JRubyEventReason]
  {:type :lock-released
   :reason reason})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Support functions for event notification

(schema/defn notify-event-listeners :- jruby-schemas/JRubyEvent
  [event-callbacks :- [IFn]
   event :- jruby-schemas/JRubyEvent]
  (doseq [f event-callbacks]
    (f event))
  event)

(schema/defn instance-requested :- jruby-schemas/JRubyRequestedEvent
  [event-callbacks :- [IFn]
   reason :- jruby-schemas/JRubyEventReason]
  (notify-event-listeners event-callbacks (create-requested-event reason)))

(schema/defn instance-borrowed :- jruby-schemas/JRubyBorrowedEvent
  [event-callbacks :- [IFn]
   requested-event :- jruby-schemas/JRubyRequestedEvent
   instance :- jruby-schemas/JRubyBorrowResult]
  (notify-event-listeners event-callbacks (create-borrowed-event requested-event instance)))

(schema/defn instance-returned :- jruby-schemas/JRubyReturnedEvent
  [event-callbacks :- [IFn]
   instance :- jruby-schemas/JRubyInstanceOrPill
   reason :- jruby-schemas/JRubyEventReason]
  (notify-event-listeners event-callbacks (create-returned-event instance reason)))

(schema/defn lock-requested :- jruby-schemas/JRubyLockRequestedEvent
  [event-callbacks :- [IFn]
   reason :- jruby-schemas/JRubyEventReason]
  (notify-event-listeners event-callbacks (create-lock-requested-event reason)))

(schema/defn lock-acquired :- jruby-schemas/JRubyLockAcquiredEvent
  [event-callbacks :- [IFn]
   reason :- jruby-schemas/JRubyEventReason]
  (notify-event-listeners event-callbacks (create-lock-acquired-event reason)))

(schema/defn lock-released :- jruby-schemas/JRubyLockReleasedEvent
  [event-callbacks :- [IFn]
   reason :- jruby-schemas/JRubyEventReason]
  (notify-event-listeners event-callbacks (create-lock-released-event reason)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  initialize-config :- jruby-schemas/JRubyConfig
  [config :- {schema/Keyword schema/Any}]
  (-> (get-in config [:jruby])
      (dissoc :lifecycle-fns)
      (update-in [:compile-mode] #(keyword (or % default-jruby-compile-mode)))
      (update-in [:borrow-timeout] #(or % default-borrow-timeout))
      (update-in [:max-active-instances] #(or % (default-pool-size (ks/num-cpus))))
      (update-in [:max-requests-per-instance] #(or % 0))))

(schema/defn ^:always-validate
  create-pool-context :- jruby-schemas/PoolContext
  "Creates a new JRuby pool context with an empty pool. Once the JRuby
  pool object has been created, it will need to be filled using `prime-pool!`."
  [config :- jruby-schemas/JRubyConfig
   lifecycle-fns :- jruby-schemas/LifecycleFns]
  (let [agent-shutdown-fn (:shutdown-on-error lifecycle-fns)]
    {:config config
     :pool-agent (jruby-agents/pool-agent agent-shutdown-fn)
     ;; For an explanation of why we need a separate agent for the `flush-instance`,
     ;; see the comments in puppetlabs.services.jruby.jruby-agents/send-flush-instance
     :flush-instance-agent (jruby-agents/pool-agent agent-shutdown-fn)
     :pool-state (atom (jruby-internal/create-pool-from-config config))
     :lifecycle lifecycle-fns}))

(schema/defn ^:always-validate
  free-instance-count
  "Returns the number of JRubyInstances available in the pool."
  [pool :- jruby-schemas/pool-queue-type]
  {:post [(>= % 0)]}
  (.size pool))

(schema/defn ^:always-validate
  instance-state :- jruby-schemas/JRubyInstanceState
  "Get the state metadata for a JRubyInstance."
  [jruby-instance :- (schema/pred jruby-schemas/jruby-instance?)]
  @(:state jruby-instance))

(schema/defn ^:always-validate
  borrow-from-pool :- jruby-schemas/JRubyInstanceOrPill
  "Borrows a JRuby interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool-context :- jruby-schemas/PoolContext
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (let [requested-event (instance-requested event-callbacks reason)
        instance (jruby-internal/borrow-from-pool pool-context)]
    (instance-borrowed event-callbacks requested-event instance)
    instance))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- jruby-schemas/JRubyBorrowResult
  "Borrows a JRuby interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [pool-context :- jruby-schemas/PoolContext
   timeout :- schema/Int
   reason :- schema/Any
   event-callbacks :- [IFn]]
  {:pre  [(>= timeout 0)]}
  (let [requested-event (instance-requested event-callbacks reason)
        instance (jruby-internal/borrow-from-pool-with-timeout
                   pool-context
                   timeout)]
    (instance-borrowed event-callbacks requested-event instance)
    instance))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool."
  [instance :- jruby-schemas/JRubyInstanceOrPill
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (instance-returned event-callbacks instance reason)
  (jruby-internal/return-to-pool instance))

(schema/defn ^:always-validate
  lock-pool
  "Locks the JRuby pool for exclusive access."
  [pool :- jruby-schemas/pool-queue-type
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (log/debug "Acquiring lock on JRubyPool...")
  (lock-requested event-callbacks reason)
  (.lock pool)
  (lock-acquired event-callbacks reason)
  (log/debug "Lock acquired"))

(schema/defn ^:always-validate
  unlock-pool
  "Unlocks the JRuby pool, restoring concurernt access."
  [pool :- jruby-schemas/pool-queue-type
   reason :- schema/Any
   event-callbacks :- [IFn]]
  (.unlock pool)
  (lock-released event-callbacks reason)
  (log/debug "Lock on JRubyPool released"))

(schema/defn ^:always-validate cli-ruby! :- jruby-schemas/JRubyMainStatus
  "Run JRuby as though native `ruby` were invoked with args on the CLI"
  [config :- {schema/Keyword schema/Any}
   args :- [schema/Str]]
  (let [main (jruby-internal/new-main (initialize-config config))
        argv (into-array String (concat ["-rjar-dependencies"] args))]
    (.run main argv)))

(schema/defn ^:always-validate cli-run! :- (schema/maybe jruby-schemas/JRubyMainStatus)
  "Run a JRuby CLI command, e.g. gem, irb, etc..."
  [config :- {schema/Keyword schema/Any}
   command :- schema/Str
   args :- [schema/Str]]
  (let [bin-dir "META-INF/jruby.home/bin"
        load-path (format "%s/%s" bin-dir command)
        url (io/resource load-path (.getClassLoader org.jruby.Main))]
    (if url
      (cli-ruby! config
        (concat ["-e" (format "load '%s'" url) "--"] args))
      (log/errorf "command %s could not be found in %s" command bin-dir))))
