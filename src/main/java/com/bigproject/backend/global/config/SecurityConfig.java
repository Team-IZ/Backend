package com.bigproject.backend.global.config;

import com.bigproject.backend.global.exception.ErrorResponse;
import com.bigproject.backend.global.security.JwtFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
	private final ObjectMapper objectMapper;
	private final JwtFilter jwtFilter;
	private final AllowedOriginPolicy allowedOriginPolicy;

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(
								"/api/v0/auth/**",
								"/api/v0/consents",
								"/swagger-ui.html",
								"/swagger-ui/**",
								"/v3/api-docs/**",
								"/error"
						).permitAll()
						.anyRequest().authenticated()
				)
				.exceptionHandling(exception -> exception
						.authenticationEntryPoint((request, response, authException) ->
								writeSecurityError(response, 401, "Unauthenticated", "UNAUTHENTICATED", "로그인이 필요합니다."))
						.accessDeniedHandler((request, response, authException) ->
								writeSecurityError(response, 403, "Access Denied", "ACCESS_DENIED", "접근 권한이 없습니다."))
				);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/**
	 * 허용 Origin은 {@link AllowedOriginPolicy} 하나만 본다 — 서버의 Origin 검사
	 * ({@code LoginClientValidator})와 값이 갈리면 "CORS는 통과했는데 403"이 되어 원인을 찾기 어렵다.
	 *
	 * <p>{@code setAllowedOrigins}가 아니라 {@code setAllowedOriginPatterns}를 쓴다. 전자는
	 * {@code https://*.vercel.app} 같은 항목을 받지 못해, 프리뷰 배포를 열어야 할 때 목록을 두 벌로
	 * 갈라 담게 된다.
	 */
	@Bean
	UrlBasedCorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOriginPatterns(allowedOriginPolicy.corsOriginPatterns());
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of(
				"Authorization",
				"Content-Type",
				"X-Login-Entry-Path",
				"X-Request-Id",
				"X-Swagger-Client-Origin"
		));
		configuration.setExposedHeaders(List.of("Location"));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/v0/**", configuration);
		return source;
	}

	/**
	 * 필터 단계에서 나가는 401·403도 컨트롤러 오류와 <b>같은 {@link ErrorResponse} 모양</b>이어야 한다.
	 * 여기만 다르면 프론트가 "인증 실패"만 별도 타입으로 다뤄야 한다.
	 */
	private void writeSecurityError(
			HttpServletResponse response,
			int status,
			String error,
			String code,
			String message
	) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json;charset=UTF-8");
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(status, error, code, message));
	}
}
