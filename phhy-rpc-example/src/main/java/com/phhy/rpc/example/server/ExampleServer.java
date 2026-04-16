package com.phhy.rpc.example.server;

import com.phhy.rpc.example.api.HelloService;
import com.phhy.rpc.example.impl.HelloServiceImpl;
import com.phhy.rpc.server.RpcServerBootstrap;

public class ExampleServer {

    public static void main(String[] args) throws Exception {
        new RpcServerBootstrap()
                .port(8080)
                .nacosAddr("127.0.0.1:8848")
                .publishService(HelloService.class, new HelloServiceImpl())
                .start();

        System.out.println("RPC Server started on port 8080, press Ctrl+C to shutdown.");
        Thread.currentThread().join();
    }
}
