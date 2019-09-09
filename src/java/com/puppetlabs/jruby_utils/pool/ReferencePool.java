package com.puppetlabs.jruby_utils.pool;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of LockablePool for managing a pool of JRubyInstances.
 *
 * @param <E> the type of element that can be added to the pool.
 */
public final class ReferencePool<E> implements LockablePool<E> {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            ReferencePool.class);

    // The `LockingPool` contract requires some synchronization behaviors that
    // are not natively present in any of the JDK deque implementations -
    // specifically to allow one calling thread to call lock() to supercede
    // and hold off any pending pool borrowers until unlock() is called.
    // This class implementation fulfills the contract by managing the
    // synchronization constructs directly rather than deferring to an
    // underlying JDK data structure to manage concurrent access.
    //
    // This implementation is modeled somewhat off of what the
    // `LinkedBlockingDeque` class in the OpenJDK does to manage
    // concurrency.  It uses a single `ReentrantLock` to provide mutual
    // exclusion around offer and take requests, with condition variables
    // used to park and later reawaken requests as needed, e.g., when pool
    // items are unavailable for borrowing or when the pool lock is
    // unavailable.
    //
    // See http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/LinkedBlockingDeque.java#l157

    // Lock which guards all accesses to the underlying instance.
    // Constructed as "nonfair" for performance, like the lock that a
    // `LinkedBlockingDeque` does.  Not clear that we need this
    // to be a "fair" lock.
    private final ReentrantLock borrowLock = new ReentrantLock(false);

    // Condition signaled when all borrowed references have been
    // handed back or if a pill has been inserted.  Awaited when a
    // lock has been requested but one or more references have been
    // borrowed from the pool.
    private final Condition lockAvailable = borrowLock.newCondition();

    // Condition signaled when an element has been added into the queue.
    // Awaited when a request has been made to borrow an item but no elements
    // currently exist in the queue.
    private final Condition borrowsAvailable = borrowLock.newCondition();

    // Condition signaled when the pool has been unlocked.  Awaited when a
    // request has been made to borrow an reference or lock the pool but the pool
    // is currently locked.
    private final Condition poolNotLocked = borrowLock.newCondition();

    // Condition signaled when all borrowed references have been returned
    // to the pool. Awaited when we are preparing to delete the instance
    // and need to make sure it is not in use.
    private final Condition instanceNotBorrowed = borrowLock.newCondition();

    // The JRuby instance that this pool hands out references to
    private volatile E instance;

    // How many times the JRuby instance can be borrowed at once
    private int maxBorrowCount;

    // Current number of references to the pool held out in the world.
    // Updates to this need to be visible to all threads.
    private volatile AtomicInteger currentBorrowCount;

    // Thread which currently holds the pool lock.  null indicates that
    // there is no current pool lock holder.  Using the current Thread
    // object for tracking the pool lock owner is comparable to what the JDK's
    // `ReentrantLock` class does via the `AbstractOwnableSynchronizer` class:
    //
    // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/locks/ReentrantLock.java#l164
    // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/locks/AbstractOwnableSynchronizer.java#l64
    //
    // Unlike the `AbstractOwnableSynchronizer` class implementation, we marked
    // this variable as `volatile` because we couldn't convince ourselves
    // that it would be safe to update this variable from different threads and
    // not be susceptible to per-thread / per-CPU caching causing the wrong
    // value to be seen by a thread.  `volatile` seems safer and doesn't appear
    // to impose any noticeable performance degradation.
    private volatile Thread poolLockThread = null;

    // Holds a poison pill object for errors and shutdowns
    // If not null, takes priority over any pool instance when a call to
    // borrowItem is made. Returns made using releaseItem are ignored if the
    // released item is the poison pill already stored here
    private volatile E pill;

    /**
     * Create a "pool" of handles to a Jruby instance.
     *
     * @param maxBorrows the max number of instance refs that can be handed out
     */
    public ReferencePool(int maxBorrows) {
        this.instance = null;
        this.maxBorrowCount = maxBorrows;
        this.currentBorrowCount = new AtomicInteger(0);
    }

    @Override
    public void register(E e) {
        final ReentrantLock lock = this.borrowLock;
        lock.lock();
        try {
            if (instance != null) {
                throw new IllegalStateException(
                        "Unable to register additional instance, pool full");
            }

            instance = e;
            currentBorrowCount.set(0);

            signalPoolNotEmpty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Unregisters the JRuby instance. Blocks waiting for all borrows of the
     * instance to be returned before clearing it out. In this pool implementation,
     * `clear` and `unregister` are aliases of one another, since clearing the pool
     * is the same as clearing the on active instance.
     *
     * @param e the instance to clean up
     * @throws InterruptedException
     */
    @Override
    public void unregister(E e) throws InterruptedException {
        final ReentrantLock lock = this.borrowLock;
        lock.lock();
        try {
            if (currentBorrowCount.get() != 0) {
                instanceNotBorrowed.await();
            }
            instance = null;

            signalIfLockCanProceed();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public E borrowItem() throws InterruptedException {
        E item = null;
        final ReentrantLock lock = this.borrowLock;
        lock.lock();
        try {
            final Thread currentThread = Thread.currentThread();
            do {
                if (this.pill != null) {
                    // Return the pill immediately if there is one
                    item = pill;
                } else if (isPoolLockHeldByAnotherThread(currentThread)) {
                    poolNotLocked.await();
                } else if (instance == null) {
                    // No instance initialized yet
                    borrowsAvailable.await();
                } else if (this.currentBorrowCount.get() >= this.maxBorrowCount) {
                    // Max borrow count reached, wait for one to be returned
                    borrowsAvailable.await();
                } else {
                    item = this.instance;
                    this.currentBorrowCount.getAndIncrement();
                    LOGGER.info("Borrowing, count is " + currentBorrowCount.get());
                }
            } while (item == null);
        } finally {
            lock.unlock();
        }

        return item;
    }

    @Override
    public E borrowItemWithTimeout(long timeout, TimeUnit unit) throws
            InterruptedException {
        E item = null;
        final ReentrantLock lock = this.borrowLock;
        long remainingMaxTimeToWait = unit.toNanos(timeout);

        // `borrowLock.lockInterruptibly()` is called here as opposed to just
        // `borrowLock.borrowLock` to follow the pattern that the JDK's
        // `LinkedBlockingDeque` does for a timed poll from a deque.  See:
        // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/LinkedBlockingDeque.java#l516
        lock.lockInterruptibly();
        try {
            final Thread currentThread = Thread.currentThread();
            // This pattern of using timed `awaitNanos` on a condition
            // variable to track the total time spent waiting for an item to
            // be available to be borrowed follows the logic that the JDK's
            // `LinkedBlockingDeque` in `pollFirst` uses.  See:
            // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/LinkedBlockingDeque.java#l522
            do {
                if (this.pill != null) {
                    // Return the pill immediately if there is one
                    item = pill;
                } else if (isPoolLockHeldByAnotherThread(currentThread)) {
                    if (remainingMaxTimeToWait <= 0) {
                        break;
                    }
                    remainingMaxTimeToWait =
                            poolNotLocked.awaitNanos(remainingMaxTimeToWait);
                } else if (this.instance == null) {
                    // No instance initialized yet
                    if (remainingMaxTimeToWait <= 0) {
                        break;
                    }
                    remainingMaxTimeToWait =
                            borrowsAvailable.awaitNanos(remainingMaxTimeToWait);
                } else if (this.currentBorrowCount.get() >= this.maxBorrowCount) {
                    // Max borrow count reached, wait for one to be returned
                    if (remainingMaxTimeToWait <= 0) {
                        break;
                    }
                    remainingMaxTimeToWait =
                            borrowsAvailable.awaitNanos(remainingMaxTimeToWait);
                } else if (instance != null) {
                    item = instance;
                    this.currentBorrowCount.getAndIncrement();
                    LOGGER.info("Borrowing, count is " + currentBorrowCount.get());
                }
            } while (item == null);
        } finally {
            lock.unlock();
        }

        return item;
    }

    /**
     * Release an item and return it to the pool. Does nothing if the item
     * being released is the pill.
     * Throws an `IllegalArgumentException` if the item is not currently
     * registered by the pool and the item is not the pill, if one has been
     * inserted
     */
    @Override
    public void releaseItem(E e) {
        final ReentrantLock lock = this.borrowLock;
        lock.lock();
        try {
            if (e != this.pill) {
                if (!isRegistered(e)) {
                    String errorMsg = "The item being released is not registered with the pool";
                    throw new IllegalArgumentException(errorMsg);
                }

                this.currentBorrowCount.getAndDecrement();
                signalPoolNotEmpty();

                if (currentBorrowCount.get() == 0) {
                   instanceNotBorrowed.signal();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean isRegistered(E e) {
        return instance.equals(e);
    }

    /**
     * Insert a poison pill into the pool.  It should only ever be used to
     * insert a `PoisonPill` or `ShutdownPoisonPill` to the pool. Only the
     * first call will insert a pill. Subsequent insertions will be ignored
     */
    @Override
    public void insertPill(E e) {
        final ReentrantLock lock = this.borrowLock;
        lock.lock();
        try {
            if (this.pill == null) {
                this.pill = e;
                signalPoolNotEmpty();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Alias for unregister in this implementation. Blocks waiting for
     * all references to be returned to the pool.
     */
    @Override
    public void clear() throws InterruptedException {
        unregister(instance);
    }

    @Override
    public int remainingCapacity() {
        return instance == null ? 1 : 0;
    }

    @Override
    public int size() {
        int size;
        final ReentrantLock lock = this.borrowLock;
        lock.lock();
        try {
            if (instance == null) {
                size = 0;
            } else {
                size = this.maxBorrowCount - this.currentBorrowCount.get();
            }
        } finally {
            lock.unlock();
        }
        return size;
    }

    /**
     * Lock the pool. Blocks until the lock is granted and the pool has been filled
     * back up to its full capacity
     * @throws InterruptedException
     */
    @Override
    public void lock() throws InterruptedException {
        final ReentrantLock lock = this.borrowLock;
        lock.lock();
        try {
            String pillErrorMsg = "Lock can't be granted because a pill has been inserted";

            final Thread currentThread = Thread.currentThread();
            while (!isPoolLockHeldByCurrentThread(currentThread)) {
                if (this.pill != null) {
                    throw new InterruptedException(pillErrorMsg);
                }
                if (!isPoolLockHeld()) {
                    poolLockThread = currentThread;
                } else {
                    poolNotLocked.await();
                }
            }
            try {
                // Wait until all references have been returned to the pool
                while (this.currentBorrowCount.get() > 0) {
                    lockAvailable.await();
                    if (this.pill != null) {
                        throw new InterruptedException(pillErrorMsg);
                    }
                }
            } catch (Exception e) {
                freePoolLock();
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void lockWithTimeout(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        final ReentrantLock lock = this.borrowLock;
        long remainingMaxTimeToWait = unit.toNanos(timeout);

        // `borrowLock.lockInterruptibly()` is called here as opposed to just
        // `borrowLock.borrowLock` to follow the pattern that the JDK's
        // `LinkedBlockingDeque` does for a timed poll from a deque.  See:
        // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/share/classes/java/util/concurrent/LinkedBlockingDeque.java#l516
        lock.lockInterruptibly();
        try {
            String pillErrorMsg = "Lock can't be granted because a pill has been inserted";
            String timeoutErrorMsg = "Timeout limit reached before lock could be granted";

            final Thread currentThread = Thread.currentThread();
            while (!isPoolLockHeldByCurrentThread(currentThread)) {
                if (this.pill != null) {
                    throw new InterruptedException(pillErrorMsg);
                }

                if (!isPoolLockHeld()) {
                    poolLockThread = currentThread;
                } else {
                    if (remainingMaxTimeToWait <= 0) {
                        throw new TimeoutException(timeoutErrorMsg);
                    }
                    remainingMaxTimeToWait = poolNotLocked.awaitNanos(remainingMaxTimeToWait);
                }
            }

            try {
                // Wait until all references have been returned to the pool
                while (this.currentBorrowCount.get() > 0) {
                    if (remainingMaxTimeToWait <= 0) {
                        throw new TimeoutException(timeoutErrorMsg);
                    }
                    remainingMaxTimeToWait = lockAvailable.awaitNanos(remainingMaxTimeToWait);

                    if (this.pill != null) {
                        throw new InterruptedException(pillErrorMsg);
                    }
                }
            } catch (Exception e) {
                freePoolLock();
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isLocked() {
        boolean locked;
        final ReentrantLock lock = this.borrowLock;
        lock.lock();
        try {
            locked = isPoolLockHeld();
        } finally {
            lock.unlock();
        }
        return locked;
    }

    @Override
    public void unlock() {
        final ReentrantLock lock = this.borrowLock;
        lock.lock();
        try {
            final Thread currentThread = Thread.currentThread();
            if (!isPoolLockHeldByCurrentThread(currentThread)) {
                String lockErrorMessage;
                if (isPoolLockHeldByAnotherThread(currentThread)) {
                    lockErrorMessage = "held by " + poolLockThread;
                } else {
                    lockErrorMessage = "not held by any thread";
                }
                throw new IllegalStateException(
                        "Unlock requested from thread not holding the lock.  " +
                        "Requested from " +
                        currentThread +
                        " but lock " +
                        lockErrorMessage +
                        ".");
            }
            freePoolLock();
        } finally {
            lock.unlock();
        }
    }

    public Set<E> getRegisteredElements() {
      Set<E> registered = new CopyOnWriteArraySet<E>();
      if (instance != null) {
          registered.add(instance);
      }
      return registered;
    }

    private void freePoolLock() {
        poolLockThread = null;
        // Need to use 'signalAll' here because there might be multiple
        // waiters (e.g., multiple borrowers) queued up, waiting for the
        // pool to be unlocked.
        poolNotLocked.signalAll();
        // Borrowers that are woken up when an instance is returned to the
        // pool and the pool borrowLock is held would then start waiting on a
        // 'poolNotLocked' signal instead.  Re-signalling 'borrowsAvailable' here
        // allows any borrowers still waiting on the 'borrowsAvailable' signal to be
        // reawoken when the pool lock is released, compensating for any
        // 'borrowsAvailable' signals that might have been essentially ignored from
        // when the pool lock was held.
        if (this.currentBorrowCount.get() < this.maxBorrowCount) {
            borrowsAvailable.signalAll();
        }
    }

    /**
     * Should be called if the pool is no longer empty (or a pill is inserted),
     * so that threads waiting for pool instances can be woken up
     */
    private void signalPoolNotEmpty() {
        // Could use 'signalAll' here instead of 'signal' but 'signal' is
        // less expensive in that only one waiter will be woken up.  Can use
        // signal here because the thread being awoken will be able to borrow
        // a pool instance and any further waiters will be woken up by
        // subsequent posts of this signal when instances are added/returned to
        // the queue.
        borrowsAvailable.signal();
        signalIfLockCanProceed();
    }

    /**
     * Checks if threads waiting on the pool lock should be woken up.
     * This will wake them up if either all borrowed references have
     * been returned to the pool, or if a pill has been inserted
     */
    private void signalIfLockCanProceed() {
        // Could use 'signalAll' here instead of 'signal'.  Doesn't really
        // matter though in that there will only be one waiter at most which
        // is active at a time - a caller of lock() that has just acquired
        // the pool lock but is waiting for the live queue to be completely
        // filled
        if ((instance != null && currentBorrowCount.get() == 0) || pill != null) {
            lockAvailable.signal();
        }
    }

    private boolean isPoolLockHeld() {
        return poolLockThread != null;
    }

    private boolean isPoolLockHeldByCurrentThread(Thread currentThread) {
        return poolLockThread == currentThread;
    }

    private boolean isPoolLockHeldByAnotherThread(Thread currentThread) {
        return (poolLockThread != null) && (poolLockThread != currentThread);
    }
}
