package com.phhy.rpc.protocol.model;

import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcMessage {

    private byte version;

    private MsgType msgType;

    private SerializeType serializeType;

    private long requestId;

    private Object body;

    public boolean isHeartbeat() {
        return msgType == MsgType.HEARTBEAT_REQ || msgType == MsgType.HEARTBEAT_RESP;
    }
}
