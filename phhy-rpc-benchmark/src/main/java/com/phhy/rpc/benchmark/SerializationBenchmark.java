package com.phhy.rpc.benchmark;

import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.common.serialization.Serializer;
import com.phhy.rpc.common.serialization.SerializerFactory;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * 序列化吞吐量基准测试
 * 对比 JSON 与 Kryo 两种序列化方式在 RpcRequest/RpcResponse 上的性能表现
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 2, jvmArgs = {"-Xms1G", "-Xmx1G"})
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
public class SerializationBenchmark {

    private Serializer jsonSerializer;
    private Serializer kryoSerializer;

    private RpcRequest rpcRequest;
    private RpcResponse rpcResponse;

    private byte[] jsonRequestBytes;
    private byte[] kryoRequestBytes;
    private byte[] jsonResponseBytes;
    private byte[] kryoResponseBytes;

    @Setup
    public void setup() {
        jsonSerializer = SerializerFactory.getSerializer(SerializeType.JSON);
        kryoSerializer = SerializerFactory.getSerializer(SerializeType.KRYO);

        rpcRequest = RpcRequest.builder()
                .interfaceName("com.phhy.rpc.example.api.HelloService")
                .methodName("sayHello")
                .parameterTypes(new Class<?>[]{String.class, int.class})
                .parameters(new Object[]{"world", 123})
                .timeout(5000)
                .authToken("Bearer-xxx-yyy-zzz")
                .build();

        rpcResponse = RpcResponse.success(10086L, "Hello, world!");

        jsonRequestBytes = jsonSerializer.serialize(rpcRequest);
        kryoRequestBytes = kryoSerializer.serialize(rpcRequest);
        jsonResponseBytes = jsonSerializer.serialize(rpcResponse);
        kryoResponseBytes = kryoSerializer.serialize(rpcResponse);
    }

    // ==================== JSON 序列化 ====================

    @Benchmark
    public void jsonSerializeRequest(Blackhole bh) {
        bh.consume(jsonSerializer.serialize(rpcRequest));
    }

    @Benchmark
    public void jsonSerializeResponse(Blackhole bh) {
        bh.consume(jsonSerializer.serialize(rpcResponse));
    }

    @Benchmark
    public void jsonDeserializeRequest(Blackhole bh) {
        bh.consume(jsonSerializer.deserialize(jsonRequestBytes, RpcRequest.class));
    }

    @Benchmark
    public void jsonDeserializeResponse(Blackhole bh) {
        bh.consume(jsonSerializer.deserialize(jsonResponseBytes, RpcResponse.class));
    }

    // ==================== Kryo 序列化 ====================

    @Benchmark
    public void kryoSerializeRequest(Blackhole bh) {
        bh.consume(kryoSerializer.serialize(rpcRequest));
    }

    @Benchmark
    public void kryoSerializeResponse(Blackhole bh) {
        bh.consume(kryoSerializer.serialize(rpcResponse));
    }

    @Benchmark
    public void kryoDeserializeRequest(Blackhole bh) {
        bh.consume(kryoSerializer.deserialize(kryoRequestBytes, RpcRequest.class));
    }

    @Benchmark
    public void kryoDeserializeResponse(Blackhole bh) {
        bh.consume(kryoSerializer.deserialize(kryoResponseBytes, RpcResponse.class));
    }
}
