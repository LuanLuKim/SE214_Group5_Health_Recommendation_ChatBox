package vn.edu.uit.se214.health.system;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "Health Recommendation API",
            version = "v1",
            description = "API backend cho ứng dụng Health Recommendation ChatBox."))
public class OpenApiConfiguration {}
