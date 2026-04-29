package com.phhy.rpc.transport.pool;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ConnectionPoolConfig {

    public static final int DEFAULT_MAX_CONNECTIONS_PER_INSTANCE = 10;
    public static final int DEFAULT_MIN_CONNECTIONS_PER_INSTANCE = 2;
    public static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 5000L;
    public static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 60000L;
    public static final int DEFAULT_WARMUP_CONNECTIONS = 2;
    public static final double DEFAULT_LOAD_THRESHOLD = 0.8;
    public static final long DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS = 30000L;
    public static final long DEFAULT_DYNAMIC_ADJUST_INTERVAL_MILLIS = 30000L;
    public static final long DEFAULT_RESPONSE_TIME_THRESHOLD_MILLIS = 1000L;

    private int maxConnectionsPerInstance;
    private int minConnectionsPerInstance;
    private long connectTimeoutMillis;
    private long idleTimeoutMillis;
    private int warmupConnections;
    private double loadThreshold;
    private long healthCheckIntervalMillis;
    private long dynamicAdjustIntervalMillis;
    private long responseTimeThresholdMillis;

    public static ConnectionPoolConfig defaultConfig() {
        return ConnectionPoolConfig.builder()
                .maxConnectionsPerInstance(DEFAULT_MAX_CONNECTIONS_PER_INSTANCE)
                .minConnectionsPerInstance(DEFAULT_MIN_CONNECTIONS_PER_INSTANCE)
                .connectTimeoutMillis(DEFAULT_CONNECT_TIMEOUT_MILLIS)
                .idleTimeoutMillis(DEFAULT_IDLE_TIMEOUT_MILLIS)
                .warmupConnections(DEFAULT_WARMUP_CONNECTIONS)
                .loadThreshold(DEFAULT_LOAD_THRESHOLD)
                .healthCheckIntervalMillis(DEFAULT_HEALTH_CHECK_INTERVAL_MILLIS)
                .dynamicAdjustIntervalMillis(DEFAULT_DYNAMIC_ADJUST_INTERVAL_MILLIS)
                .responseTimeThresholdMillis(DEFAULT_RESPONSE_TIME_THRESHOLD_MILLIS)
                .build();
    }
}
