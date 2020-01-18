(ns puppetlabs.services.protocols.jruby-pool)

(defprotocol JRubyPool
  (fill
    [pool-context]
    "Creates all the necessary JRuby instances and adds them to the pool.")

  (shutdown
    [pool-context]
    "Shuts down the JRuby pool, inserting a poison pill to prevent further borrows and terminating
     all JRuby instances.")

  (lock
    [pool-context]
    "Blocks waiting for all currently held JRubies to be returned to the pool, preventing further
    borrows until the pool is unlocked.")

  (lock-with-timeout
    [pool-context timeout time-unit]
    "Attempts to lock the JRuby pool, timing out if the supplied interval has elapsed.")

  (unlock
    [pool-context]
    "Unlocks the JRuby pool, allowing borrows to proceed.")

  (worker-id
    [pool-context instance]
    "Returns the worker id for given instance (instance id or thread id).")

  (borrow
    [pool-context]
    "Returns a reference to a JRuby instance and a worker id (instance id or thread id).
    Will block if the pool is locked or no instances are available.")


  (borrow-with-timeout
    [pool-context timeout]
    "Returns a reference to a JRuby instance and a worker id (instance id or thread id).
    Will block if the pool is locked or no instances are available, timing out when the
    supplied number of milliseconds has elapsed.")

  (return
    [pool-context instance]
    "Releases a held reference to a JRuby instance back to the pool and returns the worker id
    (instance id or thread id) for the thing being returned. If `max-requests-per-instance`
    is configured and has been reached for this instance, this function will trigger a flush of
    the instance. Note that when using the ReferencePool, this will also cause the pool to be locked.

    If something besides a JRuby instance is passed to return (e.g. a Pill), this function is a no-op.")

  (flush-pool
    [pool-context]
    "Removes and terminates all the JRuby instances from the pool, then creates new ones and adds
    them to the pool. Note that when using the ReferencePool, this will cause the pool to be locked,
    with a timeout equal to the configured `flush-timeout`."))
