package com.phhy.rpc.transport.client;

import com.phhy.rpc.common.constant.RpcConstant;
import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.protocol.model.RpcMessage;
import com.phhy.rpc.transport.pool.SmartConnectionPool;
import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ClientHeartbeatManager {

    private final Map<String, Long> lastHeartbeatTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHeartbeatResponseTime = new ConcurrentHashMap<>();
    private final Map<String, Long> heartbeatResponseTimes = new ConcurrentHashMap<>();
    private final SmartConnectionPool connectionPool;
    private final SerializeType serializeType;
    private final ScheduledExecutorService scheduler;
    private NettyRpcClient nettyRpcClient;

    private static final long HEARTBEAT_INTERVAL = 30_000L;
    private static final long HEARTBEAT_TIMEOUT = 60_000L;

    public ClientHeartbeatManager(SmartConnectionPool connectionPool, SerializeType serializeType) {
        this.connectionPool = connectionPool;
        this.serializeType = serializeType;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "client-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public void setNettyRpcClient(NettyRpcClient nettyRpcClient) {
        this.nettyRpcClient = nettyRpcClient;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::checkTimeouts, HEARTBEAT_TIMEOUT, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
        log.info("客户端心跳管理器已启动");
    }

    private void sendHeartbeats() {
        for (Map.Entry<String, Long> entry : lastHeartbeatTime.entrySet()) {
            String key = entry.getKey();
            if (nettyRpcClient == null) {
                continue;
            }

            try {
                Channel channel = connectionPool.acquireChannel(
                        key.substring(0, key.indexOf(":")),
                        Integer.parseInt(key.substring(key.indexOf(":") + 1))
                );

                if (channel != null && channel.isActive()) {
                    RpcMessage heartbeatMsg = RpcMessage.builder()
                            .version(RpcConstant.VERSION)
                            .msgType(MsgType.HEARTBEAT_REQ)
                            .serializeType(serializeType)
                            .requestId(System.currentTimeMillis())
                            .body(null)
                            .build();

                    long sendTime = System.currentTimeMillis();
                    nettyRpcClient.createStreamBootstrap(channel).open()
                            .addListener((GenericFutureListener<io.netty.util.concurrent.Future<Http2StreamChannel>>) streamFuture -> {
                                try {
                                    if (streamFuture.isSuccess()) {
                                        Http2StreamChannel streamChannel = streamFuture.getNow();
                                        nettyRpcClient.ensureStreamPipeline(streamChannel);
                                        nettyRpcClient.writeRpcMessage(streamChannel, heartbeatMsg).addListener(future -> {
                                            if (!future.isSuccess()) {
                                                log.warn("发送心跳失败到 {}", key);
                                            }
                                        });
                                    } else {
                                        log.warn("创建心跳流通道失败到 {}: {}", key, streamFuture.cause().getMessage());
                                    }
                                } finally {
                                    connectionPool.releaseChannel(
                                            key.substring(0, key.indexOf(":")),
                                            Integer.parseInt(key.substring(key.indexOf(":") + 1)),
                                            channel
                                    );
                                }
                            });
                }
            } catch (Exception e) {
                log.warn("发送心跳到 {} 失败: {}", key, e.getMessage());
            }
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : lastHeartbeatTime.entrySet()) {
            String key = entry.getKey();
            long lastTime = entry.getValue();
            Long lastResponse = lastHeartbeatResponseTime.get(key);

            if (now - lastTime > HEARTBEAT_TIMEOUT) {
                if (lastResponse == null || now - lastResponse > HEARTBEAT_TIMEOUT) {
                    log.warn("心跳 {}, 超时标记为故障", key);
                    if (nettyRpcClient != null) {
                        nettyRpcClient.markFault(
                                key.substring(0, key.indexOf(":")),
                                Integer.parseInt(key.substring(key.indexOf(":") + 1))
                        );
                    }
                    lastHeartbeatTime.remove(key);
                    lastHeartbeatResponseTime.remove(key);
                    heartbeatResponseTimes.remove(key);
                }
            }
        }
    }

    public void addServer(String key) {
        lastHeartbeatTime.put(key, System.currentTimeMillis());
        lastHeartbeatResponseTime.put(key, System.currentTimeMillis());
    }

    public void removeServer(String key) {
        lastHeartbeatTime.remove(key);
        lastHeartbeatResponseTime.remove(key);
        heartbeatResponseTimes.remove(key);
    }

    public void onHeartbeatResponse(String key) {
        long now = System.currentTimeMillis();
        Long lastSend = lastHeartbeatTime.get(key);
        if (lastSend != null) {
            long responseTime = now - lastSend;
            heartbeatResponseTimes.put(key, responseTime);
        }
        lastHeartbeatResponseTime.put(key, now);
    }

    public Long getHeartbeatResponseTime(String key) {
        return heartbeatResponseTimes.get(key);
    }

    public Map<String, Long> getAllHeartbeatResponseTimes() {
        return new ConcurrentHashMap<>(heartbeatResponseTimes);
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
