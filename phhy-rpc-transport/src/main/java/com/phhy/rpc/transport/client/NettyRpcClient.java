package com.phhy.rpc.transport.client;

import com.phhy.rpc.common.constant.RpcConstant;
import com.phhy.rpc.common.enums.MsgType;
import com.phhy.rpc.common.enums.SerializeType;
import com.phhy.rpc.common.exception.RpcException;
import com.phhy.rpc.common.exception.RpcTimeoutException;
import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.protocol.model.RpcMessage;
import com.phhy.rpc.common.serialization.Serializer;
import com.phhy.rpc.common.serialization.SerializerFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class NettyRpcClient {

    private final EventLoopGroup workerGroup;
    private final ChannelManager channelManager;
    private final UnprocessedRequests unprocessedRequests;
    private final AtomicInteger requestIdGenerator = new AtomicInteger(0);
    private final SerializeType serializeType;

    public NettyRpcClient(SerializeType serializeType) {
        this.serializeType = serializeType;
        this.workerGroup = new NioEventLoopGroup();
        this.channelManager = new ChannelManager();
        this.unprocessedRequests = new UnprocessedRequests();
    }

    public RpcResponse sendRequest(RpcRequest request, String host, int port) {
        String key = host + ":" + port;
        Channel channel = getOrCreateChannel(host, port, key);

        // 生成请求ID
        long requestId = requestIdGenerator.getAndIncrement();

        // 序列化请求
        Serializer serializer = SerializerFactory.getSerializer(serializeType);
        byte[] bodyBytes = serializer.serialize(request);

        // 构造RpcMessage
        RpcMessage rpcMessage = RpcMessage.builder()
                .version(RpcConstant.VERSION)
                .msgType(MsgType.REQUEST)
                .serializeType(serializeType)
                .requestId(requestId)
                .body(request)
                .build();

        // 注册CompletableFuture
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        unprocessedRequests.put(requestId, future);

        // 发送请求
        channel.writeAndFlush(rpcMessage).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                unprocessedRequests.remove(requestId);
                future.completeExceptionally(new RpcException("Failed to send request to " + key));
            }
        });

        // 同步等待响应（带超时控制）
        long timeout = request.getTimeout() > 0 ? request.getTimeout() : RpcConstant.DEFAULT_TIMEOUT;
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            unprocessedRequests.remove(requestId);
            throw new RpcTimeoutException("Request timeout after " + timeout + "ms for " + key);
        } catch (Exception e) {
            unprocessedRequests.remove(requestId);
            throw new RpcException("Request failed for " + key, e);
        }
    }

    private Channel getOrCreateChannel(String host, int port, String key) {
        // 检查故障标记
        if (channelManager.isFault(key)) {
            channelManager.removeChannel(key);
        }

        Channel channel = channelManager.getChannel(key);
        if (channel != null) {
            return channel;
        }

        // 创建新连接
        try {
            channel = createChannel(host, port);
            channelManager.putChannel(key, channel);
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

                        // 配置SSL/HTTP2
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

                        // 使用HTTP/2 FrameCodec
                        io.netty.handler.codec.http2.Http2FrameCodecBuilder frameCodecBuilder =
                                io.netty.handler.codec.http2.Http2FrameCodecBuilder.forClient();
                        frameCodecBuilder.initialSettings(new io.netty.handler.codec.http2.Http2Settings()
                                .maxConcurrentStreams(100)
                                .initialWindowSize(1048576));
                        pipeline.addLast(frameCodecBuilder.build());

                        pipeline.addLast(new io.netty.handler.codec.http2.Http2MultiplexHandler(
                                new SimpleChannelInboundHandler<io.netty.handler.codec.http2.Http2DataFrame>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, io.netty.handler.codec.http2.Http2DataFrame data) {
                                        ctx.fireChannelRead(data.content());
                                    }
                                }));

                        // 自定义协议编解码
                        pipeline.addLast(new com.phhy.rpc.protocol.codec.RpcMessageDecoder());
                        pipeline.addLast(new com.phhy.rpc.protocol.codec.RpcMessageEncoder());

                        // 业务处理器
                        pipeline.addLast(new RpcClientHandler(unprocessedRequests));
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
