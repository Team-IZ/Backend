package com.bigproject.backend.global.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 없는 경로가 어떤 상태로 응답하는지 못박아 둔다.
 *
 * <p><b>왜 테스트로 남기는가.</b> 프론트의 401 처리는 "토큰이 만료됐다 → 갱신하거나 로그아웃한다"이다.
 * 그런데 <b>없는 경로</b>까지 401로 답하면 오타 하나가 강제 로그아웃으로 이어지고, 로그에는
 * 진짜 만료와 구분되지 않는 401만 남아 원인을 찾기 어렵다. 그래서 둘은 갈려야 한다.
 *
 * <ul>
 *   <li>토큰이 <b>없으면</b> 401 — 시큐리티 필터가 먼저 잡는다. 경로 존재 여부는 알려 주지 않는다.</li>
 *   <li>토큰이 <b>유효하면</b> 404 — 필터를 통과해 디스패처까지 가고, 핸들러가 없으니 404다.</li>
 * </ul>
 *
 * <p>인증된 사용자에게만 경로 존재 여부를 알려 주므로 정보가 새지 않는다.
 */
@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:unmapped-path;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
class UnmappedPathStatusTest {

	private static final String MISSING_PATH = "/api/v0/definitely-not-a-real-endpoint";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 토큰이_없으면_없는_경로도_401이다() throws Exception {
		// 인증 전에는 경로 존재 여부를 알려 주지 않는다. 이건 의도된 동작이다.
		mockMvc.perform(get(MISSING_PATH)).andExpect(status().isUnauthorized());
	}

	@Test
	void 인증되면_없는_경로는_404다() throws Exception {
		// 실제 JWT 대신 인증 컨텍스트를 직접 심는다 — 여기서 확인하려는 것은 토큰 파싱이 아니라
		// "인증을 통과한 요청이 없는 경로에 닿으면 무슨 상태가 나오는가"이고,
		// 실제 토큰을 쓰면 필터가 DB에서 계정을 조회하느라 이 질문과 무관한 준비가 필요해진다.
		//
		// 404여야 프론트가 "경로 오타"와 "토큰 만료"를 구분할 수 있다.
		// 여기가 401이면 프론트 오타 하나가 강제 로그아웃이 된다.
		mockMvc.perform(get(MISSING_PATH).with(user("lead@example.com").roles("OPERATOR")))
				.andExpect(status().isNotFound());
	}
}
