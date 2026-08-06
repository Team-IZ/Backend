package com.bigproject.backend.domain.submission.presentation;

import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.submission.application.SubmissionService;
import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import com.bigproject.backend.domain.submission.domain.SubmissionMethod;
import com.bigproject.backend.domain.submission.domain.SubmissionStatus;
import com.bigproject.backend.domain.submission.presentation.dto.CreateGithubSubmissionRequest;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisPhase;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionAnalysisResponse;
import com.bigproject.backend.domain.submission.presentation.dto.SubmissionResponse;
import com.bigproject.backend.global.config.ApiPathConfig;
import com.bigproject.backend.global.security.CurrentUserResolver;
import com.bigproject.backend.global.security.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SubmissionController.class)
@Import({ApiPathConfig.class, SubmissionControllerTest.MethodSecurityConfiguration.class})
class SubmissionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SubmissionService submissionService;

	@MockitoBean
	private CurrentUserResolver currentUserResolver;

	@MockitoBean
	private JwtProvider jwtProvider;

	@MockitoBean
	private AuthUserRepository authUserRepository;

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void acceptsGithubUrlSubmissionAsJson() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID submissionId = UUID.randomUUID();
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(userId);
		when(submissionService.submitGithubUrl(eq(userId), any(CreateGithubSubmissionRequest.class), any(UUID.class)))
				.thenReturn(new SubmissionResponse(
						submissionId,
						SubmissionMethod.GITHUB_URL,
						SubmissionStatus.ACCEPTED,
						Instant.parse("2026-08-06T09:00:00Z"),
						true,
						null,
						null,
						null
				));

		mockMvc.perform(post("/api/v0/submissions")
						.with(csrf())
						.header("Idempotency-Key", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "assessmentRoundId": "%s",
								  "repositoryUrl": "https://github.com/team-iz/mini-project-3",
								  "branch": "main"
								}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.submissionId").value(submissionId.toString()))
				// 저장소 접근은 마감 후 분석 단계라 접수는 즉시 ACCEPTED다.
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				// 확인 실행 행은 분석 배치가 만든다(S-12). 제출 응답에는 아직 없다 — 여기에 값이 실리면
				// 프론트가 "저장소 확인이 끝났다"로 읽는다.
				.andExpect(jsonPath("$.repositoryVerificationId").doesNotExist());
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void rejectsBlankRepositoryUrl() throws Exception {
		mockMvc.perform(post("/api/v0/submissions")
						.with(csrf())
						.header("Idempotency-Key", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"assessmentRoundId": "%s", "repositoryUrl": "  "}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isBadRequest());
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void receivesZipUploadOnItsOwnPath() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID submissionId = UUID.randomUUID();
		UUID artifactId = UUID.randomUUID();
		UUID roundId = UUID.randomUUID();
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(userId);
		when(submissionService.submitZip(eq(userId), eq(roundId), any(), any(UUID.class)))
				.thenReturn(new SubmissionResponse(
						submissionId,
						SubmissionMethod.ZIP_WITH_GITLOG,
						SubmissionStatus.VALIDATING,
						Instant.parse("2026-08-06T09:00:00Z"),
						true,
						null,
						null,
						artifactId
				));

		mockMvc.perform(multipart("/api/v0/submissions/zip")
						.file(new MockMultipartFile("file", "project.zip", "application/zip", new byte[] {1, 2, 3}))
						.param("assessmentRoundId", roundId.toString())
						.header("Idempotency-Key", UUID.randomUUID())
						.with(csrf()))
				.andExpect(status().isAccepted())
				// 내용 검증과 안전 추출이 남아 있어 접수는 VALIDATING에서 끝난다.
				.andExpect(jsonPath("$.status").value("VALIDATING"))
				.andExpect(jsonPath("$.artifactId").value(artifactId.toString()));
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void reportsNotStartedBeforeTheAnalysisBatchRuns() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID submissionId = UUID.randomUUID();
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(userId);
		when(submissionService.getAnalysis(userId, submissionId))
				.thenReturn(SubmissionAnalysisResponse.notStarted(submissionId));

		mockMvc.perform(get("/api/v0/submissions/{submissionId}/analysis", submissionId))
				.andExpect(status().isOk())
				// 분석은 마감 후 배치라 마감 전 조회는 정상적으로 NOT_STARTED다.
				.andExpect(jsonPath("$.phase").value(SubmissionAnalysisPhase.NOT_STARTED.name()))
				.andExpect(jsonPath("$.analysisJobId").doesNotExist());
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void surfacesRepositoryFailureThroughTheAnalysisEndpoint() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID submissionId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(userId);
		when(submissionService.getAnalysis(userId, submissionId))
				.thenReturn(new SubmissionAnalysisResponse(
						submissionId,
						SubmissionAnalysisPhase.FAILED,
						jobId,
						1,
						Instant.parse("2026-08-06T10:00:00Z"),
						Instant.parse("2026-08-06T10:01:00Z"),
						"REPO_NOT_FOUND",
						"저장소를 찾을 수 없습니다.",
						null
				));

		mockMvc.perform(get("/api/v0/submissions/{submissionId}/analysis", submissionId))
				.andExpect(status().isOk())
				// 저장소 주소 오류는 제출 응답이 아니라 분석 실패 코드로 드러난다.
				.andExpect(jsonPath("$.failureCode").value("REPO_NOT_FOUND"));
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void returnsDomainErrorCodeWhenDeadlineHasPassed() throws Exception {
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(UUID.randomUUID());
		when(submissionService.submitGithubUrl(any(), any(), any()))
				.thenThrow(new SubmissionException(SubmissionErrorCode.SUBMISSION_DEADLINE_PASSED));

		mockMvc.perform(post("/api/v0/submissions")
						.with(csrf())
						.header("Idempotency-Key", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"assessmentRoundId": "%s", "repositoryUrl": "https://github.com/team-iz/p"}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SUBMISSION_DEADLINE_PASSED"));
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void rejectsSubmissionWithoutAnIdempotencyKey() throws Exception {
		// 서버가 대신 만들어 주면 멱등 판정이 항상 실패하는데 클라이언트에게는 그 사실이 보이지 않는다.
		mockMvc.perform(post("/api/v0/submissions")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"assessmentRoundId": "%s", "repositoryUrl": "https://github.com/team-iz/p"}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void rejectsAnIdempotencyKeyThatIsNotAUuid() throws Exception {
		// request_idempotency_key 컬럼이 UUID라 임의 문자열은 저장 단계에서 원인을 알기 어려운 500이 된다.
		mockMvc.perform(post("/api/v0/submissions")
						.with(csrf())
						.header("Idempotency-Key", "retry-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"assessmentRoundId": "%s", "repositoryUrl": "https://github.com/team-iz/p"}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void requiresAnIdempotencyKeyOnZipUploadsToo() throws Exception {
		// 중복 위험은 오히려 ZIP 쪽이 크다 -- 업로드가 느려 재시도가 잦다.
		mockMvc.perform(multipart("/api/v0/submissions/zip")
						.file(new MockMultipartFile("file", "project.zip", "application/zip", new byte[] {1}))
						.param("assessmentRoundId", UUID.randomUUID().toString())
						.with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
	}

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void deniesNonTraineeRoles() throws Exception {
		mockMvc.perform(get("/api/v0/submissions/{submissionId}/analysis", UUID.randomUUID()))
				.andExpect(status().isForbidden());
	}

	@TestConfiguration
	@EnableMethodSecurity
	static class MethodSecurityConfiguration {
	}
}
