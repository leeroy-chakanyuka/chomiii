package com.chomiii.memory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
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
}
