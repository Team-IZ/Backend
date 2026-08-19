package com.bigproject.backend.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code SWAGGER_ENABLED=false}가 <b>문서 생성과 경로 허용을 함께</b> 닫는지 확인한다.
 *
 * <p>springdoc의 스위치만 끄고 {@code SecurityConfig}의 {@code permitAll} 목록을 그대로 두면
 * "문서는 껐는데 경로는 여전히 인증 없이 열려 있는" 상태가 된다. 그건 끈 것이 아니다 —
 * 실제로 확인해야 하는 것은 스위치의 존재가 아니라 <b>두 곳이 한 값으로 움직이는가</b>이다.
 *
 * <p>기본값은 {@code true}다(시연·운영자 조작이 Swagger에 의존한다). 여기서만 꺼서 확인한다.
 */
@SpringBootTest(properties = {
		"swagger.enabled=false",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:api-docs-off;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
		"invitation.expiration=P7D",
		"curriculum.storage.bucket=test-curricula",
		"submission.storage.bucket=test-submissions"
})
@AutoConfigureMockMvc
class ApiDocsDisabledTest {

	@Autowired
	private MockMvc mockMvc;

	/**
	 * 401이 나오는 것이 곧 <b>permitAll 목록에서 빠졌다</b>는 증거다. 목록에 남아 있으면
	 * 인증 없이 통과해 404(핸들러 없음)가 나온다 — 두 상태가 이 검사의 갈림길이다.
	 */
	@Test
	void closesTheApiDocumentPathsAlongWithTheDocumentItself() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
	}

	/** 문서를 닫아도 서비스는 그대로 돈다 — 끄는 것이 시연 밖의 위험이 되지 않아야 한다. */
	@Test
	void keepsTheServiceItselfWorking() throws Exception {
		mockMvc.perform(get("/api/v0/consents")).andExpect(status().isOk());
	}
}
