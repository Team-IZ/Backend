package com.bigproject.backend.domain.evaluation.presentation;

import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.evaluation.application.EvaluationService;
import com.bigproject.backend.domain.evaluation.presentation.dto.ProjectEvaluationSummaryResponse;
import com.bigproject.backend.domain.evaluation.presentation.dto.TraineeEvaluationDetailResponse;
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

@WebMvcTest(controllers = ManagerEvaluationController.class)
@Import({ApiPathConfig.class, ManagerEvaluationControllerTest.MethodSecurityConfiguration.class})
class ManagerEvaluationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EvaluationService evaluationService;

	@MockitoBean
	private CurrentUserResolver currentUserResolver;

	@MockitoBean
	private JwtProvider jwtProvider;

	@MockitoBean
	private AuthUserRepository authUserRepository;

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void returnsTheResultSummary() throws Exception {
		when(evaluationService.findSummary(eq("manager@example.com"), any(UUID.class), eq(1), isNull()))
				.thenReturn(summary());

		mockMvc.perform(get("/api/v0/projects/{projectId}/evaluations", UUID.randomUUID()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reportPublished").value(false))
				.andExpect(jsonPath("$.summary.failedCount").value(1))
				.andExpect(jsonPath("$.conceptAggregates[0].notInCodeCount").value(1))
				.andExpect(jsonPath("$.trainees[0].stuckConceptCount").value(1));
	}

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void returnsTheTraineeDetail() throws Exception {
		UUID projectId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		when(evaluationService.findTraineeDetail("manager@example.com", projectId, 1, userId))
				.thenReturn(detail(userId));

		mockMvc.perform(get("/api/v0/projects/{projectId}/evaluations/{userId}", projectId, userId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resultStatus").value("AVAILABLE"))
				.andExpect(jsonPath("$.concepts[0].steps[0].axisCode").value("L1"))
				.andExpect(jsonPath("$.concepts[0].steps[0].helpCount").value(0));

		verify(evaluationService).findTraineeDetail("manager@example.com", projectId, 1, userId);
	}

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void rejectsARoundNumberBelowOne() throws Exception {
		mockMvc.perform(get("/api/v0/projects/{projectId}/evaluations", UUID.randomUUID())
						.param("roundNo", "0"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void deniesNonManagerRoles() throws Exception {
		mockMvc.perform(get("/api/v0/projects/{projectId}/evaluations", UUID.randomUUID()))
				.andExpect(status().isForbidden());
	}

	private ProjectEvaluationSummaryResponse summary() {
		UUID userId = UUID.randomUUID();
		UUID conceptId = UUID.randomUUID();
		return new ProjectEvaluationSummaryResponse(
				UUID.randomUUID(),
				"미프 3차",
				UUID.randomUUID(),
				1,
				"3차",
				false,
				null,
				true,
				new ProjectEvaluationSummaryResponse.Summary(2, 1, 1, 1, 0),
				List.of(),
				List.of(new ProjectEvaluationSummaryResponse.ConceptAggregate(
						conceptId, "HITL 트리거", 1, 1, 1,
						List.of(new ProjectEvaluationSummaryResponse.Person(userId, "김민준")),
						List.of(new ProjectEvaluationSummaryResponse.Person(userId, "김민준")))),
				List.of(new ProjectEvaluationSummaryResponse.Trainee(
						userId, "김민준", UUID.randomUUID(), "A반", "AVAILABLE", 1,
						List.of(new ProjectEvaluationSummaryResponse.ConceptOutcome(
								conceptId, "HITL 트리거", 1, true, 1, true))))
		);
	}

	private TraineeEvaluationDetailResponse detail(UUID userId) {
		return new TraineeEvaluationDetailResponse(
				UUID.randomUUID(),
				UUID.randomUUID(),
				1,
				userId,
				"김민준",
				UUID.randomUUID(),
				"A반",
				false,
				"AVAILABLE",
				List.of(new TraineeEvaluationDetailResponse.Concept(
						UUID.randomUUID(), "HITL 트리거", 1, true, 1, true,
						List.of(new TraineeEvaluationDetailResponse.Step(
								"L1", 1, true, 0, 4, "요소들이 어떻게 이어지는지 설명했다"))))
		);
	}

	@TestConfiguration
	@EnableMethodSecurity
	static class MethodSecurityConfiguration {
	}
}
