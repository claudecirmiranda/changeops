package com.changeops.deployorchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DeployOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeployOrchestratorApplication.class, args);
    }
}
