package com.changeops.deployorchestrator;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@OpenAPIDefinition(
        info = @Info(
                title = "ChangeOps — Deploy Orchestrator API",
                version = "1.0.0",
                description = "Event-driven service that consumes deploy results from Kafka, "
                        + "runs the post-deploy checklist, updates change status and publishes "
                        + "completion events. Exposes actuator endpoints for health and metrics.",
                contact = @Contact(name = "ChangeOps Team")),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Provide a valid JWT token. Example: Bearer eyJ...")
public class DeployOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeployOrchestratorApplication.class, args);
    }
}
