package com.phhy.rpc.transport.client;

import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;

/**
 * RPC 客户端通用接口
 * 抽象 NettyRpcClient（HTTP/2）和 TcpRpcClient（自定义 TCP）的公共能力
 */
public interface RpcClient {

    /**
     * 发送同步 RPC 请求
     */
    RpcResponse sendRequest(RpcRequest request, String host, int port);

    /**
     * 预热与指定服务端的连接
     */
    void warmup(String host, int port);

    /**
     * 关闭客户端，释放所有资源
     */
    void shutdown();
}
