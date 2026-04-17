package com.phhy.rpc.server.health;

import com.phhy.rpc.common.enums.HealthStatus;
import com.phhy.rpc.common.model.ServiceInstance;
import com.phhy.rpc.common.util.NetUtils;
import com.phhy.rpc.registry.api.ServiceRegistry;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
//服务端健康检查器
public class ServerHealthChecker {

    private final ServiceRegistry serviceRegistry;// 服务注册中心
    private final ServiceInstance serviceInstance;//当前实例
    private final ScheduledExecutorService scheduler;//定时任务调度

    // JVM内存使用率阈值，超过不健康
    private static final double MEMORY_THRESHOLD = 0.9;
    // 线程池队列积压率阈值，超过不健康
    private static final double QUEUE_THRESHOLD = 0.8;
    // 检查间隔（默认15秒），检测一次
    private static final long CHECK_INTERVAL = 15_000L;

    private volatile HealthStatus currentStatus = HealthStatus.UP;
    private ThreadPoolExecutor monitoredThreadPool;

    public ServerHealthChecker(ServiceRegistry serviceRegistry, ServiceInstance serviceInstance) {
        this.serviceRegistry = serviceRegistry;
        this.serviceInstance = serviceInstance;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "服务器健康检查器");
            t.setDaemon(true);
            return t;
        });
    }

    public void setMonitoredThreadPool(ThreadPoolExecutor threadPool) {
        this.monitoredThreadPool = threadPool;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::check, CHECK_INTERVAL, CHECK_INTERVAL, TimeUnit.MILLISECONDS);
        log.info("服务器健康检查器已启动");
    }

    private void check() {
        try {
            boolean healthy = true;

            // 检查项一：JVM内存使用率
            MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            double memoryUsage = (double) heapUsage.getUsed() / heapUsage.getMax();
            if (memoryUsage > MEMORY_THRESHOLD) {
                log.warn("JVM 内存使用过高：{}%", String.format("%.2f", memoryUsage * 100));
                healthy = false;
            }

            // 检查项二：业务线程池队列积压率
            if (monitoredThreadPool != null) {
                int queueSize = monitoredThreadPool.getQueue().size();
                int maxQueueSize = monitoredThreadPool.getQueue().remainingCapacity() + queueSize;
                if (maxQueueSize > 0) {
                    double queueUsage = (double) queueSize / maxQueueSize;
                    if (queueUsage > QUEUE_THRESHOLD) {
                        log.warn("线程池队列使用过高： {}%", String.format("%.2f", queueUsage * 100));
                        healthy = false;
                    }
                }
            }

            // 更新健康状态
            HealthStatus newStatus = healthy ? HealthStatus.UP : HealthStatus.DOWN;
            if (newStatus != currentStatus) {
                currentStatus = newStatus;
                // 检查结果同步至Nacos
                serviceRegistry.updateHealthStatus(serviceInstance, currentStatus);
                log.info("健康状态已更改为： {}", currentStatus);
            }
        } catch (Exception e) {
            log.error("健康检查错误", e);
        }
    }

    public HealthStatus getCurrentStatus() {
        return currentStatus;
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
