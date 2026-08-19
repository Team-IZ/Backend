package com.bigproject.backend.tmp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:dump-spec2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
class DumpSpecTest {

	@Autowired
	private MockMvc mockMvc;

	/**
	 * 생성된 스펙을 {@code build/api-docs.json}으로 떨군다. 프론트 요청서의 부록이 세는 수치를
	 * 직접 확인하거나 생성기에 물려 볼 때 쓴다.
	 *
	 * <p>전에는 특정 사람의 로컬 임시 폴더 절대경로가 박혀 있어 <b>다른 기계에서는 항상 실패</b>했다.
	 * 저장소 기준 상대경로로 바꾸고, 부모 디렉터리도 만들어 둔다.
	 */
	@Test
	void dump() throws Exception {
		Path target = Path.of("build", "api-docs.json");
		Files.createDirectories(target.getParent());
		Files.writeString(target, mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
	}
}
