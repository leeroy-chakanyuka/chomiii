package com.chomiii.chat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatStreamConfiguration {
    @Bean(destroyMethod = "shutdown")
    ExecutorService chatExecutor() {
        return Executors.newFixedThreadPool(8, Thread.ofPlatform().name("chat-stream-", 0).factory());
    }
}
