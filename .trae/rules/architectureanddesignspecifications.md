## 三、架构与设计规范

### 3.1 模块依赖方向

phhy-rpc-common（零依赖，所有模块可依赖它）
↑
phhy-rpc-protocol（依赖 common）
phhy-rpc-serialization（依赖 common）
phhy-rpc-loadbalance（依赖 common）
phhy-rpc-registry（依赖 common）
↑
phhy-rpc-transport（依赖 protocol、serialization、common）
↑
phhy-rpc-proxy（依赖 transport、loadbalance、registry、common）
↑
phhy-rpc-server（依赖 proxy、transport、registry、common）
phhy-rpc-client（依赖 proxy、transport、registry、loadbalance、common）
↑
phhy-rpc-example（依赖 server、client、common）

text
text

- 严格遵守自底向上依赖，禁止反向依赖
- common 模块不依赖任何其他模块
- 同层模块之间尽量不互相依赖

### 3.2 设计原则

- 单一职责：每个类只做一件事，超过 300 行的类考虑拆分
- 开闭原则：对扩展开放，对修改关闭。新增序列化方式只需新增 Serializer 实现类 + 注册到 Factory，不修改已有代码
- 里氏替换：接口的所有实现必须可互换使用
- 接口隔离：接口方法不超过 5 个，过大的接口拆分为多个小接口
- 依赖倒置：模块间通过接口交互，不直接依赖实现类。如 ServiceRegistry 接口 + NacosRegistry 实现

### 3.3 设计模式使用规范

- 工厂模式：用于创建序列化器（SerializerFactory）、线程池（ThreadPoolFactory）
- 策略模式：用于负载均衡（LoadBalancer 接口 + RoundRobinBalancer 实现）
- 代理模式：JDK 动态代理（RpcClientProxy）
- 建造者模式：用于复杂对象构建（RpcServerBootstrap、RpcClientBootstrap）
- 单例模式：序列化器通过 Factory 缓存为单例，线程安全
- 观察者模式：可选，用于服务上下线事件通知
- 模板方法：可选，用于编解码流程的公共逻辑提取

### 3.4 接口设计规范

- 所有跨模块调用必须通过接口
- 接口方法签名中不暴露实现细节（不使用 Netty 的 Channel、ByteBuf 等作为参数）
- 接口的参数和返回值使用 common 模块中的模型类
- 接口文档使用 Javadoc 注释，说明每个参数的含义和返回值的可能情况
