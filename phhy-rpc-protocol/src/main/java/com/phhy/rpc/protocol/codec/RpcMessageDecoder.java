package com.phhy.rpc.protocol.codec;

import com.phhy.rpc.common.constant.RpcConstant;
import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.exception.RpcException;
import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.protocol.model.RpcMessage;
import com.phhy.rpc.common.serialization.Serializer;
import com.phhy.rpc.common.serialization.SerializerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * RPC 协议解码器
 * 优化点：使用 RpcMessage 对象池（Recycler）减少 GC
 */
@Slf4j
public class RpcMessageDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < RpcConstant.HEADER_LENGTH) {
            return;
        }

        in.markReaderIndex();

        byte[] magic = new byte[RpcConstant.MAGIC.length];
        in.readBytes(magic);
        if (!Arrays.equals(magic, RpcConstant.MAGIC)) {
            in.resetReaderIndex();
            throw new RpcException("无效的魔术数字： " + Arrays.toString(magic));
        }

        byte version = in.readByte();
        byte msgTypeCode = in.readByte();
        MsgType msgType = MsgType.fromCode(msgTypeCode);
        byte serializeTypeCode = in.readByte();
        SerializeType serializeType = SerializeType.fromCode(serializeTypeCode);
        long requestId = in.readLong();
        int bodyLen = in.readInt();

        if (in.readableBytes() < bodyLen) {
            in.resetReaderIndex();
            return;
        }

        byte[] bodyBytes = new byte[bodyLen];
        if (bodyLen > 0) {
            in.readBytes(bodyBytes);
        }

        Object body = null;
        if (bodyLen > 0) {
            Serializer serializer = SerializerFactory.getSerializer(serializeType);
            if (msgType == MsgType.REQUEST) {
                body = serializer.deserialize(bodyBytes, RpcRequest.class);
            } else if (msgType == MsgType.RESPONSE) {
                body = serializer.deserialize(bodyBytes, RpcResponse.class);
            }
        }

        // 使用对象池获取 RpcMessage
        RpcMessage rpcMessage = RpcMessage.newInstance();
        rpcMessage.setVersion(version);
        rpcMessage.setMsgType(msgType);
        rpcMessage.setSerializeType(serializeType);
        rpcMessage.setRequestId(requestId);
        rpcMessage.setBody(body);

        out.add(rpcMessage);

        log.debug("已解码的 RpcMessage: type={}, serializeType={}, requestId={}, bodyLen={}",
                msgType, serializeType, requestId, bodyLen);
    }
}
