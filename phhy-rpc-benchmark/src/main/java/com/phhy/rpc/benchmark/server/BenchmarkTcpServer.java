package com.phhy.rpc.benchmark.server;

import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.protocol.codec.RpcMessageDecoder;
import com.phhy.rpc.protocol.codec.RpcMessageEncoder;
import com.phhy.rpc.transport.server.RpcServerHandler;
import com.phhy.rpc.transport.server.ServerHeartbeatHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 用于基准测试的 TCP RPC 服务端（无 HTTP/2、无 TLS、无 Nacos）
 */
@Slf4j
public class BenchmarkTcpServer {

    private final int port;
    private final SerializeType serializeType;
    private final Map<String, Object> serviceRegistry = new ConcurrentHashMap<>();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ThreadPoolExecutor businessExecutor;
    private Channel serverChannel;

    public BenchmarkTcpServer(int port, SerializeType serializeType) {
        this.port = port;
        this.serializeType = serializeType;
    }

    public BenchmarkTcpServer publishService(Class<?> interfaceClass, Object impl) {
        serviceRegistry.put(interfaceClass.getName(), impl);
        return this;
    }

    public void start() throws Exception {
        int cpuCores = Runtime.getRuntime().availableProcessors();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(cpuCores * 2);

        businessExecutor = new ThreadPoolExecutor(
                cpuCores,
                cpuCores * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                r -> new Thread(r, "rpc-business-" + ThreadLocalRandom.current().nextInt(1000)),
                new ThreadPoolExecutor.AbortPolicy());

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new RpcMessageDecoder());
                        pipeline.addLast(new RpcMessageEncoder());
                        pipeline.addLast(new ServerHeartbeatHandler());
                        pipeline.addLast(new RpcServerHandler(
                                serviceRegistry,
                                businessExecutor,
                                false,
                                false));
                    }
                });

        ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();
        log.info("Benchmark TCP RPC 服务器已在端口启动 {}", port);
    }

    public void shutdown() {
        log.info("正在关闭 Benchmark TCP RPC 服务器...");
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (businessExecutor != null) {
            businessExecutor.shutdown();
            try {
                if (!businessExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    businessExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                businessExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("Benchmark TCP RPC 服务器已关闭");
    }
}
