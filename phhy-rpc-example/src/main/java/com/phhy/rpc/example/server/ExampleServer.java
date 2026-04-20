package com.phhy.rpc.example.server;

import com.phhy.rpc.example.api.HelloService;
import com.phhy.rpc.example.impl.HelloServiceImpl;
import com.phhy.rpc.server.RpcServerBootstrap;

public class ExampleServer {

    private static final String JWT_SECRET = "demo-rpc-jwt-secret";

    public static void main(String[] args) throws Exception {
        new RpcServerBootstrap()
                .port(8080)
                .nacosAddr("127.0.0.1:8848")
                .jwtSecret(JWT_SECRET)
                .jwtExpireMillis(60_000)
                .publishService(HelloService.class, new HelloServiceImpl())
                .start();

        System.out.println("RPC 服务器已在端口 8080 启动，按 Ctrl C 关闭。");
        Thread.currentThread().join();
    }
}
