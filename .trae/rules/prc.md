我要从零搭建一个生产级的 Java RPC 框架，用于学习和简历项目。


技术栈：


语言：Java 17

网络：Netty 4.x

序列化：JSON，Kryo

注册中心：Nacos

构建工具：Maven 多模块

核心机制：JDK 动态代理、反射调用

负载均衡：轮询策略

服务保障：心跳机制、服务健康检查

协议规范：RPC 协议采用 HTTP/2


请帮我实现以下全部功能，要求：


1.
代码完整可运行，不要省略

2.
关键逻辑加注释

3.
包名统一用 com.phhy.rpc



一、Maven 多模块结构


采用 Maven 多模块项目，统一管理依赖版本与模块间依赖关系。模块划分为：


phhy-rpc-common：公共模型（RpcRequest、RpcResponse、ServiceInstance）、常量（魔数、消息类型、序列化类型枚举）、自定义异常体系（RpcException、RpcTimeoutException、RpcRemoteException）、工具类

phhy-rpc-protocol：自定义 RPC 协议定义、编解码器

phhy-rpc-serialization：序列化接口与实现

phhy-rpc-registry：Nacos 注册中心集成

phhy-rpc-loadbalance：负载均衡策略

phhy-rpc-transport：Netty 客户端与服务端封装

phhy-rpc-proxy：JDK 动态代理

phhy-rpc-server：服务端启动引导

phhy-rpc-client：客户端启动引导

phhy-rpc-example：使用示例（接口定义、服务端、客户端）



二、自定义 RPC 协议设计


HTTP/2 与自定义 RPC 协议采用分层设计：


外层（HTTP/2 帧层）：利用 HTTP/2 多路复用、头部压缩、二进制帧等特性实现高效通信。HTTP/2 在传输层提供帧分片与多路复用能力，无需处理底层 TCP 粘包问题。HTTP/2 帧头自带 Length 字段定义帧边界，天然具备消息分帧语义。


内层（自定义 RPC 协议层）：在 HTTP/2 DATA 帧的 payload 内定义统一的 RPC 消息格式，分为协议头与协议体两部分。一条完整 RPC 消息作为整体封装进 HTTP/2 DATA 帧的 payload 中。由于一条 RPC 消息可能跨多个 DATA 帧，或一个 DATA 帧包含多条小 RPC 消息，内层协议头仍需 bodyLen 字段界定消息边界。


协议头采用 18 字节定长设计：


字段	类型	长度	说明
magic	byte[4]	4B	魔数 "RPC\0"（0x52,0x50,0x43,0x00），解码时校验，防止非法连接
version	byte	1B	协议版本，当前为 1，用于协议升级兼容
msgType	byte	1B	消息类型：0x01=REQUEST，0x02=RESPONSE，0x03=HEARTBEAT_REQ，0x04=HEARTBEAT_RESP
serializeType	byte	1B	序列化方式：0x01=JSON，0x02=KRYO
requestId	long	8B	请求唯一 ID（大端序），用于 HTTP/2 多路复用时匹配请求与响应
bodyLen	int	4B	消息体字节长度（大端序）
body	byte[]	bodyLen	序列化后的 RpcRequest 或 RpcResponse

实现 RpcMessageEncoder 编码器（将 RpcMessage 编码为 ByteBuf）和 RpcMessageDecoder 解码器（从 ByteBuf 解码出 RpcMessage，处理半包情况：先检查协议头是否完整，再读取 bodyLen 校验消息体是否完整，不完整则重置读索引等待更多数据）。



三、序列化抽象层


实现 Serializer 序列化接口，定义 serialize(Object obj) 和 deserialize(byte[] bytes, Class<T> clazz) 两个方法。通过 SerializerFactory 工厂根据序列化类型获取对应的 Serializer 单例实例，支持 JSON 与 Kryo 灵活切换。


JSON 序列化（Jackson 实现）：


配置 FAIL_ON_UNKNOWN_PROPERTIES = false，JSON 中多余字段不导致反序列化失败，保证空对象兼容

配置 FAIL_ON_SELF_REFERENCES = false，跳过自引用，处理循环引用场景

通过 @JsonManagedReference / @JsonBackReference 注解配合处理双向引用


Kryo 序列化实现：


使用 KryoPool 池化管理 Kryo 实例，每次序列化/反序列化从池中借用，用完归还，保证线程安全（Kryo 实例本身非线程安全）

开启引用跟踪（setReferences(true)），自动处理循环引用

对 null 对象天然支持，serialize(null) 返回空字节数组，反序列化时遇到空字节数组返回 null

setRegistrationRequired(false)，兼容未注册的类


适配自定义 RPC 协议的消息体序列化/反序列化：编码时根据 RpcMessage 中的 serializeType 选择序列化器对 RpcRequest/RpcResponse 进行序列化，解码时根据同样的类型反序列化回对象。



四、集成 Nacos 实现服务注册与发现


实现 ServiceRegistry 服务注册接口，提供 register（注册服务实例，携带元数据）、deregister（注销服务实例）、updateHealthStatus（更新健康状态）三个方法。基于 Nacos SDK 实现 NacosRegistry，服务注册时将实例信息（IP、端口、权重）与元数据（healthStatus、serializeType、protocolVersion、rpcPort、lastHeartbeat）写入 Nacos。


实现 ServiceDiscovery 服务发现接口，提供 getHealthyInstances 方法。基于 Nacos 实现 NacosDiscovery，从 Nacos 拉取服务实例列表，双重过滤：先过滤 Nacos 自身标记为 unhealthy 的实例，再过滤自定义元数据 healthStatus 不为 UP 的实例，仅返回健康实例。


实现 ServiceCacheManager 本地服务缓存管理：


客户端从 Nacos 拉取服务列表后缓存到本地 ConcurrentHashMap

定时任务（默认 30 秒）刷新所有已缓存的服务列表

缓存条目带有过期时间（默认 60 秒），超时后强制从 Nacos 重新拉取

当 Nacos 不可用时，降级使用本地缓存，保证基本可用性

提供 forceRefresh 方法，连接失败时强制刷新缓存



五、负载均衡采用轮询策略


实现 LoadBalancer 负载均衡接口，提供 select(List<ServiceInstance> instances) 方法。基于 AtomicInteger 实现 RoundRobinBalancer 轮询策略：


使用 AtomicInteger 维护全局计数器，每次调用 select() 时通过 getAndIncrement() 获取当前值并对实例列表取模（CAS 原子操作保证线程安全）

从 Nacos 拉取的健康实例列表中依次选择服务节点进行调用，保证流量均匀分发

实例列表变更时（服务上下线），下一轮调用自动适配新列表

接口抽象设计，后续可扩展随机策略、加权轮询、一致性哈希等



六、JDK 动态代理实现透明远程调用


实现 RpcClientProxy 类实现 InvocationHandler 接口。通过 Proxy.newProxyInstance 为服务接口生成代理对象，客户端通过代理类封装远程调用细节，实现"本地方法调用"式的透明远程调用，支持同步调用。


代理方法调用流程：


1.
调用方执行 proxy.hello("world")，触发 InvocationHandler.invoke()

2.
过滤 Object 类方法（toString、hashCode、equals），不进行远程调用

3.
构造 RpcRequest：interfaceName（接口全限定名）、methodName（方法名）、parameterTypes（参数类型列表，用于方法重载匹配）、parameters（参数值列表）、timeout（超时时间）

4.
通过 ServiceCacheManager 从 Nacos（本地缓存）获取该服务的健康实例列表

5.
通过 LoadBalancer 轮询选择一个服务实例

6.
通过 NettyRpcClient 获取与该实例的 Channel 连接

7.
序列化 RpcRequest 为字节数组，封装为 RpcMessage，通过 Channel 发送

8.
注册 CompletableFuture<RpcResponse> 到 UnprocessedRequests（key 为 requestId），同步等待响应（带超时控制）

9.
RpcClientHandler 接收服务端响应，根据 requestId 匹配 CompletableFuture，反序列化 RpcResponse，完成 Future 唤醒调用线程

10.
成功则返回 result，失败则抛出 RpcRemoteException（携带远程异常类名和异常消息）


请求 ID 生成：使用 AtomicInteger 递增生成，保证同一客户端内唯一，用于 HTTP/2 多路复用时的请求-响应匹配。


超时控制：调用级可配置，默认 5 秒。CompletableFuture.get(timeout, TimeUnit.MILLISECONDS) 同步等待，超时抛出 RpcTimeoutException。



七、心跳机制


实现客户端与服务端之间的心跳保活机制，独立于业务请求，不阻塞业务调用。


客户端心跳管理（ClientHeartbeatManager）：


定时发送心跳：启动 ScheduledExecutorService，每隔 30 秒向所有已连接的服务端发送 HEARTBEAT_REQ 类型的 RpcMessage

记录响应时间：每个服务端 key（host:port）对应一个最后心跳响应时间戳，收到 HEARTBEAT_RESP 时更新

超时检测：定时任务检查每个服务端的最后响应时间，超过 60 秒未收到响应则判定为超时

故障转移：超时后通过 ChannelManager.markFault(key) 标记该通道为故障，从心跳列表中移除。下次业务请求时，getChannel() 检测到故障标记，从 Nacos 重新拉取健康实例列表，负载均衡切换至其他健康节点，自动重建连接


服务端心跳检测（ServerHeartbeatHandler）：


继承 IdleStateHandler，配置读空闲超时时间为 60 秒

超过 60 秒未收到客户端任何数据（包括心跳和业务请求），触发 READER_IDLE 事件

触发后标记客户端离线，关闭该客户端连接，释放相关资源

收到 HEARTBEAT_REQ 时立即返回 HEARTBEAT_RESP，不提交到业务线程池（在 IO 线程中直接处理，轻量级操作）



八、服务健康检查


实现三层健康检查机制：


第一层：服务端自身健康检查（ServerHealthChecker）


启动 ScheduledExecutorService 定时任务，每隔 15 秒执行一次健康检测

检查项一：JVM 内存使用率，通过 MemoryMXBean 获取堆内存使用量与最大值，计算使用率，超过 90% 标记为不健康

检查项二：业务线程池状态，检查 ThreadPoolExecutor 队列积压率，超过 80% 标记为不健康

检查结果同步至 Nacos：通过 ServiceRegistry.updateHealthStatus() 更新实例元数据中的 healthStatus 字段为 UP 或 DOWN

Nacos 收到 DOWN 状态后，客户端下次拉取实例列表时将过滤掉该实例


第二层：Nacos 注册中心校验


Nacos 内置健康检查机制，定期向注册的实例发送健康检查探针

实例长时间未响应，Nacos 将其标记为 unhealthy 并从可用列表中剔除

长时间处于 unhealthy 状态的实例，Nacos 自动执行注销操作


第三层：客户端健康状态过滤


客户端从 Nacos 获取实例列表后进行双重过滤

过滤条件一：Nacos 自身标记为 unhealthy 的实例

过滤条件二：自定义元数据 healthStatus 不为 UP 的实例

仅对通过双重过滤的健康实例进行负载均衡和远程调用



九、Netty 客户端与服务端封装


线程模型设计：


Boss Group（1 线程）：负责接受 TCP 连接

Worker Group（CPU 核心数 × 2 线程）：负责 IO 读写、HTTP/2 帧处理、协议编解码

业务线程池（核心线程数 = CPU 核心数，最大线程数 = CPU × 2，有界队列容量 1000，拒绝策略 CallerRunsPolicy）：执行反射调用等耗时操作，避免阻塞 IO 线程


Netty 服务端（NettyRpcServer）：


Channel Pipeline 入站：SslHandler → Http2FrameCodec（HTTP/2 帧编解码）→ Http2MultiplexCodec（多路复用）→ RpcMessageDecoder（自定义协议解码，处理半包）→ ServerHeartbeatHandler（心跳超时检测）→ RpcServerHandler（业务处理，提交到业务线程池）

Channel Pipeline 出站：RpcMessageEncoder（自定义协议编码）→ Http2FrameCodec → SslHandler

支持优雅停机：通过 JVM ShutdownHook 按顺序执行停止健康检查 → 从 Nacos 注销服务 → 关闭 Netty Server


Netty 客户端（NettyRpcClient）：


与指定服务端实例建立 HTTP/2 连接，SslContext 配置 ALPN 协议协商

通过 ChannelManager 管理连接通道缓存（ConcurrentHashMap<host:port, Channel>），支持连接复用

通道失效时自动标记故障，下次请求时重建连接

封装 sendRequest 方法：序列化请求 → 构造 RpcMessage → 注册 CompletableFuture → 发送 → 同步等待响应（带超时）

RpcClientHandler 接收响应：根据 requestId 匹配 CompletableFuture → 反序列化 RpcResponse → 完成 Future



十、请求-响应匹配机制


HTTP/2 多路复用意味着同一连接上会交错多个请求和响应。通过 UnprocessedRequests 类管理未完成请求：


数据结构：ConcurrentHashMap<Long, CompletableFuture<RpcResponse>>

客户端发送请求时，以 requestId 为 key 注册 CompletableFuture

RpcClientHandler 收到 RESPONSE 类型消息时，根据 requestId 从 Map 中取出对应的 CompletableFuture 并完成（complete），唤醒调用线程

超时或连接异常时，清理对应的 CompletableFuture 并 completeExceptionally

连接关闭时，遍历所有未完成请求，全部 completeExceptionally，避免调用线程无限阻塞



十一、异常传播机制


定义完整异常体系：


RpcException：RPC 基础异常

RpcTimeoutException：调用超时异常，客户端等待响应超过配置的超时时间时抛出

RpcRemoteException：远程异常，服务端目标方法抛出异常时，异常类名和异常消息通过 RpcResponse 传递到客户端，客户端抛出 RpcRemoteException，调用方可通过 getRemoteExceptionClass() 获取远程异常类型


异常处理流程：


服务端反射调用目标方法时，捕获 InvocationTargetException，提取原始异常，构造 RpcResponse.fail()

客户端收到失败响应后，抛出 RpcRemoteException，保持异常语义的一致性

连接失败、超时等通信层异常直接抛出对应的 RpcException 子类



十二、Filter 拦截器链


定义 Filter 接口，支持在请求发送前和响应接收后插入自定义逻辑。客户端和服务端分别维护 Filter 链，按注册顺序执行。典型应用场景：


日志记录 Filter：记录每次调用的接口名、方法名、耗时、结果

鉴权 Filter：请求携带 token，服务端校验权限

限流 Filter：客户端或服务端基于 QPS 限制调用频率

监控 Filter：采集调用成功率、延迟分布等指标，上报至监控系统