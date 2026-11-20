package com.aics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池集中定义：入库、Trace 旁路、工具超时控制、SSE 心跳互不干扰。
 */
@Configuration
public class ExecutorConfig {

    @Bean(name = "ingestExecutor")
    public ThreadPoolTaskExecutor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    @Bean(name = "traceExecutor")
    public ThreadPoolTaskExecutor traceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("trace-writer-");
        // Trace 是旁路，队列满了直接丢弃，不允许反压到用户请求
        executor.setRejectedExecutionHandler((r, e) -> {
        });
        executor.initialize();
        return executor;
    }

    @Bean(name = "chatExecutor")
    public ThreadPoolTaskExecutor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 10 路并发对话是产品指标，留一倍余量
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("chat-turn-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "toolExecutor")
    public ThreadPoolTaskExecutor toolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("tool-call-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "sseHeartbeatScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService sseHeartbeatScheduler() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger idx = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sse-heartbeat-" + idx.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newScheduledThreadPool(2, factory);
    }
}
