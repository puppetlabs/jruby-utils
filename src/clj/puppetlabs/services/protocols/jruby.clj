(ns puppetlabs.services.protocols.jruby)

(defprotocol JRubyService
  "Describes the JRuby provider service which pools JRubyInstances."

  (borrow-instance
    [this reason]
    "Borrows an instance from the JRuby interpreter pool. If there are no
    interpreters left in the pool then the operation blocks until there is one
    available. A timeout (integer measured in milliseconds) can be configured
    which will either return an interpreter if one is available within the
    timeout length, or will return nil after the timeout expires if no
    interpreters are available. This timeout defaults to 1200000 milliseconds.

    `reason` is an identifier (usually a map) describing the reason for borrowing the
    JRubyInstance.  It may be used for metrics and logging purposes.")

  (return-instance
    [this jruby-instance reason]
    "Returns the JRuby interpreter back to the pool.

    `reason` is an identifier (usually a map) describing the reason for borrowing the
    JRubyInstance.  It may be used for metrics and logging purposes, so for
    best results it should be set to the same value as it was set during the
    `borrow-instance` call.")

  (free-instance-count
    [this]
    "The number of free JRubyInstances left in the pool.")

  (flush-jruby-pool!
    [this]
    "Flush all the current JRubyInstances and repopulate the pool.")

  (register-event-handler
    [this callback]
    "Register a callback function to receive notifications when JRuby service events occur.
    The callback fn should accept a single arg, which will conform to the JRubyEvent schema."))
