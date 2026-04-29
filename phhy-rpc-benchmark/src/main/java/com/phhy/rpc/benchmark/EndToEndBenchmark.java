package com.phhy.rpc.benchmark;

import com.phhy.rpc.benchmark.server.BenchmarkTcpServer;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.example.api.HelloService;
import com.phhy.rpc.example.impl.HelloServiceImpl;
import com.phhy.rpc.loadbalance.impl.RoundRobinBalancer;
import com.phhy.rpc.proxy.RpcClientProxy;
import com.phhy.rpc.proxy.filter.FilterChain;
import com.phhy.rpc.registry.api.ServiceDiscovery;
import com.phhy.rpc.registry.cache.ServiceCacheManager;
import com.phhy.rpc.transport.client.TcpRpcClient;
import org.openjdk.jmh.annotations.*;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 端到端（End-to-End）RPC 调用吞吐量基准测试
 * 使用自定义 TCP 协议（无 HTTP/2、无 TLS）
 *
 * 测试完整链路：客户端代理 → 序列化 → Netty TCP 发送 → 服务端处理 → 反序列化 → 返回响应
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(1)
@State(Scope.Benchmark)
public class EndToEndBenchmark {

    @Param({"1", "4", "8"})
    private int connections;

    @Param({"128", "1024", "4096"})
    private int bodySize;

    @Param({"JSON", "KRYO"})
    private String serializerType;

    private BenchmarkTcpServer server;
    private TcpRpcClient tcpRpcClient;
    private HelloService helloService;

    private String largePayload;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        StringBuilder sb = new StringBuilder(bodySize);
        while (sb.length() < bodySize) {
            sb.append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789");
        }
        largePayload = sb.substring(0, bodySize);

        int port = 19527;
        SerializeType serializeType = SerializeType.valueOf(serializerType);

        server = new BenchmarkTcpServer(port, serializeType)
                .publishService(HelloService.class, new HelloServiceImpl());
        server.start();

        Thread.sleep(500);

        tcpRpcClient = new TcpRpcClient(serializeType);

        ServiceDiscovery directDiscovery = serviceName -> Collections.singletonList(
                ServiceInstance.builder()
                        .serviceName(serviceName)
                        .host("127.0.0.1")
                        .port(port)
                        .healthy(true)
                        .build()
        );
        ServiceCacheManager cacheManager = new ServiceCacheManager(directDiscovery);

        // 预热连接
        for (int i = 0; i < connections; i++) {
            tcpRpcClient.warmup("127.0.0.1", port);
        }

        RpcClientProxy proxy = new RpcClientProxy(
                HelloService.class,
                tcpRpcClient,
                cacheManager,
                new RoundRobinBalancer(),
                new FilterChain(),
                10000
        );
        helloService = proxy.getProxy();

        Thread.sleep(connections * 100L + 300);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (tcpRpcClient != null) {
            tcpRpcClient.shutdown();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    @Benchmark
    @Threads(1)
    public String endToEndSync() {
        return helloService.hello(largePayload);
    }

    @Benchmark
    @Threads(4)
    public String endToEndSync4Threads() {
        return helloService.hello(largePayload);
    }

    @Benchmark
    @Threads(16)
    public String endToEndSync16Threads() {
        return helloService.hello(largePayload);
    }
}
