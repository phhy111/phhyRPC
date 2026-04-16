package com.phhy.rpc.transport.client;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ChannelManager {

    // 连接通道缓存
    private final Map<String, Channel> channelCache = new ConcurrentHashMap<>();
    // 故障标记
    private final Map<String, Boolean> faultMarkers = new ConcurrentHashMap<>();

    public Channel getChannel(String key) {
        Channel channel = channelCache.get(key);
        if (channel != null && channel.isActive()) {
            return channel;
        }
        // 通道失效，移除缓存
        if (channel != null) {
            channelCache.remove(key);
        }
        return null;
    }

    public void putChannel(String key, Channel channel) {
        channelCache.put(key, channel);
        faultMarkers.remove(key);
        log.info("Channel cached for key: {}", key);
    }

    public void markFault(String key) {
        faultMarkers.put(key, true);
        Channel channel = channelCache.remove(key);
        if (channel != null && channel.isActive()) {
            channel.close();
        }
        log.warn("Channel marked as fault for key: {}", key);
    }

    public boolean isFault(String key) {
        return faultMarkers.getOrDefault(key, false);
    }

    public void removeChannel(String key) {
        channelCache.remove(key);
        faultMarkers.remove(key);
    }

    public void closeAll() {
        for (Map.Entry<String, Channel> entry : channelCache.entrySet()) {
            Channel channel = entry.getValue();
            if (channel.isActive()) {
                channel.close();
            }
        }
        channelCache.clear();
        faultMarkers.clear();
    }
}
