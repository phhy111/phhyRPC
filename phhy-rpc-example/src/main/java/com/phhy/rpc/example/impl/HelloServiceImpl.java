package com.phhy.rpc.example.impl;

import com.phhy.rpc.example.api.HelloService;

public class HelloServiceImpl implements HelloService {

    @Override
    public String hello(String name) {
        return "Hello, " + name + "!";
    }

    @Override
    public String hello(String name, int age) {
        return "Hello, " + name + "! You are " + age + " years old.";
    }
}
