package com.changeops.changeservice;

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
                title = "ChangeOps — Change Service API",
                version = "1.0.0",
                description = "REST API for creating and querying technical change requests. "
                        + "All endpoints require Bearer JWT authentication (except health/metrics).",
                contact = @Contact(name = "ChangeOps Team")),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Provide a valid JWT token. Example: Bearer eyJ...")
public class ChangeServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChangeServiceApplication.class, args);
    }
}
