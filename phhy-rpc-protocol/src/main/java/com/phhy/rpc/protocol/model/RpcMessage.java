package com.phhy.rpc.protocol.model;

import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import io.netty.util.Recycler;
import lombok.Data;

/**
 * RPC 协议消息模型
 * 使用 Netty Recycler 实现对象池，减少 GC 压力
 */
@Data
public class RpcMessage {

    private static final Recycler<RpcMessage> RECYCLER = new Recycler<RpcMessage>() {
        @Override
        protected RpcMessage newObject(Handle<RpcMessage> handle) {
            return new RpcMessage(handle);
        }
    };

    private final Recycler.Handle<RpcMessage> handle;

    private byte version;
    private MsgType msgType;
    private SerializeType serializeType;
    private long requestId;
    private Object body;

    private RpcMessage(Recycler.Handle<RpcMessage> handle) {
        this.handle = handle;
    }

    public RpcMessage() {
        this.handle = null;
    }

    public static RpcMessage newInstance() {
        return RECYCLER.get();
    }

    public void recycle() {
        if (handle != null) {
            version = 0;
            msgType = null;
            serializeType = null;
            requestId = 0L;
            body = null;
            handle.recycle(this);
        }
    }

    public boolean isHeartbeat() {
        return msgType == MsgType.HEARTBEAT_REQ || msgType == MsgType.HEARTBEAT_RESP;
    }
}
