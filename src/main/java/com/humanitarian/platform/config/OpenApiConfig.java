package com.humanitarian.platform.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Live API explorer at /swagger-ui.html, generated from the controllers by
 * springdoc (DEP-2). The bearer scheme lets a reader paste the access token
 * from POST /api/auth/login into "Authorize" and call protected endpoints.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI nidaaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nidaa API")
                        .version("1.0.0")
                        .description("Humanitarian aid coordination: help requests, psychological support, "
                                + "provider matching and administration. Every response uses one envelope: "
                                + "{\"success\": boolean, \"message\": string, \"data\"?: ..., \"details\"?: ...}.")
                        .contact(new Contact().name("Nidaa").email("supp0rtnidaa@yandex.ru")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from POST /api/auth/login (data.token); 15 minutes, renewed with the refresh token.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
