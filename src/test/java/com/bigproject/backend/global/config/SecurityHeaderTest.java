package com.bigproject.backend.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * 브라우저에게 내려보내는 보안 헤더를 못박아 둔다.
 *
 * <p><b>왜 테스트가 필요한가.</b> 이 헤더들은 켜져 있어도 화면에서 아무 변화가 없고, 꺼져도
 * 아무 기능이 깨지지 않는다. 즉 누군가 {@code headers().disable()}을 넣어도 <b>테스트가 없으면
 * 아무도 모른다.</b> 값이 응답에 실제로 실리는지를 여기서 고정한다.
 *
 * <p>인증이 필요 없는 {@code /api/v0/consents}로 확인한다 — 헤더는 인증 여부와 무관하게
 * 모든 응답에 붙어야 하므로 가장 단순한 경로가 낫다.
 */
@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:security-headers;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
		"jwt.access-token-expiration=900000",
		"jwt.refresh-token-expiration=1209600000",
		"auth.login.allowed-origins=http://localhost:5173",
		"auth.login.swagger-origin-override-enabled=false",
		"auth.login.swagger-ui-origin=http://localhost:8080",
		"auth.refresh-cookie.name=refresh_token",
		"auth.refresh-cookie.path=/api/v0/auth",
		"auth.refresh-cookie.secure=false",
		"auth.refresh-cookie.same-site=Lax",
		"invitation.base-url=http://localhost:5173",
		"invitation.expiration=P7D"
})
@AutoConfigureMockMvc
class SecurityHeaderTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void sendsTheHeadersThatTellBrowsersHowToTreatThisResponse() throws Exception {
		mockMvc.perform(get("/api/v0/consents"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Referrer-Policy", "same-origin"));
	}

	/**
	 * HSTS는 <b>이미 HTTPS로 온 요청에만</b> 붙는다(평문 응답에 붙여 봐야 중간자가 지울 수 있어 의미가 없다).
	 * 그래서 이 검사만 {@code secure(true)}가 필요하다 — 배포 환경에서 헤더가 안 보인다는 보고가
	 * 오면 먼저 볼 것은 이 설정이 아니라 프록시가 {@code X-Forwarded-Proto}를 넘기는지다.
	 */
	@Test
	void sendsHstsOnlyOnConnectionsThatAreAlreadyEncrypted() throws Exception {
		mockMvc.perform(get("/api/v0/consents").secure(true))
				.andExpect(header().string(
						"Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));

		mockMvc.perform(get("/api/v0/consents"))
				.andExpect(header().doesNotExist("Strict-Transport-Security"));
	}
}
