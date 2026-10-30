package com.myshelf.global.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

  @Bean("searchExecutor")
  public Executor searchExecutor() {
    return Executors.newFixedThreadPool(12);
  }
}
