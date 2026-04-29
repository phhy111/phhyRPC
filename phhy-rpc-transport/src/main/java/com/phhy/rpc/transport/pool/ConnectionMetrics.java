package com.phhy.rpc.transport.pool;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Getter
public class ConnectionMetrics {

    private final String instanceKey;

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger faultConnections = new AtomicInteger(0);

    private final LongAdder requestCount = new LongAdder();
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final LongAdder totalResponseTime = new LongAdder();

    private final AtomicLong createdCount = new AtomicLong(0);
    private final AtomicLong destroyedCount = new AtomicLong(0);

    private volatile boolean warmedUp = false;
    private volatile long lastUsedTime = System.currentTimeMillis();

    public ConnectionMetrics(String instanceKey) {
        this.instanceKey = instanceKey;
    }

    public void setWarmedUp(boolean warmedUp) {
        this.warmedUp = warmedUp;
    }

    public void recordRequest() {
        requestCount.increment();
        lastUsedTime = System.currentTimeMillis();
    }

    public void recordSuccess(long responseTimeMillis) {
        successCount.increment();
        totalResponseTime.add(responseTimeMillis);
    }

    public void recordFailure() {
        failureCount.increment();
    }

    public void recordConnectionCreated() {
        createdCount.incrementAndGet();
        totalConnections.incrementAndGet();
    }

    public void recordConnectionDestroyed() {
        destroyedCount.incrementAndGet();
        totalConnections.decrementAndGet();
    }

    public double getAverageResponseTime() {
        long success = successCount.sum();
        if (success == 0) {
            return 0.0;
        }
        return (double) totalResponseTime.sum() / success;
    }

    public double getSuccessRate() {
        long total = requestCount.sum();
        if (total == 0) {
            return 1.0;
        }
        return (double) successCount.sum() / total;
    }

    public double getUsageRate() {
        int total = totalConnections.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) activeConnections.get() / total;
    }
}
