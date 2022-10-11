package com.yahoo.vespa.curator;

import com.yahoo.jdisc.Metric;
import com.yahoo.path.Path;
import com.yahoo.protect.Process;
import com.yahoo.vespa.curator.api.VespaCurator.SingletonWorker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages {@link com.yahoo.vespa.curator.api.VespaCurator.SingletonWorker}.
 *
 * @author jonmv
 */
class SingletonManager {

    private static final Logger logger = Logger.getLogger(SingletonManager.class.getName());

    private final Curator curator;
    private final Clock clock;
    private final Duration tickTimeout;
    private final Map<String, Janitor> janitors = new HashMap<>();
    private final Map<String, Integer> count = new HashMap<>();
    private final Map<SingletonWorker, String> registrations = new IdentityHashMap<>();
    private final Metric metric;

    SingletonManager(Curator curator, Clock clock, Duration tickTimeout, Metric metric) {
        this.curator = curator;
        this.clock = clock;
        this.tickTimeout = tickTimeout;
        this.metric = metric;
    }

    synchronized CompletableFuture<?> register(String singletonId, SingletonWorker singleton) {
        if (singletonId.isEmpty() || singletonId.contains("/") || singletonId.contains("..")) {
            throw new IllegalArgumentException("singleton ID must be non-empty, and may not contain '/' or '..', but got " + singletonId);
        }
        String old = registrations.putIfAbsent(singleton, singletonId);
        if (old != null) throw new IllegalArgumentException(singleton + " already registered with ID " + old);
        count.merge(singletonId, 1, Integer::sum);
        return janitors.computeIfAbsent(singletonId, Janitor::new).register(singleton);
    }

    synchronized CompletableFuture<?> unregister(SingletonWorker singleton) {
        String id = registrations.remove(singleton);
        if (id == null) throw new IllegalArgumentException(singleton + " is not registered");
        return janitors.get(id).unregister(singleton)
                       .whenComplete((__, ___) -> unregistered(id, singleton));
    }

    synchronized void unregistered(String singletonId, SingletonWorker singleton) {
        registrations.remove(singleton);
        if (count.merge(singletonId, -1, Integer::sum) > 0) return;
        count.remove(singletonId);
        janitors.remove(singletonId).shutdown();
    }

    /**
     * The instant until which this container holds the exclusive lease for activation of singletons with this ID.
     * The container may abandon the lease early, if deactivation is triggered and completes before the deadline.
     * Unless connection to the underlying ZK cluster is lost, the returned value will regularly move forwards in time.
     */
    synchronized Optional<Instant> activeUntil(String singletonId) {
        return Optional.ofNullable(janitors.get(singletonId)).map(janitor -> janitor.doom.get());
    }

    /** Whether this container currently holds the activation lease for the given singleton ID. */
    boolean isActive(String singletonId) {
        return activeUntil(singletonId).map(clock.instant()::isBefore).orElse(false);
    }

    /** Invalidate all leases, due to connection loss. */
    synchronized void invalidate() {
        for (Janitor janitor : janitors.values()) janitor.invalidate();
    }

    public synchronized CompletableFuture<?> shutdown() {
        CompletableFuture<?>[] futures = new CompletableFuture[registrations.size()];
        int i = 0;
        for (SingletonWorker singleton : List.copyOf(registrations.keySet())) {
            String id = registrations.get(singleton);
            logger.log(Level.WARNING, singleton + " still registered with id '" + id + "' at shutdown");
            futures[i++] = unregister(singleton);
        }
        return CompletableFuture.allOf(futures)
                                .orTimeout(10, TimeUnit.SECONDS);
    }


    private class Janitor {

        static class Task {

            enum Type { register, unregister }

            final Type type;
            final SingletonWorker singleton;
            final CompletableFuture<?> future = new CompletableFuture<>();

            private Task(Type type, SingletonWorker singleton) {
                this.type = type;
                this.singleton = singleton;
            }

            static Task register(SingletonWorker singleton) { return new Task(Type.register, singleton); }
            static Task unregister(SingletonWorker singleton) { return new Task(Type.unregister, singleton); }

        }

        private static final Instant INVALID = Instant.ofEpochMilli(-1);

        final BlockingDeque<Task> tasks = new LinkedBlockingDeque<>();
        final Deque<SingletonWorker> singletons = new ArrayDeque<>(2);
        final AtomicReference<Instant> doom = new AtomicReference<>();
        final AtomicBoolean shutdown = new AtomicBoolean();
        final Thread worker;
        final String id;
        final Path path;
        final MetricHelper metrics;
        Lock lock = null;
        boolean active;

        Janitor(String id) {
            this.id = id;
            this.path = Path.fromString("/vespa/singleton/v1/" + id);
            this.worker = new Thread(this::run, "singleton-janitor-" + id + "-");
            this.metrics = new MetricHelper();

            worker.setDaemon(true);
            worker.start();
        }

        public void unlock() {
            doom.set(null);
            if (lock != null) try {
                metrics.hasLease(false);
                lock.close();
                lock = null;
            }
            catch (Exception e) {
                logger.log(Level.WARNING, "failed closing " + lock, e);
            }
        }

        private void run() {
            try {
                while ( ! shutdown.get()) {
                    try {
                        // Attempt to acquire the lock, and extend our doom.
                        renewLease();
                        // Ensure activation status is set accordingly to doom, or clear state if this fails.
                        updateStatus();
                        // Process the next pending, externally triggered task, if any.
                        doTask();
                    }
                    catch (InterruptedException e) {
                        if ( ! shutdown.get()) {
                            logger.log(Level.WARNING, worker + " interrupted, restarting event loop");
                        }
                    }
                }
                unlock();
            }
            catch (Throwable t) {
                Process.logAndDie(worker + " can't continue, shutting down", t);
            }
        }

        protected void doTask() throws InterruptedException {
            Task task = tasks.poll(tickTimeout.toMillis(), TimeUnit.MILLISECONDS);
            try {
                if (task != null) switch (task.type) {
                    case register -> {
                        doRegister(task.singleton);
                        task.future.complete(null);
                    }
                    case unregister -> {
                        doUnregister(task.singleton);
                        task.future.complete(null);
                    }
                    default -> throw new AssertionError("unknown task type '" + task.type + "'");
                }
            }
            catch (RuntimeException e) {
                logger.log(Level.WARNING, "Uncaught exception in " + worker, e);
                task.future.completeExceptionally(e);
            }
        }

        private void doRegister(SingletonWorker singleton) {
            SingletonWorker current = singletons.peek();
            singletons.push(singleton);
            if (active) {
                RuntimeException e = null;
                if (current != null) try {
                    metrics.deactivation(current::deactivate);
                }
                catch (RuntimeException f) {
                    e = f;
                }
                try {
                    metrics.activation(singleton::activate);
                }
                catch (RuntimeException f) {
                    if (e == null) e = f;
                    else e.addSuppressed(f);
                }
                if (singletons.isEmpty()) doom.set(INVALID);
                if (e != null) throw e;
            }
        }

        private void doUnregister(SingletonWorker singleton) {
            RuntimeException e = null;
            SingletonWorker current = singletons.peek();
            if ( ! singletons.remove(singleton)) return;
            if (active && current == singleton) {
                try {
                    metrics.deactivation(singleton::deactivate);
                }
                catch (RuntimeException f) {
                    e = f;
                }
                if ( ! singletons.isEmpty()) try {
                    metrics.activation(singletons.peek()::activate);
                }
                catch (RuntimeException f) {
                    if (e == null) e = f;
                    else e.addSuppressed(f);
                }
            }
            if (singletons.isEmpty()) doom.set(INVALID);
            if (e != null) throw e;
        }

        /**
         * Attempt to acquire the lock, if not held.
         * If lock is held, or acquired, ping the ZK cluster to extend our deadline.
         */
        private void renewLease() {
            if (doom.get() == INVALID) {
                doom.set(null);
                return; // Skip to updateStatus, deactivation, and release the lock.
            }
            // Witness value to detect if invalidation occurs between here and successful ping.
            Instant ourDoom = doom.get();
            Instant start = clock.instant();
            if (lock == null) try {
                lock = curator.lock(path.append("lock"), tickTimeout);
                metrics.hasLease(true);
            }
            catch (RuntimeException e) {
                logger.log(Level.FINE, "Failed acquiring lock for '" + path + "' within " + tickTimeout, e);
                return;
            }
            try {
                curator.set(path.append("ping"), new byte[0]);
            }
            catch (RuntimeException e) {
                logger.log(Level.FINE, "Failed pinging ZK cluster", e);
                return;
            }
            if ( ! doom.compareAndSet(ourDoom, start.plus(Curator.ZK_SESSION_TIMEOUT.multipliedBy(9).dividedBy(10)))) {
                logger.log(Level.FINE, "Deadline changed, invalidating current lease renewal");
            }
        }

        /**
         * Attempt to activate or deactivate if status has changed.
         * If activation fails, we release the lock, to a different container may acquire it.
         */
        private void updateStatus() {
            Instant ourDoom = doom.get();
            boolean shouldBeActive = ourDoom != null && ourDoom != INVALID && ! clock.instant().isAfter(ourDoom);
            if ( ! active && shouldBeActive) {
                try {
                    active = true;
                    if ( ! singletons.isEmpty()) metrics.activation(singletons.peek()::activate);
                }
                catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Failed to activate " + singletons.peek() + ", deactivating again", e);
                    shouldBeActive = false;
                }
            }
            if (active && ! shouldBeActive) {
                try  {
                    if ( ! singletons.isEmpty()) metrics.deactivation(singletons.peek()::deactivate);
                    active = false;
                }
                catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Failed to deactivate " + singletons.peek(), e);
                }
                unlock();
            }
        }

        CompletableFuture<?> register(SingletonWorker singleton) {
            Task task = Task.register(singleton);
            tasks.offer(task);
            return task.future;
        }

        CompletableFuture<?> unregister(SingletonWorker singleton) {
            Task task = Task.unregister(singleton);
            tasks.offer(task);
            return task.future;
        }

        void invalidate() {
            doom.set(INVALID);
        }

        void shutdown() {
            if ( ! shutdown.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Shutdown called more than once on " + this);
            }
            if (Thread.currentThread() != worker) {
                try {
                    worker.join();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            else {
                unlock();
            }
            if ( ! tasks.isEmpty()) {
                logger.log(Level.WARNING, "Non-empty task list after shutdown: " + tasks.size() + " remaining");
                for (Task task : tasks) task.future.cancel(true); // Shouldn't happen.
            }
        }

        private class MetricHelper {

            static final String PREFIX = "singleton.";
            static final String HAS_LEASE = PREFIX + "has_lease";
            static final String IS_ACTIVE = PREFIX + "is_active";
            static final String ACTIVATION = PREFIX + "activation.count";
            static final String ACTIVATION_MILLIS = PREFIX + "activation.millis";
            static final String ACTIVATION_FAILURES = PREFIX + "activation.failure.count";
            static final String DEACTIVATION = PREFIX + "deactivation.count";
            static final String DEACTIVATION_MILLIS = PREFIX + "deactivation.millis";
            static final String DEACTIVATION_FAILURES = PREFIX + "deactivation.failure.count";

            final Metric.Context context;

            MetricHelper() {
                this.context = metric.createContext(Map.of("singletonId", id));
            }

            void hasLease(boolean hasLease) {
                metric.set(HAS_LEASE, hasLease ? 1 : 0, context);
            }

            void activation(Runnable activation) {
                Instant start = clock.instant();
                boolean failed = false;
                metric.add(ACTIVATION, 1, context);
                try {
                    activation.run();
                }
                catch (RuntimeException e) {
                    failed = true;
                    throw e;
                }
                finally {
                    metric.set(ACTIVATION_MILLIS, Duration.between(start, clock.instant()).toMillis(), context);
                    if (failed) metric.add(ACTIVATION_FAILURES, 1, context);
                    else metric.set(IS_ACTIVE, 1, context);
                }
            }

            void deactivation(Runnable deactivation) {
                Instant start = clock.instant();
                boolean failed = false;
                metric.add(DEACTIVATION, 1, context);
                try {
                    deactivation.run();
                }
                catch (RuntimeException e) {
                    failed = true;
                    throw e;
                }
                finally {
                    metric.set(DEACTIVATION_MILLIS, Duration.between(start, clock.instant()).toMillis(), context);
                    if (failed) metric.add(DEACTIVATION_FAILURES, 1, context);
                    metric.set(IS_ACTIVE, 0, context);
                }
            }

        }

    }

}
