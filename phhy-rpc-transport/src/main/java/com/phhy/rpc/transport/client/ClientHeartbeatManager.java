package com.phhy.rpc.transport.client;

import com.phhy.rpc.common.constant.RpcConstant;
import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.protocol.model.RpcMessage;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ClientHeartbeatManager {

    // 每个服务端key对应一个最后心跳响应时间戳
    private final Map<String, Long> lastHeartbeatTime = new ConcurrentHashMap<>();
    private final ChannelManager channelManager;
    private final SerializeType serializeType;
    private final ScheduledExecutorService scheduler;

    // 心跳发送间隔（默认30秒）
    private static final long HEARTBEAT_INTERVAL = 30_000L;
    // 心跳超时阈值（默认60秒）
    private static final long HEARTBEAT_TIMEOUT = 60_000L;

    public ClientHeartbeatManager(ChannelManager channelManager, SerializeType serializeType) {
        this.channelManager = channelManager;
        this.serializeType = serializeType;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "client-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        // 定时发送心跳
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
        // 定时检测超时
        scheduler.scheduleAtFixedRate(this::checkTimeouts, HEARTBEAT_TIMEOUT, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
        log.info("Client heartbeat manager started");
    }

    private void sendHeartbeats() {
        for (Map.Entry<String, Long> entry : lastHeartbeatTime.entrySet()) {
            String key = entry.getKey();
            Channel channel = channelManager.getChannel(key);
            if (channel != null && channel.isActive()) {
                RpcMessage heartbeatMsg = RpcMessage.builder()
                        .version(RpcConstant.VERSION)
                        .msgType(MsgType.HEARTBEAT_REQ)
                        .serializeType(serializeType)
                        .requestId(System.currentTimeMillis())
                        .body(null)
                        .build();
                channel.writeAndFlush(heartbeatMsg).addListener(future -> {
                    if (!future.isSuccess()) {
                        log.warn("Failed to send heartbeat to {}", key);
                    }
                });
            }
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : lastHeartbeatTime.entrySet()) {
            String key = entry.getKey();
            long lastTime = entry.getValue();
            // 超过60秒未收到响应则判定为超时
            if (now - lastTime > HEARTBEAT_TIMEOUT) {
                log.warn("Heartbeat timeout for {}, marking as fault", key);
                // 故障转移：标记该通道为故障，从心跳列表中移除
                channelManager.markFault(key);
                lastHeartbeatTime.remove(key);
            }
        }
    }

    public void addServer(String key) {
        lastHeartbeatTime.put(key, System.currentTimeMillis());
    }

    public void removeServer(String key) {
        lastHeartbeatTime.remove(key);
    }

    // 收到HEARTBEAT_RESP时更新最后心跳响应时间戳
    public void onHeartbeatResponse(String key) {
        lastHeartbeatTime.put(key, System.currentTimeMillis());
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
