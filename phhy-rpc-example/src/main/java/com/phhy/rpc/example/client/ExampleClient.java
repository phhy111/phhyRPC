package com.phhy.rpc.example.client;

import com.phhy.rpc.client.RpcClientBootstrap;
import com.phhy.rpc.example.api.HelloService;
import com.phhy.rpc.example.api.UserProfile;
import com.phhy.rpc.common.util.JwtUtils;

public class ExampleClient {

    private static final String JWT_SECRET = "demo-rpc-jwt-secret";
    private static final String WRONG_JWT_SECRET = "demo-rpc-wrong-secret";

    public static void main(String[] args) {
        JwtUtils.configure(JWT_SECRET, 60_000);
        String token = JwtUtils.generateToken("example-client");

        RpcClientBootstrap client = new RpcClientBootstrap()
                .nacosAddr("127.0.0.1:8848")
                .timeout(5000)
                .jwtSecret(JWT_SECRET)
                .jwtExpireMillis(60_000)
                .withAuthToken(token);

        client.start();

        try {
            HelloService helloService = client.getService(HelloService.class);

            String result1 = helloService.hello("world");
            System.out.println("Result 1: " + result1);

            String result2 = helloService.hello("RPC", 3);
            System.out.println("Result 2: " + result2);

            UserProfile profile = UserProfile.builder()
                    .username("alice")
                    .phone("13800138000")
                    .idCard("110101199001010011")
                    .build();
            String result3 = helloService.register(profile);
            System.out.println("Result 3: " + result3);

            demoExpiredToken();
            demoWrongSecretToken();
        } finally {
            client.shutdown();
        }
    }

    private static void demoExpiredToken() {
        JwtUtils.configure(JWT_SECRET, 1);
        String expiredToken = JwtUtils.generateToken("expired-client");
        sleep(5);

        RpcClientBootstrap expiredClient = new RpcClientBootstrap()
                .nacosAddr("127.0.0.1:8848")
                .timeout(3000)
                .jwtSecret(JWT_SECRET)
                .jwtExpireMillis(60_000)
                .withAuthToken(expiredToken);
        expiredClient.start();
        try {
            HelloService helloService = expiredClient.getService(HelloService.class);
            helloService.hello("expired");
            System.out.println("Expired token check: unexpected success");
        } catch (Exception e) {
            System.out.println("Expired token check: expected failure -> " + e.getMessage());
        } finally {
            expiredClient.shutdown();
            JwtUtils.configure(JWT_SECRET, 60_000);
        }
    }

    private static void demoWrongSecretToken() {
        JwtUtils.configure(WRONG_JWT_SECRET, 60_000);
        String wrongSecretToken = JwtUtils.generateToken("wrong-secret-client");
        JwtUtils.configure(JWT_SECRET, 60_000);

        RpcClientBootstrap wrongSecretClient = new RpcClientBootstrap()
                .nacosAddr("127.0.0.1:8848")
                .timeout(3000)
                .jwtSecret(JWT_SECRET)
                .jwtExpireMillis(60_000)
                .withAuthToken(wrongSecretToken);
        wrongSecretClient.start();
        try {
            HelloService helloService = wrongSecretClient.getService(HelloService.class);
            helloService.hello("wrong-secret");
            System.out.println("Wrong secret token check: unexpected success");
        } catch (Exception e) {
            System.out.println("Wrong secret token check: expected failure -> " + e.getMessage());
        } finally {
            wrongSecretClient.shutdown();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
