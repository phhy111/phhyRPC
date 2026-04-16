package com.phhy.rpc.example.client;

import com.phhy.rpc.client.RpcClientBootstrap;
import com.phhy.rpc.example.api.HelloService;

public class ExampleClient {

    public static void main(String[] args) {
        RpcClientBootstrap client = new RpcClientBootstrap()
                .nacosAddr("127.0.0.1:8848")
                .timeout(5000);

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
