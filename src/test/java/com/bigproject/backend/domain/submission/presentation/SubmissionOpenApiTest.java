package com.bigproject.backend.domain.submission.presentation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 코드 제출 API가 Swagger에 제대로 문서화되는지 산출물에서 직접 확인한다.
 *
 * <p>한때 JSON과 multipart를 {@code POST /submissions} 한 경로에 {@code consumes}로만 갈라 두었는데,
 * OpenAPI는 경로·메서드당 operation이 하나뿐이라 springdoc이 둘을 하나로 병합했다. 그 결과 Swagger UI에서
 * {@code application/json}을 골라도 multipart 입력 폼이 뜨고, ZIP 전용 쿼리 파라미터가 JSON 쪽에도
 * {@code required}로 붙었다. 경로를 나눠 해결했고, 이 테스트가 그 상태를 고정한다.
 */
@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:submission-openapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
		"jwt.access-token-expiration=1800000",
		"jwt.refresh-token-expiration=604800000",
		"auth.login.allowed-origins=http://localhost:5173",
		"auth.login.swagger-origin-override-enabled=false",
		"auth.login.swagger-ui-origin=http://localhost:8080",
		"auth.refresh-cookie.name=refresh_token",
		"auth.refresh-cookie.path=/",
		"auth.refresh-cookie.secure=false",
		"auth.refresh-cookie.same-site=Lax",
		"invitation.base-url=http://localhost:5173",
		"invitation.expiration=P7D"
})
@AutoConfigureMockMvc
class SubmissionOpenApiTest {

	private static final String GITHUB_POST = "$.paths['/api/v0/submissions'].post";
	private static final String ZIP_POST = "$.paths['/api/v0/submissions/zip'].post";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void documentsTheGithubSubmitEndpointAsJsonOnly() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(GITHUB_POST + ".requestBody.content['application/json'].schema.$ref")
						.value("#/components/schemas/CreateGithubSubmissionRequest"))
				// multipart가 섞여 들어오면 Swagger UI가 JSON 본문 자리에 파일 입력 폼을 그린다.
				.andExpect(jsonPath(GITHUB_POST + ".requestBody.content['multipart/form-data']").doesNotExist());
	}

	@Test
	void asksForRepositoryUrlAndBranchOnTheGithubSchema() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.schemas.CreateGithubSubmissionRequest.properties.repositoryUrl")
						.exists())
				.andExpect(jsonPath("$.components.schemas.CreateGithubSubmissionRequest.properties.branch")
						.exists())
				.andExpect(jsonPath("$.components.schemas.CreateGithubSubmissionRequest.properties.file")
						.doesNotExist());
	}

	@Test
	void keepsTheZipOnlyQueryParameterOffTheGithubEndpoint() throws Exception {
		// 병합됐을 때 assessmentRoundId 가 required=true 쿼리 파라미터로 JSON 쪽에도 붙어 있었다.
		// JSON 표현은 그 값을 본문으로 받으므로 쿼리로 또 요구하면 안 된다.
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(GITHUB_POST + ".parameters[?(@.name == 'assessmentRoundId')]").isEmpty());
	}

	@Test
	void advertisesTheIdempotencyKeyAsRequiredOnBothSubmitPaths() throws Exception {
		// 런타임은 @RequestHeader(required = false)로 받아 도메인 에러 코드로 거절한다. Swagger가 이를
		// 선택 파라미터로 그리면 프론트가 헤더를 빠뜨린 채 구현하게 되므로 문서에서는 필수여야 한다.
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(GITHUB_POST + ".parameters[?(@.name == 'Idempotency-Key')].required")
						.value(true))
				.andExpect(jsonPath(ZIP_POST + ".parameters[?(@.name == 'Idempotency-Key')].required")
						.value(true));
	}

	@Test
	void marksTheZipEndpointAsOnHoldButNotTheGithubOne() throws Exception {
		// AI 서버의 POST /api/v0/analyses 에 ZIP 을 전달할 필드가 없어 분석까지 이어지지 않는다.
		// 접수만 되고 영원히 VALIDATING 에 머무르므로 프론트가 연동하지 않도록 문서에 표시한다.
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(ZIP_POST + ".deprecated").value(true))
				.andExpect(jsonPath(ZIP_POST + ".summary").value(org.hamcrest.Matchers.startsWith("[구현 보류]")))
				// GitHub 경로는 현재 유일하게 쓸 수 있는 제출 수단이라 함께 묻히면 안 된다.
				.andExpect(jsonPath(GITHUB_POST + ".deprecated").doesNotExist());
	}

	@Test
	void documentsTheZipEndpointAsMultipartOnItsOwnPath() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(ZIP_POST + ".requestBody.content['multipart/form-data'].schema.properties.file")
						.exists())
				.andExpect(jsonPath(ZIP_POST + ".requestBody.content['application/json']").doesNotExist())
				.andExpect(jsonPath(ZIP_POST + ".parameters[?(@.name == 'assessmentRoundId')]").isNotEmpty());
	}
}
