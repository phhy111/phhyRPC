package com.phhy.rpc.transport.pool;

import com.phhy.rpc.common.exception.RpcException;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
public class SmartConnectionPool {

    private final ConnectionPoolConfig config;
    private final EventLoopGroup workerGroup;

    private final Map<String, List<PooledChannel>> poolMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> usageCounter = new ConcurrentHashMap<>();
    private final Map<String, ConnectionMetrics> metricsMap = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> healthCheckTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> adjustTasks = new ConcurrentHashMap<>();
    private final Set<String> warmedUpSet = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "smart-pool-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final ReentrantReadWriteLock poolLock = new ReentrantReadWriteLock();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public SmartConnectionPool(ConnectionPoolConfig config, EventLoopGroup workerGroup) {
        this.config = config != null ? config : ConnectionPoolConfig.defaultConfig();
        this.workerGroup = workerGroup;
    }

    public ConnectionMetrics getMetrics(String instanceKey) {
        return metricsMap.computeIfAbsent(instanceKey, ConnectionMetrics::new);
    }

    public Map<String, ConnectionMetrics> getAllMetrics() {
        return new HashMap<>(metricsMap);
    }

    public void warmup(String host, int port) {
        String key = host + ":" + port;
        if (warmedUpSet.contains(key)) {
            return;
        }
        ConnectionMetrics metrics = getMetrics(key);
        if (metrics.isWarmedUp()) {
            return;
        }

        int count = Math.min(config.getWarmupConnections(), config.getMaxConnectionsPerInstance());
        log.info("开始为 {} 预热连接，数量: {}", key, count);

        CompletableFuture.runAsync(() -> {
            int success = 0;
            for (int i = 0; i < count; i++) {
                try {
                    PooledChannel pc = createPooledChannel(host, port);
                    if (pc != null) {
                        addToPool(key, pc);
                        success++;
                    }
                } catch (Exception e) {
                    log.warn("预热连接失败 {} 第 {}/{}: {}", key, i + 1, count, e.getMessage());
                }
            }
            metrics.setWarmedUp(true);
            warmedUpSet.add(key);
            log.info("{} 预热完成，成功 {}/{} 个连接", key, success, count);

            startHealthCheck(key);
            startDynamicAdjust(key, host, port);
        });
    }

    public Channel acquireChannel(String host, int port) {
        String key = host + ":" + port;
        ensurePoolExists(key);

        List<PooledChannel> pool = poolMap.get(key);
        if (pool != null) {
            for (PooledChannel pc : pool) {
                if (pc.isActive() && pc.acquire()) {
                    getMetrics(key).recordRequest();
                    getMetrics(key).getActiveConnections().incrementAndGet();
                    return pc.getChannel();
                }
            }
        }

        int currentTotal = pool != null ? pool.size() : 0;
        if (currentTotal < config.getMaxConnectionsPerInstance()) {
            try {
                PooledChannel pc = createPooledChannel(host, port);
                if (pc != null && pc.acquire()) {
                    addToPool(key, pc);
                    getMetrics(key).recordRequest();
                    getMetrics(key).getActiveConnections().incrementAndGet();
                    return pc.getChannel();
                }
            } catch (Exception e) {
                log.error("创建新连接失败 {}: {}", key, e.getMessage());
            }
        }

        if (pool != null) {
            for (PooledChannel pc : pool) {
                if (pc.isActive()) {
                    if (pc.acquire()) {
                        getMetrics(key).recordRequest();
                        getMetrics(key).getActiveConnections().incrementAndGet();
                        return pc.getChannel();
                    }
                }
            }
        }

        throw new RpcException("无法获取可用连接到 " + key);
    }

    public void releaseChannel(String host, int port, Channel channel) {
        String key = host + ":" + port;
        List<PooledChannel> pool = poolMap.get(key);
        if (pool == null) {
            if (channel != null && channel.isActive()) {
                channel.close();
            }
            return;
        }
        for (PooledChannel pc : pool) {
            if (pc.getChannel() == channel) {
                pc.release();
                getMetrics(key).getActiveConnections().decrementAndGet();
                return;
            }
        }
        if (channel != null && channel.isActive()) {
            channel.close();
        }
    }

    public void markFault(String host, int port) {
        String key = host + ":" + port;
        List<PooledChannel> pool = poolMap.get(key);
        if (pool != null) {
            for (PooledChannel pc : pool) {
                pc.markFault();
            }
        }
        getMetrics(key).getFaultConnections().addAndGet(pool != null ? pool.size() : 0);
        cancelTasks(key);
        poolMap.remove(key);
        usageCounter.remove(key);
        warmedUpSet.remove(key);
        log.warn("实例 {} 被标记为故障，连接池已清空", key);
    }

    public boolean isHealthy(String host, int port) {
        String key = host + ":" + port;
        List<PooledChannel> pool = poolMap.get(key);
        if (pool == null || pool.isEmpty()) {
            return false;
        }
        return pool.stream().anyMatch(PooledChannel::isActive);
    }

    public void removeInstance(String key) {
        cancelTasks(key);
        List<PooledChannel> pool = poolMap.remove(key);
        if (pool != null) {
            for (PooledChannel pc : pool) {
                if (pc.getChannel() != null && pc.getChannel().isActive()) {
                    pc.getChannel().close();
                }
            }
        }
        usageCounter.remove(key);
        warmedUpSet.remove(key);
        log.info("实例 {} 的连接池已移除", key);
    }

    public void closeAll() {
        if (shutdown.compareAndSet(false, true)) {
            for (String key : new ArrayList<>(poolMap.keySet())) {
                removeInstance(key);
            }
            for (ScheduledFuture<?> future : healthCheckTasks.values()) {
                future.cancel(false);
            }
            for (ScheduledFuture<?> future : adjustTasks.values()) {
                future.cancel(false);
            }
            healthCheckTasks.clear();
            adjustTasks.clear();
            scheduler.shutdown();
            log.info("SmartConnectionPool 已关闭");
        }
    }

    private void ensurePoolExists(String key) {
        poolMap.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        usageCounter.computeIfAbsent(key, k -> new AtomicInteger(0));
    }

    private void addToPool(String key, PooledChannel pc) {
        ensurePoolExists(key);
        poolMap.get(key).add(pc);
        getMetrics(key).recordConnectionCreated();
    }

    private PooledChannel createPooledChannel(String host, int port) throws Exception {
        Channel channel = doCreateChannel(host, port);
        String key = host + ":" + port;
        PooledChannel pc = new PooledChannel(channel, key);
        channel.closeFuture().addListener(f -> {
            removeFromPool(key, pc);
        });
        return pc;
    }

    private Channel doCreateChannel(String host, int port) throws Exception {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.getConnectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        SslContext sslContext = SslContextBuilder.forClient()
                                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                .applicationProtocolConfig(new ApplicationProtocolConfig(
                                        ApplicationProtocolConfig.Protocol.ALPN,
                                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                                        ApplicationProtocolNames.HTTP_2,
                                        ApplicationProtocolNames.HTTP_1_1))
                                .build();
                        pipeline.addLast(sslContext.newHandler(ch.alloc(), host, port));
                        Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forClient();
                        frameCodecBuilder.initialSettings(new Http2Settings()
                                .maxConcurrentStreams(100)
                                .initialWindowSize(1048576));
                        pipeline.addLast(frameCodecBuilder.build());
                        pipeline.addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                            }
                        }));
                    }
                });
        ChannelFuture future = bootstrap.connect(host, port).sync();
        return future.channel();
    }

    private void removeFromPool(String key, PooledChannel pc) {
        List<PooledChannel> pool = poolMap.get(key);
        if (pool != null) {
            pool.remove(pc);
            getMetrics(key).recordConnectionDestroyed();
        }
    }

    private void startHealthCheck(String key) {
        if (healthCheckTasks.containsKey(key)) {
            return;
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                doHealthCheck(key);
            } catch (Exception e) {
                log.warn("健康检查异常 {}", key, e);
            }
        }, config.getHealthCheckIntervalMillis(), config.getHealthCheckIntervalMillis(), TimeUnit.MILLISECONDS);
        healthCheckTasks.put(key, future);
    }

    private void doHealthCheck(String key) {
        List<PooledChannel> pool = poolMap.get(key);
        if (pool == null) {
            return;
        }
        List<PooledChannel> toRemove = new ArrayList<>();
        for (PooledChannel pc : pool) {
            if (!pc.isActive()) {
                toRemove.add(pc);
                continue;
            }
            long idleTime = pc.getIdleTime();
            if (idleTime > config.getIdleTimeoutMillis()) {
                log.info("连接 {} 空闲超时，准备关闭", key);
                pc.markFault();
                toRemove.add(pc);
            }
        }
        for (PooledChannel pc : toRemove) {
            removeFromPool(key, pc);
            if (pc.getChannel() != null && pc.getChannel().isActive()) {
                pc.getChannel().close();
            }
        }
    }

    private void startDynamicAdjust(String key, String host, int port) {
        if (adjustTasks.containsKey(key)) {
            return;
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                doDynamicAdjust(key, host, port);
            } catch (Exception e) {
                log.warn("动态调整异常 {}", key, e);
            }
        }, config.getDynamicAdjustIntervalMillis(), config.getDynamicAdjustIntervalMillis(), TimeUnit.MILLISECONDS);
        adjustTasks.put(key, future);
    }

    private void doDynamicAdjust(String key, String host, int port) {
        List<PooledChannel> pool = poolMap.get(key);
        if (pool == null) {
            return;
        }
        ConnectionMetrics metrics = getMetrics(key);
        int currentSize = pool.size();
        double usageRate = metrics.getUsageRate();
        double avgResponseTime = metrics.getAverageResponseTime();
        double successRate = metrics.getSuccessRate();

        log.debug("动态调整 {}: 当前连接数={}, 使用率={:.2f}, 平均响应时间={:.2f}ms, 成功率={:.2f}",
                key, currentSize, usageRate, avgResponseTime, successRate);

        if (usageRate > config.getLoadThreshold() && currentSize < config.getMaxConnectionsPerInstance()) {
            int increase = Math.min(
                    Math.max(1, (int) Math.ceil(currentSize * 0.5)),
                    config.getMaxConnectionsPerInstance() - currentSize
            );
            log.info("{} 负载过高，增加 {} 个连接", key, increase);
            for (int i = 0; i < increase; i++) {
                try {
                    PooledChannel pc = createPooledChannel(host, port);
                    if (pc != null) {
                        addToPool(key, pc);
                    }
                } catch (Exception e) {
                    log.warn("动态增加连接失败 {}: {}", key, e.getMessage());
                    break;
                }
            }
        } else if (usageRate < config.getLoadThreshold() / 2
                && currentSize > config.getMinConnectionsPerInstance()
                && avgResponseTime < config.getResponseTimeThresholdMillis()
                && successRate > 0.95) {
            int decrease = Math.min(
                    Math.max(1, (int) Math.ceil(currentSize * 0.25)),
                    currentSize - config.getMinConnectionsPerInstance()
            );
            log.info("{} 负载过低，减少 {} 个连接", key, decrease);
            int removed = 0;
            for (PooledChannel pc : new ArrayList<>(pool)) {
                if (removed >= decrease) {
                    break;
                }
                if (!pc.isInUse() && pc.isActive()) {
                    pc.markFault();
                    removeFromPool(key, pc);
                    removed++;
                }
            }
        }
    }

    private void cancelTasks(String key) {
        ScheduledFuture<?> healthTask = healthCheckTasks.remove(key);
        if (healthTask != null) {
            healthTask.cancel(false);
        }
        ScheduledFuture<?> adjustTask = adjustTasks.remove(key);
        if (adjustTask != null) {
            adjustTask.cancel(false);
        }
    }
}
