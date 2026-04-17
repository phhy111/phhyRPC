package com.phhy.rpc.transport.server;

import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.protocol.codec.RpcMessageDecoder;
import com.phhy.rpc.transport.codec.Http2StreamFrameToByteBufDecoder;
import com.phhy.rpc.transport.codec.RpcMessageToHttp2FrameEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class NettyRpcServer {

    private final int port;
    private final Map<String, Object> serviceRegistry;
    private final SerializeType serializeType;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ExecutorService businessExecutor;
    private Channel serverChannel;

    public NettyRpcServer(int port, Map<String, Object> serviceRegistry, SerializeType serializeType) {
        this.port = port;
        this.serviceRegistry = serviceRegistry;
        this.serializeType = serializeType;
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
                new ThreadPoolExecutor.CallerRunsPolicy());

        SelfSignedCertificate ssc = new SelfSignedCertificate();
        SslContext sslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1))
                .build();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();

                        pipeline.addLast(sslContext.newHandler(ch.alloc()));

                        Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forServer();
                        frameCodecBuilder.initialSettings(new Http2Settings()
                                .maxConcurrentStreams(100)
                                .initialWindowSize(1048576));
                        pipeline.addLast(frameCodecBuilder.build());

                        pipeline.addLast(new Http2MultiplexHandler(
                                new ChannelInitializer<Channel>() {
                                    @Override
                                    protected void initChannel(Channel ch) {
                                        ch.pipeline()
                                                .addLast(new Http2StreamFrameToByteBufDecoder())
                                                .addLast(new RpcMessageDecoder())
                                                .addLast(new RpcMessageToHttp2FrameEncoder())
                                                .addLast(new ServerHeartbeatHandler())
                                                .addLast(new RpcServerHandler(serviceRegistry, businessExecutor));
                                    }
                                }));
                    }
                });

        ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();
        log.info("RPC 服务器已在端口启动 {}", port);
    }

    public void shutdown() {
        log.info("正在关闭 RPC 服务器...");
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
        log.info("RPC 服务器已关闭");
    }
}
