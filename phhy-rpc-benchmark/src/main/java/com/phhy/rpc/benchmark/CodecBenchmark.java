package com.phhy.rpc.benchmark;

import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.protocol.codec.RpcMessageDecoder;
import com.phhy.rpc.protocol.codec.RpcMessageEncoder;
import com.phhy.rpc.protocol.model.RpcMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 协议编解码吞吐量基准测试
 * 测试 RpcMessageEncoder / RpcMessageDecoder 的编码解码性能
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 2, jvmArgs = {"-Xms1G", "-Xmx1G"})
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
public class CodecBenchmark {

    private RpcMessage requestMessage;
    private RpcMessage responseMessage;
    private RpcMessage heartbeatMessage;

    private RpcMessageEncoder encoder;
    private RpcMessageDecoder decoder;
    private EmbeddedChannel channel;

    private ByteBuf requestBuf;
    private ByteBuf responseBuf;
    private ByteBuf heartbeatBuf;

    @Setup
    public void setup() {
        encoder = new RpcMessageEncoder();
        decoder = new RpcMessageDecoder();
        channel = new EmbeddedChannel(encoder, decoder);

        RpcRequest rpcRequest = RpcRequest.builder()
                .interfaceName("com.phhy.rpc.example.api.HelloService")
                .methodName("sayHello")
                .parameterTypes(new Class<?>[]{String.class})
                .parameters(new Object[]{"benchmark"})
                .timeout(5000)
                .build();

        RpcResponse rpcResponse = RpcResponse.success(999L, "Hello, benchmark!");

        requestMessage = new RpcMessage();
        requestMessage.setVersion((byte) 1);
        requestMessage.setMsgType(MsgType.REQUEST);
        requestMessage.setSerializeType(SerializeType.KRYO);
        requestMessage.setRequestId(10086L);
        requestMessage.setBody(rpcRequest);

        responseMessage = new RpcMessage();
        responseMessage.setVersion((byte) 1);
        responseMessage.setMsgType(MsgType.RESPONSE);
        responseMessage.setSerializeType(SerializeType.KRYO);
        responseMessage.setRequestId(10086L);
        responseMessage.setBody(rpcResponse);

        heartbeatMessage = new RpcMessage();
        heartbeatMessage.setVersion((byte) 1);
        heartbeatMessage.setMsgType(MsgType.HEARTBEAT_REQ);
        heartbeatMessage.setSerializeType(SerializeType.KRYO);
        heartbeatMessage.setRequestId(0L);
        heartbeatMessage.setBody(null);

        requestBuf = encodeToBuf(requestMessage);
        responseBuf = encodeToBuf(responseMessage);
        heartbeatBuf = encodeToBuf(heartbeatMessage);
    }

    private ByteBuf encodeToBuf(RpcMessage msg) {
        EmbeddedChannel ch = new EmbeddedChannel(new RpcMessageEncoder());
        ch.writeOutbound(msg);
        return ch.readOutbound();
    }

    @TearDown
    public void tearDown() {
        if (requestBuf != null) requestBuf.release();
        if (responseBuf != null) responseBuf.release();
        if (heartbeatBuf != null) heartbeatBuf.release();
        channel.close();
    }

    @Benchmark
    public void encodeRequest(Blackhole bh) {
        EmbeddedChannel ch = new EmbeddedChannel(new RpcMessageEncoder());
        ch.writeOutbound(requestMessage);
        bh.consume(ch.readOutbound());
    }

    @Benchmark
    public void encodeResponse(Blackhole bh) {
        EmbeddedChannel ch = new EmbeddedChannel(new RpcMessageEncoder());
        ch.writeOutbound(responseMessage);
        bh.consume(ch.readOutbound());
    }

    @Benchmark
    public void encodeHeartbeat(Blackhole bh) {
        EmbeddedChannel ch = new EmbeddedChannel(new RpcMessageEncoder());
        ch.writeOutbound(heartbeatMessage);
        bh.consume(ch.readOutbound());
    }

    @Benchmark
    public void decodeRequest(Blackhole bh) {
        EmbeddedChannel ch = new EmbeddedChannel(new RpcMessageDecoder());
        ByteBuf buf = requestBuf.retainedSlice();
        ch.writeInbound(buf);
        bh.consume(ch.readInbound());
    }

    @Benchmark
    public void decodeResponse(Blackhole bh) {
        EmbeddedChannel ch = new EmbeddedChannel(new RpcMessageDecoder());
        ByteBuf buf = responseBuf.retainedSlice();
        ch.writeInbound(buf);
        bh.consume(ch.readInbound());
    }

    @Benchmark
    public void decodeHeartbeat(Blackhole bh) {
        EmbeddedChannel ch = new EmbeddedChannel(new RpcMessageDecoder());
        ByteBuf buf = heartbeatBuf.retainedSlice();
        ch.writeInbound(buf);
        bh.consume(ch.readInbound());
    }
}
