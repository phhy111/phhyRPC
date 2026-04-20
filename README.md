# myRPC 项目说明

`myRPC` 是一个基于 Java 17 的多模块 RPC 框架，使用 Netty + HTTP/2 作为通信层，Nacos 作为注册发现中心，支持 JSON/Kryo 序列化，并内置可扩展 Filter 链路。

## 1. 项目模块

- `phhy-rpc-common`：公共模型、常量、异常、工具类（包含 JWT 与加密能力）
- `phhy-rpc-protocol`：RPC 协议模型与编解码
- `phhy-rpc-serialization`：JSON/Kryo 序列化实现
- `phhy-rpc-registry`：Nacos 注册与发现、服务缓存
- `phhy-rpc-loadbalance`：负载均衡（默认轮询）
- `phhy-rpc-transport`：Netty 客户端/服务端与心跳管理
- `phhy-rpc-proxy`：JDK 动态代理与 Filter 链
- `phhy-rpc-client`：客户端启动引导
- `phhy-rpc-server`：服务端启动引导
- `phhy-rpc-example`：示例服务与调用演示

## 2. 核心调用链路

1. 客户端通过 `RpcClientBootstrap` 启动并获取代理对象。
2. 代理层 `RpcClientProxy` 构造 `RpcRequest`，执行 Filter 链前置逻辑。
3. 从注册中心发现服务实例，经负载均衡选择目标节点。
4. `NettyRpcClient` 通过 HTTP/2 Stream 发送请求并等待响应。
5. 服务端 `RpcServerHandler` 收到请求后（可选 JWT 校验与敏感字段解密）、反射调用，再返回响应（可选敏感字段加密）。
6. 返回 `RpcResponse`，客户端执行 Filter 链后置逻辑并返回结果或抛出远程异常。

## 3. JWT 认证

### 3.1 关键组件

- `JwtUtils`：生成/校验 JWT，支持配置密钥和过期时间。
- `AuthFilter`：客户端请求前注入并校验 token。
- `AuthContext`：服务端请求线程内保存认证信息（subject/token）。
- `RpcRequest.authToken`：请求级认证字段。
- `RpcServerHandler`：在配置了 `jwtSecret` 时校验 `RpcRequest.authToken`，失败返回错误响应；与 `EncryptionFilter` 配合时做入参解密与返回值加密。

### 3.2 使用方式

客户端：

```java
JwtUtils.configure("demo-rpc-jwt-secret", 60_000);
String token = JwtUtils.generateToken("example-client");

RpcClientBootstrap client = new RpcClientBootstrap()
        .nacosAddr("127.0.0.1:8848")
        .jwtSecret("demo-rpc-jwt-secret")
        .jwtExpireMillis(60_000)
        .withAuthToken(token);
client.start();
```

`start()` 内会再次调用 `JwtUtils.configure`（与上述配置一致即可）。也可在 `start()` 之前自行 `JwtUtils.configure` 并生成 token。

服务端（配置了 `jwtSecret` 即开启鉴权，需与客户端密钥一致）：

```java
new RpcServerBootstrap()
        .port(8080)
        .nacosAddr("127.0.0.1:8848")
        .jwtSecret("demo-rpc-jwt-secret")
        .jwtExpireMillis(60_000)
        .publishService(HelloService.class, new HelloServiceImpl())
        .start();
```

## 4.敏感数据加密

### 4.1 关键组件

- `@Sensitive`：标记需要加解密的字段。
- `EncryptionUtils`：AES/RSA 加解密与密钥生成。
- `KeyManager`：密钥获取与轮换（当前提供内存实现）。
- `SensitiveDataProcessor`：递归处理对象图中的敏感字段。
- `EncryptionFilter`：客户端请求前加密、响应后解密；服务端需在 `RpcServerBootstrap` 上启用 `sensitiveDataProcessing(true)`，与客户端成对使用。

### 4.2 示例模型

```java
@Data
public class UserProfile implements Serializable {
    private String username;
    @Sensitive
    private String phone;
    @Sensitive
    private String idCard;
}
```

## 5. 示例工程说明

`phhy-rpc-example` 已包含完整示例：

- 正常调用：`hello(name)` / `hello(name, age)` / `register(UserProfile)`
- 认证失败演示：过期 token、错误签名 token

启动顺序：

1. 启动 `ExampleServer`
2. 启动 `ExampleClient`

## 6. 构建命令

```bash
mvn -DskipTests compile
```

## 7. 当前设计注意点

- `JwtUtils` 为轻量自实现，适合学习与框架演示；生产环境建议使用成熟 JWT 库并增加更多 claim 校验。
- `KeyManager` 当前为进程内存存储，生产建议接入 KMS/Vault 等外部密钥系统。
- 参数反射调用前已增加类型对齐逻辑（处理 JSON 下 `Map -> POJO` 的参数转换问题）。
- 业务线程池使用 `AbortPolicy`，队列满时快速失败并返回「服务器繁忙」，避免在 Netty I/O 线程执行业务逻辑。

