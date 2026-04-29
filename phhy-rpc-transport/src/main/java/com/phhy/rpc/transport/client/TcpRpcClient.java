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
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于自定义 TCP 协议的 RPC 客户端（无 HTTP/2、无 TLS）
 * 直接通过 TCP Socket 发送 RpcMessage，Pipeline: RpcMessageEncoder → RpcMessageDecoder → RpcClientHandler
 */
@Slf4j
public class TcpRpcClient implements RpcClient {

    private final EventLoopGroup workerGroup;
    private final UnprocessedRequests unprocessedRequests;
    private final RpcMessageEncoder rpcMessageEncoder = new RpcMessageEncoder();
    private final AtomicInteger requestIdGenerator = new AtomicInteger(0);
    private final SerializeType serializeType;

    // 连接缓存: host:port -> Channel
    private final Map<String, Channel> channelMap = new ConcurrentHashMap<>();
    private final Map<String, Object> channelLocks = new ConcurrentHashMap<>();

    public TcpRpcClient(SerializeType serializeType) {
        this.serializeType = serializeType;
        this.workerGroup = new NioEventLoopGroup();
        this.unprocessedRequests = new UnprocessedRequests();
    }

    public RpcResponse sendRequest(RpcRequest request, String host, int port) {
        String key = host + ":" + port;
        long startTime = System.currentTimeMillis();

        try {
            Channel channel = getOrCreateChannel(host, port);

            long requestId = requestIdGenerator.getAndIncrement();

            RpcMessage rpcMessage = RpcMessage.newInstance();
            rpcMessage.setVersion(RpcConstant.VERSION);
            rpcMessage.setMsgType(MsgType.REQUEST);
            rpcMessage.setSerializeType(serializeType);
            rpcMessage.setRequestId(requestId);
            rpcMessage.setBody(request);

            CompletableFuture<RpcResponse> future = new CompletableFuture<>();
            unprocessedRequests.put(requestId, future);

            ByteBuf buf = channel.alloc().buffer();
            try {
                rpcMessageEncoder.encode(null, rpcMessage, buf);
                channel.writeAndFlush(buf).addListener((ChannelFutureListener) writeFuture -> {
                    rpcMessage.recycle();
                    if (!writeFuture.isSuccess()) {
                        unprocessedRequests.remove(requestId);
                        future.completeExceptionally(new RpcException("发送请求失败至 " + key, writeFuture.cause()));
                    }
                });
            } catch (Exception e) {
                buf.release();
                rpcMessage.recycle();
                unprocessedRequests.remove(requestId);
                throw new RpcException("编码或发送请求失败", e);
            }

            long timeout = request.getTimeout() > 0 ? request.getTimeout() : RpcConstant.DEFAULT_TIMEOUT;
            try {
                RpcResponse response = future.get(timeout, TimeUnit.MILLISECONDS);
                return response;
            } catch (java.util.concurrent.TimeoutException e) {
                unprocessedRequests.remove(requestId);
                throw new RpcTimeoutException("请求超时后 " + timeout + "毫秒用于 " + key);
            } catch (Exception e) {
                unprocessedRequests.remove(requestId);
                throw new RpcException("请求失败 " + key, e);
            }
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw new RpcException("获取连接或发送请求失败 " + key, e);
        }
    }

    private Channel getOrCreateChannel(String host, int port) throws Exception {
        String key = host + ":" + port;
        Channel channel = channelMap.get(key);
        if (channel != null && channel.isActive()) {
            return channel;
        }

        Object lock = channelLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            channel = channelMap.get(key);
            if (channel != null && channel.isActive()) {
                return channel;
            }

            if (channel != null) {
                channel.close();
            }

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new RpcMessageDecoder());
                            pipeline.addLast(new RpcMessageEncoder());
                            pipeline.addLast(new RpcClientHandler(unprocessedRequests, null));
                        }
                    });

            ChannelFuture future = bootstrap.connect(host, port).sync();
            Channel newChannel = future.channel();
            channelMap.put(key, newChannel);

            newChannel.closeFuture().addListener(f -> {
                channelMap.remove(key);
                log.debug("连接已关闭: {}", key);
            });

            return newChannel;
        }
    }

    public void warmup(String host, int port) {
        try {
            getOrCreateChannel(host, port);
        } catch (Exception e) {
            log.warn("预热连接失败 {}:{}", host, port, e);
        }
    }

    public void shutdown() {
        for (Channel ch : channelMap.values()) {
            ch.close();
        }
        channelMap.clear();
        workerGroup.shutdownGracefully();
    }
}
