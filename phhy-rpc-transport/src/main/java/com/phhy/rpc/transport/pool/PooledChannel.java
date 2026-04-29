package com.phhy.rpc.transport.pool;

import io.netty.channel.Channel;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public class PooledChannel {

    private final Channel channel;
    private final String instanceKey;
    private final long createTime;
    private volatile long lastActiveTime;
    private volatile long lastHeartbeatResponseTime;
    private final AtomicBoolean inUse = new AtomicBoolean(false);
    private final AtomicBoolean fault = new AtomicBoolean(false);

    public PooledChannel(Channel channel, String instanceKey) {
        this.channel = channel;
        this.instanceKey = instanceKey;
        this.createTime = System.currentTimeMillis();
        this.lastActiveTime = this.createTime;
        this.lastHeartbeatResponseTime = this.createTime;
    }

    public boolean isActive() {
        return channel != null && channel.isActive() && !fault.get();
    }

    public boolean acquire() {
        return inUse.compareAndSet(false, true);
    }

    public void release() {
        inUse.set(false);
        lastActiveTime = System.currentTimeMillis();
    }

    public void markFault() {
        fault.set(true);
        if (channel != null && channel.isActive()) {
            channel.close();
        }
    }

    public boolean isInUse() {
        return inUse.get();
    }

    public long getIdleTime() {
        return System.currentTimeMillis() - lastActiveTime;
    }
}
