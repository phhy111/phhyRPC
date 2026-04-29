package com.phhy.rpc.transport.server;

import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.protocol.codec.RpcMessageDecoder;
import com.phhy.rpc.protocol.codec.RpcMessageEncoder;
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
 * 基于自定义 TCP 协议的 RPC 服务端（无 HTTP/2、无 TLS）
 * Pipeline: RpcMessageDecoder → RpcMessageEncoder → ServerHeartbeatHandler → RpcServerHandler
 */
@Slf4j
public class TcpRpcServer {

    private final int port;
    private final Map<String, Object> serviceRegistry;
    private final SerializeType serializeType;
    private final boolean authRequired;
    private final boolean sensitiveDataProcessing;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ThreadPoolExecutor businessExecutor;
    private Channel serverChannel;

    public TcpRpcServer(int port, Map<String, Object> serviceRegistry, SerializeType serializeType) {
        this(port, serviceRegistry, serializeType, false, false);
    }

    public TcpRpcServer(int port,
                        Map<String, Object> serviceRegistry,
                        SerializeType serializeType,
                        boolean authRequired,
                        boolean sensitiveDataProcessing) {
        this.port = port;
        this.serviceRegistry = serviceRegistry;
        this.serializeType = serializeType;
        this.authRequired = authRequired;
        this.sensitiveDataProcessing = sensitiveDataProcessing;
    }

    public ThreadPoolExecutor getBusinessExecutor() {
        return businessExecutor;
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
                new ThreadFactory() {
                    private int count = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "rpc-business-" + (count++));
                    }
                },
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
                                authRequired,
                                sensitiveDataProcessing));
                    }
                });

        ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();
        log.info("TCP RPC 服务器已在端口启动 {}", port);
    }

    public void shutdown() {
        log.info("正在关闭 TCP RPC 服务器...");
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
        log.info("TCP RPC 服务器已关闭");
    }
}
