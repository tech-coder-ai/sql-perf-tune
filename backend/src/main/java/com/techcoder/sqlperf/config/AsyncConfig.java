package com.techcoder.sqlperf.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    /** One worker: imports run one at a time in the order they were submitted. */
    @Bean(name = "ingestionExecutor", destroyMethod = "shutdown")
    Executor ingestionExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("spt-ingest-");
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }

    /** Automatic diagnostics captures (explain / profile / DDL fetches) of newly tracked groups. */
    @Bean(name = "diagnosticsExecutor", destroyMethod = "shutdown")
    Executor diagnosticsExecutor(SptProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(Math.max(1, props.diagnostics().threads()));
        ex.setMaxPoolSize(Math.max(1, props.diagnostics().threads()));
        ex.setQueueCapacity(10_000);
        ex.setThreadNamePrefix("spt-capture-");
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }
}
