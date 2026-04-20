package com.phhy.rpc.proxy.filter;

import com.phhy.rpc.common.exception.RpcAuthException;
import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import com.phhy.rpc.common.util.JwtUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AuthFilter implements Filter {

    private final String authToken;

    public AuthFilter(String authToken) {
        this.authToken = authToken;
    }

    @Override
    public void doFilterBefore(RpcRequest request) {
        String token = request.getAuthToken();
        if (token == null || token.isBlank()) {
            token = authToken;
            request.setAuthToken(token);
        }
        if (token == null || token.isBlank()) {
            throw new RpcAuthException("认证失败：缺少 JWT token");
        }
        if (!JwtUtils.validateToken(token)) {
            throw new RpcAuthException("认证失败：JWT token 无效或已过期");
        }
    }

    @Override
    public void doFilterAfter(RpcRequest request, RpcResponse response) {
        String token = request.getAuthToken();
        if (token != null && !token.isBlank() && !JwtUtils.validateToken(token)) {
            log.warn("请求完成后 token 已失效，请考虑刷新 token");
        }
    }
}
