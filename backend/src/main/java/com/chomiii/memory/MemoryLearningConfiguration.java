package com.chomiii.memory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MemoryLearningConfiguration {
    @Bean(name = "memoryLearningExecutor", destroyMethod = "shutdown")
    ExecutorService memoryLearningExecutor() {
        // Keep optional memory inference off both the visible response and chat workers.
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().name("memory-learning-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "memoryLearningScheduler", destroyMethod = "shutdown")
    ScheduledExecutorService memoryLearningScheduler() {
        var timer = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("memory-learning-timer-", 0).daemon(true).factory());
        // Remove completed watchdogs immediately rather than retaining provider credentials.
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}
