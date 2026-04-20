package com.phhy.rpc.example.client;

import com.phhy.rpc.client.RpcClientBootstrap;
import com.phhy.rpc.common.util.JwtUtils;
import com.phhy.rpc.example.api.HelloService;

public class ExampleClient {

    public static void main(String[] args) {
        JwtUtils.configure("demo-rpc-jwt-secret", 60_000);
        String token = JwtUtils.generateToken("example-client");

        RpcClientBootstrap client = new RpcClientBootstrap()
                .nacosAddr("127.0.0.1:8848")
                .timeout(5000)
                .jwtSecret("demo-rpc-jwt-secret")
                .jwtExpireMillis(60_000)
                .withAuthToken(token);

        client.start();

        try {
            HelloService helloService = client.getService(HelloService.class);

            String result1 = helloService.hello("world");
            System.out.println("Result 1: " + result1);

            String result2 = helloService.hello("RPC", 3);
            System.out.println("Result 2: " + result2);
        } finally {
            client.shutdown();
        }
    }
}
