package com.bigproject.backend.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 프리플라이트가 <b>어느 Origin에서 통과하는지</b>를 못박아 둔다.
 *
 * <p><b>왜 테스트로 남기는가.</b> 이 목록에 없으면 CORS 오류가 아니라 <b>로그인 자체가 안 된다</b> —
 * 브라우저가 응답을 안 넘겨주는 것이 아니라 서버가 403으로 거절하기 때문이다. 그래서
 * "CORS 설정이라 배포 직전에 하면 된다"가 성립하지 않고, 목록이 조용히 줄면 배포하는 날
 * 아무것도 못 하게 된다.
 *
 * <p>로컬 오리진이 5173 하나뿐이던 시절의 증상이 특히 고약했다. Vite는 5173이 이미 쓰이면
 * <b>말없이 다음 포트로 올라가는데</b>, 그러면 "로그인이 갑자기 안 된다"로 보이고 원인이 코드가
 * 아니라 포트라 찾는 데 오래 걸린다.
 */
@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:login-origin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
		"jwt.access-token-expiration=3600000",
		"jwt.refresh-token-expiration=604800000",
		// application.yaml의 기본 허용 목록과 같은 값이어야 한다.
		"auth.login.allowed-origins=https://frontend-eight-neon-73.vercel.app,http://localhost:5173,"
				+ "http://localhost:5174,http://localhost:5175,http://127.0.0.1:5173,http://localhost:4173",
		"auth.login.swagger-origin-override-enabled=false",
		"auth.login.swagger-ui-origin=http://localhost:8080",
		"auth.refresh-cookie.name=refresh_token",
		"auth.refresh-cookie.path=/api/v0/auth",
		"auth.refresh-cookie.secure=true",
		"auth.refresh-cookie.same-site=None",
		"invitation.base-url=http://localhost:5173",
		"invitation.expiration=P7D"
})
@AutoConfigureMockMvc
class LoginOriginPreflightTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 배포_도메인_프리플라이트가_통과한다() throws Exception {
		preflight("https://frontend-eight-neon-73.vercel.app")
				.andExpect(status().isOk())
				// 리프레시 쿠키를 주고받으려면 이 헤더가 있어야 한다.
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"))
				.andExpect(header().string(
						"Access-Control-Allow-Origin", "https://frontend-eight-neon-73.vercel.app"));
	}

	@Test
	void 밀려난_포트와_127_0_0_1과_vite_preview도_통과한다() throws Exception {
		for (String origin : new String[]{
				"http://localhost:5173",
				"http://localhost:5174",
				"http://localhost:5175",
				"http://127.0.0.1:5173",
				"http://localhost:4173"
		}) {
			preflight(origin)
					.andExpect(status().isOk())
					.andExpect(header().string("Access-Control-Allow-Origin", origin));
		}
	}

	@Test
	void 목록에_없는_Origin은_프리플라이트에서_잘린다() throws Exception {
		preflight("https://evil.example.com").andExpect(status().isForbidden());
	}

	/**
	 * 33차 R1 — <b>제출이 통째로 막혀 있던 자리를 그대로 재현한다.</b>
	 *
	 * <p>프론트가 보낸 {@code curl -X OPTIONS}와 같은 요청이다. 종전에는 허용 목록에
	 * {@code Idempotency-Key}가 없어 응답의 {@code Access-Control-Allow-Headers}에 그 헤더가
	 * 빠졌고, 브라우저는 <b>본 요청(POST)을 아예 보내지 않았다.</b>
	 *
	 * <p>스펙이 그 헤더를 필수로 요구하므로 빼면 400이고 넣으면 브라우저가 막아, 프론트에
	 * 고를 수 있는 선택지가 없는 상태였다.
	 *
	 * <p><b>이 검사는 여기서만 가능하다.</b> 사전 확인은 브라우저만 보내므로 curl·스웨거로
	 * 본 요청을 직접 던지면 언제나 정상으로 보인다.
	 */
	@Test
	void 제출_사전확인이_Idempotency_Key를_허용한다() throws Exception {
		mockMvc.perform(options("/api/v0/submissions/zip")
						.header("Origin", "http://localhost:5173")
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "authorization,idempotency-key"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
				.andExpect(header().stringValues("Access-Control-Allow-Headers",
						org.hamcrest.Matchers.hasItem(
								org.hamcrest.Matchers.containsStringIgnoringCase("idempotency-key"))));
	}

	private org.springframework.test.web.servlet.ResultActions preflight(String origin) throws Exception {
		return mockMvc.perform(options("/api/v0/auth/login")
				.header("Origin", origin)
				.header("Access-Control-Request-Method", "POST"));
	}
}
