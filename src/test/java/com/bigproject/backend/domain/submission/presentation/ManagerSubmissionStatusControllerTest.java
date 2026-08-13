package com.bigproject.backend.domain.submission.presentation;

import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.submission.application.SubmissionStatusService;
import com.bigproject.backend.domain.submission.presentation.dto.ProjectSubmissionStatusResponse;
import com.bigproject.backend.global.config.ApiPathConfig;
import com.bigproject.backend.global.security.CurrentUserResolver;
import com.bigproject.backend.global.security.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ManagerSubmissionStatusController.class)
@Import({ApiPathConfig.class, ManagerSubmissionStatusControllerTest.MethodSecurityConfiguration.class})
class ManagerSubmissionStatusControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SubmissionStatusService submissionStatusService;

	@MockitoBean
	private CurrentUserResolver currentUserResolver;

	@MockitoBean
	private JwtProvider jwtProvider;

	@MockitoBean
	private AuthUserRepository authUserRepository;

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void returnsTheTwoLayerSubmissionStatus() throws Exception {
		when(submissionStatusService.findSubmissionStatus(eq("manager@example.com"), any(UUID.class), eq(1), isNull()))
				.thenReturn(response());

		mockMvc.perform(get("/api/v0/projects/{projectId}/submissions", UUID.randomUUID()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.teamFormationStage").value("CONFIRMED"))
				.andExpect(jsonPath("$.submissionOpened").value(true))
				.andExpect(jsonPath("$.summary.submittedTeamCount").value(1))
				.andExpect(jsonPath("$.teams[0].teamName").value("3팀"))
				.andExpect(jsonPath("$.teams[0].members[0].attendanceStatus").value("OPEN"));
	}

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void defaultsToTheFirstRound() throws Exception {
		// 프론트 URL이 /manager/projects/:id 하나뿐이라 회차를 안 넘기는 호출이 기본이다.
		when(submissionStatusService.findSubmissionStatus(any(), any(UUID.class), eq(1), isNull()))
				.thenReturn(response());
		UUID projectId = UUID.randomUUID();

		mockMvc.perform(get("/api/v0/projects/{projectId}/submissions", projectId))
				.andExpect(status().isOk());

		verify(submissionStatusService).findSubmissionStatus("manager@example.com", projectId, 1, null);
	}

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void rejectsARoundNumberBelowOne() throws Exception {
		mockMvc.perform(get("/api/v0/projects/{projectId}/submissions", UUID.randomUUID())
						.param("roundNo", "0"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void deniesNonManagerRoles() throws Exception {
		mockMvc.perform(get("/api/v0/projects/{projectId}/submissions", UUID.randomUUID()))
				.andExpect(status().isForbidden());
	}

	private ProjectSubmissionStatusResponse response() {
		return new ProjectSubmissionStatusResponse(
				UUID.randomUUID(),
				"미프 3차",
				UUID.randomUUID(),
				1,
				"3차",
				Instant.parse("2026-07-14T14:59:00Z"),
				"CONFIRMED",
				true,
				false,
				0L,
				new ProjectSubmissionStatusResponse.Summary(1, 1, 0, 0),
				List.of(new ProjectSubmissionStatusResponse.Requirement(
						UUID.randomUUID(), "HITL", 1, "HITL 트리거", "설명")),
				List.of(new ProjectSubmissionStatusResponse.Team(
						UUID.randomUUID(),
						UUID.randomUUID(),
						"A반",
						"3",
						"3팀",
						"CONFIRMED",
						new ProjectSubmissionStatusResponse.Submission(
								UUID.randomUUID(),
								Instant.parse("2026-07-13T14:55:00Z"),
								"GITHUB_URL",
								"ACCEPTED",
								UUID.randomUUID(),
								"김민준",
								"https://github.com/team-a/mif3-3"),
						new ProjectSubmissionStatusResponse.Analysis(
								UUID.randomUUID(), "SUCCEEDED", null, null),
						List.of(new ProjectSubmissionStatusResponse.RequirementResult(
								UUID.randomUUID(), "HITL", "HITL 트리거", "PASS",
								"hitl/trigger.py:14-22", true)),
						List.of(new ProjectSubmissionStatusResponse.Member(
								UUID.randomUUID(),
								"김민준",
								"OPEN",
								Instant.parse("2026-07-16T14:59:00Z"),
								null)))));
	}

	@TestConfiguration
	@EnableMethodSecurity
	static class MethodSecurityConfiguration {
	}
}
