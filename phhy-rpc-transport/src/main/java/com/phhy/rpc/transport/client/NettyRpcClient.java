package com.phhy.rpc.transport.client;

import com.phhy.rpc.common.constant.RpcConstant;
import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.exception.RpcException;
import com.phhy.rpc.common.exception.RpcTimeoutException;
import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.protocol.codec.RpcMessageDecoder;
import com.phhy.rpc.protocol.codec.RpcMessageEncoder;
import com.phhy.rpc.protocol.model.RpcMessage;
import com.phhy.rpc.transport.codec.Http2StreamFrameToByteBufDecoder;
import com.phhy.rpc.transport.codec.RpcMessageToHttp2FrameEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.*;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.AttributeKey;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class NettyRpcClient {

    public static final AttributeKey<String> SERVER_KEY_ATTR = AttributeKey.valueOf("serverKey");
    private static final AsciiString METHOD_POST = AsciiString.cached("POST");
    private static final AsciiString PATH_RPC = AsciiString.cached("/rpc");
    private static final AsciiString SCHEME_HTTPS = AsciiString.cached("https");

    private final EventLoopGroup workerGroup;
    private final ChannelManager channelManager;
    private final UnprocessedRequests unprocessedRequests;
    private final RpcMessageEncoder rpcMessageEncoder = new RpcMessageEncoder();
    private final AtomicInteger requestIdGenerator = new AtomicInteger(0);
    private final SerializeType serializeType;
    private ClientHeartbeatManager heartbeatManager;

    public NettyRpcClient(SerializeType serializeType) {
        this.serializeType = serializeType;
        this.workerGroup = new NioEventLoopGroup();
        this.channelManager = new ChannelManager();
        this.unprocessedRequests = new UnprocessedRequests();
    }

    public void setHeartbeatManager(ClientHeartbeatManager heartbeatManager) {
        this.heartbeatManager = heartbeatManager;
    }

    public ClientHeartbeatManager getHeartbeatManager() {
        return heartbeatManager;
    }

    public Http2StreamChannelBootstrap createStreamBootstrap(Channel parentChannel) {
        Http2StreamChannelBootstrap bootstrap = new Http2StreamChannelBootstrap(parentChannel);
        bootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ensureStreamPipeline(ch);
            }
        });
        return bootstrap;
    }

    public void ensureStreamPipeline(Channel streamChannel) {
        ChannelPipeline pipeline = streamChannel.pipeline();
        if (pipeline.get(Http2StreamFrameToByteBufDecoder.class) == null) {
            pipeline.addLast(new Http2StreamFrameToByteBufDecoder());
        }
        if (pipeline.get(RpcMessageDecoder.class) == null) {
            pipeline.addLast(new RpcMessageDecoder());
        }
        if (pipeline.get(RpcMessageToHttp2FrameEncoder.class) == null) {
            pipeline.addLast(new RpcMessageToHttp2FrameEncoder());
        }
        if (pipeline.get(RpcClientHandler.class) == null) {
            pipeline.addLast(new RpcClientHandler(unprocessedRequests, this));
        }
    }

    public RpcResponse sendRequest(RpcRequest request, String host, int port) {
        String key = host + ":" + port;
        Channel channel = getOrCreateChannel(host, port, key);

        long requestId = requestIdGenerator.getAndIncrement();

        RpcMessage rpcMessage = RpcMessage.builder()
                .version(RpcConstant.VERSION)
                .msgType(MsgType.REQUEST)
                .serializeType(serializeType)
                .requestId(requestId)
                .body(request)
                .build();

        java.util.concurrent.CompletableFuture<RpcResponse> future = new java.util.concurrent.CompletableFuture<>();
        unprocessedRequests.put(requestId, future);

        Http2StreamChannelBootstrap streamBootstrap = createStreamBootstrap(channel);
        streamBootstrap.open().addListener((GenericFutureListener<io.netty.util.concurrent.Future<Http2StreamChannel>>) streamFuture -> {
            if (streamFuture.isSuccess()) {
                Http2StreamChannel streamChannel = streamFuture.getNow();
                ensureStreamPipeline(streamChannel);
                writeRpcMessage(streamChannel, rpcMessage).addListener((ChannelFutureListener) writeFuture -> {
                    if (!writeFuture.isSuccess()) {
                        unprocessedRequests.remove(requestId);
                        log.error("发送请求失败至 {}: {}", key, writeFuture.cause().getMessage(), writeFuture.cause());
                        future.completeExceptionally(new RpcException("发送请求失败至 " + key, writeFuture.cause()));
                        streamChannel.close();
                    }
                });
            } else {
                unprocessedRequests.remove(requestId);
                log.error("创建流通道失败至 {}: {}", key, streamFuture.cause().getMessage(), streamFuture.cause());
                future.completeExceptionally(new RpcException("创建流通道失败至 " + key, streamFuture.cause()));
            }
        });

        long timeout = request.getTimeout() > 0 ? request.getTimeout() : RpcConstant.DEFAULT_TIMEOUT;
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            unprocessedRequests.remove(requestId);
            throw new RpcTimeoutException("请求超时后 " + timeout + "毫秒用于 " + key);
        } catch (Exception e) {
            unprocessedRequests.remove(requestId);
            throw new RpcException("请求失败 " + key, e);
        }
    }

    public ChannelFuture writeRpcMessage(Http2StreamChannel streamChannel, RpcMessage rpcMessage) {
        ByteBuf payload = streamChannel.alloc().buffer();
        try {
            rpcMessageEncoder.encode(null, rpcMessage, payload);
        } catch (Exception e) {
            payload.release();
            throw new RpcException("编码 RPC 消息失败", e);
        }

        Http2Headers headers = new DefaultHttp2Headers()
                .method(METHOD_POST)
                .path(PATH_RPC)
                .scheme(SCHEME_HTTPS);

        streamChannel.write(new DefaultHttp2HeadersFrame(headers, false));
        return streamChannel.writeAndFlush(new DefaultHttp2DataFrame(payload, true));
    }

    private Channel getOrCreateChannel(String host, int port, String key) {
        if (channelManager.isFault(key)) {
            channelManager.removeChannel(key);
        }

        Channel channel = channelManager.getChannel(key);
        if (channel != null) {
            return channel;
        }

        try {
            channel = createChannel(host, port);
            channel.attr(SERVER_KEY_ATTR).set(key);
            channel.closeFuture().addListener((ChannelFutureListener) closeFuture -> {
                unprocessedRequests.completeAllExceptionally(
                        new RuntimeException("Connection closed: " + key));
            });
            channelManager.putChannel(key, channel);
            ClientHeartbeatManager heartbeatManager = getHeartbeatManager();
            if (heartbeatManager != null) {
                heartbeatManager.addServer(key);
            }
            return channel;
        } catch (Exception e) {
            channelManager.markFault(key);
            throw new RpcException("Failed to connect to " + key, e);
        }
    }

    private Channel createChannel(String host, int port) throws Exception {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();

                        SslContext sslContext = SslContextBuilder.forClient()
                                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
                                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                .applicationProtocolConfig(new ApplicationProtocolConfig(
                                        ApplicationProtocolConfig.Protocol.ALPN,
                                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                                        ApplicationProtocolNames.HTTP_2,
                                        ApplicationProtocolNames.HTTP_1_1))
                                .build();

                        pipeline.addLast(sslContext.newHandler(ch.alloc(), host, port));

                        Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forClient();
                        frameCodecBuilder.initialSettings(new Http2Settings()
                                .maxConcurrentStreams(100)
                                .initialWindowSize(1048576));
                        pipeline.addLast(frameCodecBuilder.build());

                        pipeline.addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) throws Exception {
                                ch.pipeline()
                                        .addLast(new Http2StreamFrameToByteBufDecoder())
                                        .addLast(new RpcMessageDecoder())
                                        .addLast(new RpcMessageToHttp2FrameEncoder())
                                        .addLast(new RpcClientHandler(unprocessedRequests, NettyRpcClient.this));
                            }
                        }));
                    }
                });

        ChannelFuture future = bootstrap.connect(host, port).sync();
        return future.channel();
    }

    public ChannelManager getChannelManager() {
        return channelManager;
    }

    public UnprocessedRequests getUnprocessedRequests() {
        return unprocessedRequests;
    }

    public void shutdown() {
        channelManager.closeAll();
        workerGroup.shutdownGracefully();
    }
}
