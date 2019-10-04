(ns puppetlabs.services.protocols.jruby-pool)

(defprotocol JRubyPool
  (fill
    [this])

  (shutdown
    [this])

  (lock
    [this])

  (unlock
    [this])

  (add-instance
    [this instance])

  (remove-instance
    [this instance])

  (borrow
    [this])

  (return
    [this instance]))

