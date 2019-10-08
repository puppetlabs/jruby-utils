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

  (return
    [this instance]))

