package com.phhy.rpc.transport.client;

import com.phhy.rpc.common.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class UnprocessedRequests {

    private final Map<Long, CompletableFuture<RpcResponse>> pendingRequests = new ConcurrentHashMap<>();

    public void put(long requestId, CompletableFuture<RpcResponse> future) {
        pendingRequests.put(requestId, future);
    }

    public CompletableFuture<RpcResponse> remove(long requestId) {
        return pendingRequests.remove(requestId);
    }

    public void complete(long requestId, RpcResponse response) {
        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        if (future != null) {
            future.complete(response);
        } else {
            log.warn("No pending request found for requestId: {}", requestId);
        }
    }

    // 连接关闭时，遍历所有未完成请求，全部completeExceptionally，避免调用线程无限阻塞
    public void completeAllExceptionally(Throwable cause) {
        for (Map.Entry<Long, CompletableFuture<RpcResponse>> entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(cause);
        }
        pendingRequests.clear();
        log.warn("All pending requests completed exceptionally: {}", cause.getMessage());
    }

    public int size() {
        return pendingRequests.size();
    }
}
