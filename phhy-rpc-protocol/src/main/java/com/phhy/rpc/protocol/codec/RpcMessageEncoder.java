package com.phhy.rpc.protocol.codec;

import com.phhy.rpc.common.constant.RpcConstant;
import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.protocol.model.RpcMessage;
import com.phhy.rpc.common.serialization.Serializer;
import com.phhy.rpc.common.serialization.SerializerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcMessageEncoder extends MessageToByteEncoder<RpcMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage msg, ByteBuf out) throws Exception {
        // 写入魔数 4B
        out.writeBytes(RpcConstant.MAGIC);
        // 写入版本号 1B
        out.writeByte(msg.getVersion());
        // 写入消息类型 1B
        out.writeByte(msg.getMsgType().getCode());
        // 写入序列化类型 1B
        out.writeByte(msg.getSerializeType().getCode());
        // 写入请求ID 8B（大端序）
        out.writeLong(msg.getRequestId());

        // 序列化消息体
        byte[] bodyBytes = new byte[0];
        if (msg.getBody() != null) {
            Serializer serializer = SerializerFactory.getSerializer(msg.getSerializeType());
            bodyBytes = serializer.serialize(msg.getBody());
        }
        // 写入消息体长度 4B（大端序）
        out.writeInt(bodyBytes.length);
        // 写入消息体
        if (bodyBytes.length > 0) {
            out.writeBytes(bodyBytes);
        }

        log.debug("Encoded RpcMessage: type={}, serializeType={}, requestId={}, bodyLen={}",
                msg.getMsgType(), msg.getSerializeType(), msg.getRequestId(), bodyBytes.length);
    }
}
