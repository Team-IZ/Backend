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
 * 코드 분석 상태 조회 API(TR-02) 문서화가 산출물에 실제로 반영되는지 확인한다.
 *
 * <p>{@code code-analysis-issue-resolution-request.md}(2026-08-21)가 지적한 갭 — {@code operationId}
 * 누락, {@code failureCode}가 순수 {@code String}이라 enum이 스펙에 안 잡히는 것 — 을 고정한다.
 * 애너테이션이 컴파일된다고 스펙에 반영되는 것은 아니므로({@code @Schema(allowableValues=...)}를
 * 엉뚱한 위치에 적어도 컴파일은 통과한다), 실제 {@code /v3/api-docs} 산출물을 본다.
 */
@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:submission-analysis-openapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
		"invitation.expiration=P7D",
		"curriculum.storage.bucket=test-curricula",
		"submission.storage.bucket=test-submissions"
})
@AutoConfigureMockMvc
class SubmissionAnalysisOpenApiTest {

	private static final String ANALYSIS_GET = "$.paths['/api/v0/submissions/{submissionId}/analysis'].get";
	private static final String ANALYSIS_RESULT_GET =
			"$.paths['/api/v0/submissions/{submissionId}/analysis/result'].get";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void assignsOperationIdsToBothAnalysisEndpoints() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(ANALYSIS_GET + ".operationId").value("getSubmissionAnalysis"))
				.andExpect(jsonPath(ANALYSIS_RESULT_GET + ".operationId").value("getSubmissionAnalysisResult"));
	}

	@Test
	void documentsFailureCodeAsAnEnumWithAllFourteenValues() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.schemas.SubmissionAnalysisResponse.properties.failureCode.enum",
						org.hamcrest.Matchers.containsInAnyOrder(
								"TEMPORARY_ERROR", "ANALYSIS_TIMEOUT", "MODEL_ERROR", "SOURCE_UNREACHABLE",
								"UNSUPPORTED_LANGUAGE",
								"INVALID_REPOSITORY_URL", "REPO_NOT_FOUND", "REPOSITORY_ACCESS_DENIED",
								"BRANCH_NOT_FOUND", "UNSUPPORTED_HOST",
								"EMPTY_CODE", "GIT_LOG_MISSING",
								"SESSION_PREPARATION_FAILED", "EXTERNAL_JOB_ID_LOST")));
	}

	@Test
	void documentsMultiplePhaseExamplesOnTheAnalysisEndpoint() throws Exception {
		// getAnalysis()의 성공 응답에 대표 phase별 예시가 여러 개 실려 있는지 — 설명 프로즈뿐이던
		// 상태에서 실제 예시 응답으로 확장했다.
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(ANALYSIS_GET + ".responses.200.content['application/json'].examples",
						org.hamcrest.Matchers.aMapWithSize(org.hamcrest.Matchers.greaterThanOrEqualTo(5))));
	}
}
