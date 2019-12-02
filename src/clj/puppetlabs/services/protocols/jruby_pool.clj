(ns puppetlabs.services.protocols.jruby-pool)

(defprotocol JRubyPool
  (fill
    [this])

  (shutdown
    [this])

  (lock
    [this])

  (lock-with-timeout
    [this timeout time-unit])

  (unlock
    [this])

  (borrow
    [this])

  (borrow-with-timeout
    [this timeout])

  (return
    [this instance])

  (flush-pool
    [this]))
