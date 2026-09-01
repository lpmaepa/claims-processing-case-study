package za.co.claims.processing.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI claimsProcessingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Claims Processing API")
                .version("v1")
                .description("Case-study API for reliable asynchronous insurance claim processing."));
    }
}
