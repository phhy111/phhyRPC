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
import com.phhy.rpc.transport.pool.ConnectionPoolConfig;
import com.phhy.rpc.transport.pool.SmartConnectionPool;
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
import io.netty.handler.codec.http2.Http2SecurityUtil;
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
    private final SmartConnectionPool connectionPool;
    private final UnprocessedRequests unprocessedRequests;
    private final RpcMessageEncoder rpcMessageEncoder = new RpcMessageEncoder();
    private final AtomicInteger requestIdGenerator = new AtomicInteger(0);
    private final SerializeType serializeType;
    private ClientHeartbeatManager heartbeatManager;

    public NettyRpcClient(SerializeType serializeType) {
        this(serializeType, null);
    }

    public NettyRpcClient(SerializeType serializeType, ConnectionPoolConfig poolConfig) {
        this.serializeType = serializeType;
        this.workerGroup = new NioEventLoopGroup();
        this.connectionPool = new SmartConnectionPool(poolConfig, workerGroup);
        this.unprocessedRequests = new UnprocessedRequests();
    }

    public void setHeartbeatManager(ClientHeartbeatManager heartbeatManager) {
        this.heartbeatManager = heartbeatManager;
    }

    public ClientHeartbeatManager getHeartbeatManager() {
        return heartbeatManager;
    }

    public SmartConnectionPool getConnectionPool() {
        return connectionPool;
    }

    public Http2StreamChannelBootstrap createStreamBootstrap(Channel parentChannel) {
        Http2StreamChannelBootstrap bootstrap = new Http2StreamChannelBootstrap(parentChannel);
        bootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
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
        long startTime = System.currentTimeMillis();
        Channel channel = null;
        boolean acquired = false;

        try {
            channel = connectionPool.acquireChannel(host, port);
            acquired = true;

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
                RpcResponse response = future.get(timeout, TimeUnit.MILLISECONDS);
                long responseTime = System.currentTimeMillis() - startTime;
                connectionPool.getMetrics(key).recordSuccess(responseTime);
                return response;
            } catch (java.util.concurrent.TimeoutException e) {
                unprocessedRequests.remove(requestId);
                connectionPool.getMetrics(key).recordFailure();
                throw new RpcTimeoutException("请求超时后 " + timeout + "毫秒用于 " + key);
            } catch (Exception e) {
                unprocessedRequests.remove(requestId);
                connectionPool.getMetrics(key).recordFailure();
                throw new RpcException("请求失败 " + key, e);
            }
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            connectionPool.getMetrics(key).recordFailure();
            throw new RpcException("获取连接或发送请求失败 " + key, e);
        } finally {
            if (acquired && channel != null) {
                connectionPool.releaseChannel(host, port, channel);
            }
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

    public void warmup(String host, int port) {
        connectionPool.warmup(host, port);
    }

    public void markFault(String host, int port) {
        connectionPool.markFault(host, port);
        if (heartbeatManager != null) {
            heartbeatManager.removeServer(host + ":" + port);
        }
    }

    public UnprocessedRequests getUnprocessedRequests() {
        return unprocessedRequests;
    }

    public void shutdown() {
        connectionPool.closeAll();
        workerGroup.shutdownGracefully();
    }
}
