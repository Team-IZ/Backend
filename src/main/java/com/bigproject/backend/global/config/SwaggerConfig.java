package com.bigproject.backend.global.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
		info = @Info(title = "IZ-Get", description = "IZ-Get API Specification", version = "v0.1")
)
@SecurityScheme(
		name = "bearerAuth",
		description = "로그인 API에서 발급받은 access token만 입력하세요. Bearer 접두사는 Swagger UI가 자동으로 추가합니다.",
		type = SecuritySchemeType.HTTP,
		scheme = "bearer",
		bearerFormat = "JWT"
)
@Configuration
public class SwaggerConfig {
}
