package com.phhy.rpc.example.api;

public interface HelloService {

    String hello(String name);

    String hello(String name, int age);

    String register(UserProfile profile);
}
