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

@Slf4j
public class RpcMessageDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 检查协议头是否完整（至少需要 HEADER_LENGTH 字节）
        if (in.readableBytes() < RpcConstant.HEADER_LENGTH) {
            return;
        }

        // 标记当前读索引，用于半包回退
        in.markReaderIndex();

        // 读取并校验魔数
        byte[] magic = new byte[RpcConstant.MAGIC.length];
        in.readBytes(magic);
        if (!Arrays.equals(magic, RpcConstant.MAGIC)) {
            in.resetReaderIndex();
            throw new RpcException("Invalid magic number: " + Arrays.toString(magic));
        }

        // 读取版本号
        byte version = in.readByte();
        // 读取消息类型
        byte msgTypeCode = in.readByte();
        MsgType msgType = MsgType.fromCode(msgTypeCode);
        // 读取序列化类型
        byte serializeTypeCode = in.readByte();
        SerializeType serializeType = SerializeType.fromCode(serializeTypeCode);
        // 读取请求ID
        long requestId = in.readLong();
        // 读取消息体长度
        int bodyLen = in.readInt();

        // 检查消息体是否完整
        if (in.readableBytes() < bodyLen) {
            // 半包情况：重置读索引，等待更多数据到达
            in.resetReaderIndex();
            return;
        }

        // 读取消息体
        byte[] bodyBytes = new byte[bodyLen];
        if (bodyLen > 0) {
            in.readBytes(bodyBytes);
        }

        // 反序列化消息体
        Object body = null;
        if (bodyLen > 0) {
            Serializer serializer = SerializerFactory.getSerializer(serializeType);
            // 根据消息类型决定反序列化的目标类
            if (msgType == MsgType.REQUEST) {
                body = serializer.deserialize(bodyBytes, RpcRequest.class);
            } else if (msgType == MsgType.RESPONSE) {
                body = serializer.deserialize(bodyBytes, RpcResponse.class);
            }
        }

        // 构造 RpcMessage
        RpcMessage rpcMessage = RpcMessage.builder()
                .version(version)
                .msgType(msgType)
                .serializeType(serializeType)
                .requestId(requestId)
                .body(body)
                .build();

        out.add(rpcMessage);

        log.debug("Decoded RpcMessage: type={}, serializeType={}, requestId={}, bodyLen={}",
                msgType, serializeType, requestId, bodyLen);
    }
}
