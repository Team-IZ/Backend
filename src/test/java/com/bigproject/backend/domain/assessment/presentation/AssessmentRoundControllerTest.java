package com.bigproject.backend.domain.assessment.presentation;

import com.bigproject.backend.domain.assessment.application.AssessmentRoundQueryService;
import com.bigproject.backend.domain.assessment.presentation.dto.AssessmentRoundsResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.CurrentRoundResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.ManagerResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.MembershipResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.PastRoundResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.UpcomingRoundResponse;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.global.config.ApiPathConfig;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AssessmentRoundController.class)
@Import({
		ApiPathConfig.class,
		AssessmentRoundControllerTest.MethodSecurityConfiguration.class
})
class AssessmentRoundControllerTest {
	private static final String PATH = "/api/v0/assessment-rounds";
	private static final Instant AS_OF = Instant.parse("2026-08-06T00:14:02Z");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AssessmentRoundQueryService assessmentRoundQueryService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@MockitoBean
	private AuthUserRepository authUserRepository;

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void returnsThreeSectionsAtCanonicalPath() throws Exception {
		when(assessmentRoundQueryService.getMyAssessmentRounds()).thenReturn(populatedResponse());

		mockMvc.perform(get(PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.membership.cohortName").value("7기"))
				.andExpect(jsonPath("$.membership.className").value("A반"))
				.andExpect(jsonPath("$.current.roundName").value("미프 3차"))
				.andExpect(jsonPath("$.upcoming[0].roundName").value("미프 4차"))
				.andExpect(jsonPath("$.past[0].roundName").value("미프 2차"));
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void putsTeamOnCurrentAndKeepsItOffMembership() throws Exception {
		when(assessmentRoundQueryService.getMyAssessmentRounds()).thenReturn(populatedResponse());

		mockMvc.perform(get(PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.current.teamName").value("3팀"))
				.andExpect(jsonPath("$.current.teamNumber").value("3"))
				// 팀은 회차 스코프라 기수 스코프 블록에 노출되면 안 된다.
				.andExpect(jsonPath("$.membership.teamId").doesNotExist())
				.andExpect(jsonPath("$.membership.teamName").doesNotExist());
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void exposesFieldsThatDriveTheFourStageIndicator() throws Exception {
		when(assessmentRoundQueryService.getMyAssessmentRounds()).thenReturn(populatedResponse());

		mockMvc.perform(get(PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.current.submissionStatus").value("ACCEPTED"))
				.andExpect(jsonPath("$.current.analysisPhase").value("ANALYZING"))
				.andExpect(jsonPath("$.current.initialAttemptStatus").value("ANALYZING"))
				.andExpect(jsonPath("$.current.canViewReport").value(false));
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void exposesCommitEmailStatusForTheBanner() throws Exception {
		when(assessmentRoundQueryService.getMyAssessmentRounds()).thenReturn(populatedResponse());

		mockMvc.perform(get(PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.current.commitEmailStatus").value("PENDING"))
				// 배너 강도를 프로젝트 종류로 나누려면 이 값이 함께 필요하다.
				.andExpect(jsonPath("$.current.projectCategory").value("MINI_PROJECT"));
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void returnsOkWithSynthesizedCardInsteadOfNotFound() throws Exception {
		when(assessmentRoundQueryService.getMyAssessmentRounds()).thenReturn(new AssessmentRoundsResponse(
				new MembershipResponse(null, null, null, null),
				CurrentRoundResponse.noActiveRound(AS_OF),
				List.of(),
				List.of()
		));

		mockMvc.perform(get(PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.current.representativeStatus").value("NO_ACTIVE_ROUND"))
				.andExpect(jsonPath("$.current.defaultActionCode").value("NONE"))
				.andExpect(jsonPath("$.current.assessmentRoundId").doesNotExist())
				.andExpect(jsonPath("$.upcoming").isArray())
				.andExpect(jsonPath("$.past").isArray());
	}

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void rejectsNonTrainee() throws Exception {
		mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
	}

	private static AssessmentRoundsResponse populatedResponse() {
		return new AssessmentRoundsResponse(
				new MembershipResponse(UUID.randomUUID(), "7기", UUID.randomUUID(), "A반"),
				new CurrentRoundResponse(
						UUID.randomUUID(), 3, "미프 3차", "OPEN",
						UUID.randomUUID(), "미프 3차", "MINI_PROJECT", List.of("AI_LLMOps"),
						UUID.randomUUID(), "3", "3팀",
						"ANALYZING", "WAIT_FOR_ANALYSIS", null, List.of(),
						"PENDING", List.of("GITHUB_URL", "ZIP_WITH_GITLOG"),
						"GITHUB_URL", "ACCEPTED", Instant.parse("2026-07-14T08:22:10Z"), true, true,
						"ANALYZING", "RUNNING", null,
						"ANALYZING", null, 3, null, 0,
						null, "NOT_PUBLISHED", false, "UNAVAILABLE",
						Instant.parse("2026-07-14T09:00:00Z"), null, null, null, null, null,
						"ROUND_BATCH", null,
						new ManagerResponse(UUID.randomUUID(), "김매니저"), AS_OF
				),
				List.of(new UpcomingRoundResponse(
						UUID.randomUUID(), 4, "미프 4차", "PLANNED",
						Instant.parse("2026-07-21T09:00:00Z"), null, null
				)),
				List.of(new PastRoundResponse(
						UUID.randomUUID(), 2, "미프 2차", "ASSESSMENT_COMPLETED",
						null, 1, UUID.randomUUID(), true
				))
		);
	}

	@TestConfiguration
	@EnableMethodSecurity
	static class MethodSecurityConfiguration {
	}
}
