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
	 * 브라우저가 실어 보낼 수 있는 요청 헤더. <b>컨트롤러가 {@code @RequestHeader}로 읽는 헤더는
	 * 전부 여기 있어야 한다.</b>
	 *
	 * <p>테스트가 스펙과 대조하므로 {@code public}이다 — 목록을 테스트에 한 벌 더 적으면 두 벌이
	 * 갈라져, 정작 서버가 쓰는 쪽이 틀린 채로 검사만 통과한다.
	 *
	 * <h2>33차 R1 — {@code Idempotency-Key}가 빠져 제출이 통째로 막혀 있었다</h2>
	 *
	 * <p>스펙이 <b>필수</b>로 요구하는 헤더인데 목록에 없어서, 브라우저가 사전 확인 단계에서
	 * 본 요청을 취소했다. 헤더를 빼면 400이고 넣으면 브라우저가 막아 <b>프론트에 선택지가 없는</b>
	 * 상태였다. 제출이 막히면 분석·응시·리포트가 전부 막힌다.
	 *
	 * <p>{@code X-Trace-Id}도 같이 넣는다. {@code InterviewBriefController}가 읽는데 목록에 없어
	 * 같은 자리에 서 있었다 — 아직 부르는 화면이 없어서 드러나지 않았을 뿐이다.
	 */
	public static final List<String> ALLOWED_HEADERS = List.of(
			"Authorization",
			"Content-Type",
			"Idempotency-Key",
			"X-Login-Entry-Path",
			"X-Request-Id",
			"X-Swagger-Client-Origin",
			"X-Trace-Id"
	);

	/**
	 * 허용 Origin은 {@link AllowedOriginPolicy} 하나만 본다 — 서버의 Origin 검사
	 * ({@code LoginClientValidator})와 값이 갈리면 "CORS는 통과했는데 403"이 되어 원인을 찾기 어렵다.
	 *
	 * <p>{@code setAllowedOrigins}가 아니라 {@code setAllowedOriginPatterns}를 쓴다. 전자는
	 * {@code https://*.vercel.app} 같은 항목을 받지 못해, 프리뷰 배포를 열어야 할 때 목록을 두 벌로
	 * 갈라 담게 된다.
	 *
	 * <h2>🔴 {@link #ALLOWED_HEADERS}는 컨트롤러가 읽는 헤더와 <b>같이 늘어나야 한다</b>(33차 R1)</h2>
	 *
	 * <p>이 목록에 없는 헤더는 <b>브라우저가 본 요청을 보내기 전에 막는다.</b> 서버 코드는 그 헤더를
	 * 읽을 준비가 되어 있고 스펙에도 적혀 있는데, 요청이 도착하지 않아 아무 로그도 남지 않는다.
	 *
	 * <p><b>curl·스웨거·Postman으로는 재현되지 않는다.</b> 사전 확인(preflight)은 브라우저만 보내므로
	 * 그 도구들에서는 요청이 그대로 도착해 정상으로 보인다 — 실제로 33차에서 제출 API가 이 상태였다.
	 * {@code OpenApiDocumentTest}가 스펙의 헤더 파라미터를 이 목록과 대조해 같은 누락을 막는다.
	 */
	@Bean
	UrlBasedCorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOriginPatterns(allowedOriginPolicy.corsOriginPatterns());
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(ALLOWED_HEADERS);
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
