package com.ringforge.chord.service;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ServiceHeartbeatScheduler implements AutoCloseable {
    private final ServiceChordNode node;
    private final long intervalMillis;
    private final Clock clock;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ringforge-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger runCount = new AtomicInteger();
    private volatile List<Integer> lastFailedNodeIds = Collections.emptyList();
    private volatile long lastRunEpochMillis;

    public ServiceHeartbeatScheduler(ServiceChordNode node, long intervalMillis) {
        this(node, intervalMillis, Clock.systemUTC());
    }

    ServiceHeartbeatScheduler(ServiceChordNode node, long intervalMillis, Clock clock) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive");
        }
        this.node = node;
        this.intervalMillis = intervalMillis;
        this.clock = clock;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::repairSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public long intervalMillis() {
        return intervalMillis;
    }

    public int runCount() {
        return runCount.get();
    }

    public long lastRunEpochMillis() {
        return lastRunEpochMillis;
    }

    public List<Integer> lastFailedNodeIds() {
        return Collections.unmodifiableList(lastFailedNodeIds);
    }

    private void repairSafely() {
        try {
            lastFailedNodeIds = node.repairFailedMembers();
            lastRunEpochMillis = clock.millis();
            runCount.incrementAndGet();
        } catch (RuntimeException ignored) {
            lastRunEpochMillis = clock.millis();
            runCount.incrementAndGet();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
