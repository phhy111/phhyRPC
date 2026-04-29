package com.phhy.rpc.benchmark;

import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.loadbalance.api.LoadBalancer;
import com.phhy.rpc.loadbalance.impl.RoundRobinBalancer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 负载均衡吞吐量基准测试
 * 测试 RoundRobinBalancer 在不同实例数量下的选择性能
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 2, jvmArgs = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
public class LoadBalanceBenchmark {

    private LoadBalancer loadBalancer;

    @Param({"3", "10", "50", "100"})
    private int instanceCount;

    private List<ServiceInstance> instances;

    @Setup
    public void setup() {
        loadBalancer = new RoundRobinBalancer();
        List<ServiceInstance> list = new ArrayList<>(instanceCount);
        for (int i = 0; i < instanceCount; i++) {
            list.add(ServiceInstance.builder()
                    .serviceName("com.phhy.rpc.example.api.HelloService")
                    .host("192.168.1." + (i + 1))
                    .port(8080 + i)
                    .weight(1.0)
                    .healthy(true)
                    .build());
        }
        instances = Collections.unmodifiableList(list);
    }

    @Benchmark
    @Threads(1)
    public void selectSingleThread(Blackhole bh) {
        bh.consume(loadBalancer.select(instances));
    }

    @Benchmark
    @Threads(4)
    public void select4Threads(Blackhole bh) {
        bh.consume(loadBalancer.select(instances));
    }

    @Benchmark
    @Threads(16)
    public void select16Threads(Blackhole bh) {
        bh.consume(loadBalancer.select(instances));
    }
}
