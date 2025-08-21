package com.gaebang.backend.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Value("${moderation.async.core-pool-size:5}")
    private int moderationCorePoolSize;

    @Value("${moderation.async.max-pool-size:10}")
    private int moderationMaxPoolSize;

    @Value("${moderation.async.queue-capacity:100}")
    private int moderationQueueCapacity;

    @Value("${moderation.async.keep-alive-seconds:60}")
    private int moderationKeepAliveSeconds;

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("News-Async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "moderationExecutor")
    public Executor moderationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(moderationCorePoolSize);
        executor.setMaxPoolSize(moderationMaxPoolSize);
        executor.setQueueCapacity(moderationQueueCapacity);
        executor.setKeepAliveSeconds(moderationKeepAliveSeconds);
        executor.setThreadNamePrefix("Moderation-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        
        // 거부된 작업에 대한 정책 설정
        executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) -> {
            log.warn("검열 작업이 거부되었습니다. 큐가 가득참 - 큐 크기: {}, 활성 스레드: {}", 
                     threadPoolExecutor.getQueue().size(), threadPoolExecutor.getActiveCount());
        });
        
        executor.initialize();
        
        log.info("검열용 비동기 스레드풀 초기화 완료 - Core: {}, Max: {}, Queue: {}", 
                 moderationCorePoolSize, moderationMaxPoolSize, moderationQueueCapacity);
        
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> {
            log.error("비동기 메서드 실행 중 예외 발생 - Method: {}, Exception: {}",
                    method.getName(), throwable.getMessage(), throwable);
        };
    }
}
