(ns puppetlabs.services.jruby.impl.jruby-events
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-schemas :as jruby-schemas])
  (:import (clojure.lang IFn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

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

(schema/defn notify-event-listeners :- jruby-schemas/JRubyEvent
  [event-callbacks :- [IFn]
   event :- jruby-schemas/JRubyEvent]
  (doseq [f event-callbacks]
    (f event))
  event)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

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
