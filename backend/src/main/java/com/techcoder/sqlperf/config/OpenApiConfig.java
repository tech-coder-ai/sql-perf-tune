package com.techcoder.sqlperf.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI sptOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SQL Performance & Tuning API")
                .description("Query log ingestion, fingerprint grouping, tuning tracker and optimization workflow")
                .version("v1"));
    }
}
