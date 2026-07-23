package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.application.MemberQueryService;
import com.bigproject.backend.domain.member.domain.MemberSortField;
import com.bigproject.backend.domain.member.domain.SortDirection;
import com.bigproject.backend.domain.member.presentation.dto.MemberListResponse;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import com.bigproject.backend.domain.member.presentation.dto.TraineeListResponse;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {MemberController.class, TraineeController.class})
@Import({ApiPathConfig.class, MemberQueryControllerTest.MethodSecurityConfiguration.class})
class MemberQueryControllerTest {
	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MemberQueryService memberQueryService;

	@MockitoBean
	private MemberInvitationService memberInvitationService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	void requiresAuthenticationForManagerDirectory() throws Exception {
		mockMvc.perform(get("/api/v0/members"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@WithMockUser(username = "admin@example.com", roles = "SUPER_ADMIN")
	void superAdminReadsManagerDirectoryWithOrganization() throws Exception {
		UUID organizationId = UUID.randomUUID();
		when(memberQueryService.findManagers(
				any(), isNull(), isNull(), isNull(), anyInt(), anyInt(), any(), any(), anyString()
		)).thenReturn(new MemberListResponse(List.of(), 0, 20, 0, 0));

		mockMvc.perform(get("/api/v0/members")
						.param("organizationId", organizationId.toString()))
				.andExpect(status().isOk());

		verify(memberQueryService).findManagers(
				organizationId,
				null,
				null,
				null,
				0,
				20,
				MemberSortField.NAME,
				SortDirection.ASC,
				"admin@example.com"
		);
	}

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void managerCannotReadManagerDirectory() throws Exception {
		mockMvc.perform(get("/api/v0/members"))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "lead@example.com", roles = "LEAD_MANAGER")
	void validatesManagerDirectoryQueryParameters() throws Exception {
		mockMvc.perform(get("/api/v0/members")
						.param("organizationId", "not-a-uuid"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/v0/members")
						.param("size", "101"))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/v0/members")
						.param("query", "x".repeat(201)))
				.andExpect(status().isBadRequest());
	}

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void managerReadsTraineeRoster() throws Exception {
		UUID cohortId = UUID.randomUUID();
		when(memberQueryService.findTrainees(any(), isNull(), isNull(), isNull(), anyInt(), anyInt(), anyString()))
				.thenReturn(new TraineeListResponse(List.of(), 0, 20, 0, 0));

		mockMvc.perform(get("/api/v0/cohorts/{cohortId}/trainees", cohortId))
				.andExpect(status().isOk());
	}

	@Test
	@WithMockUser(username = "admin@example.com", roles = "SUPER_ADMIN")
	void superAdminCannotReadTraineeRoster() throws Exception {
		mockMvc.perform(get("/api/v0/cohorts/{cohortId}/trainees", UUID.randomUUID()))
				.andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "lead@example.com", roles = "LEAD_MANAGER")
	void registersTraineesOnlyAtCanonicalCohortPath() throws Exception {
		UUID cohortId = UUID.randomUUID();
		when(memberInvitationService.inviteTrainees(any(), any(), anyString(), isNull()))
				.thenReturn(new RegisterTraineesResponse(1, 1, 1, List.of()));
		String request = """
				{"trainees":[{"name":"교육생","email":"trainee@example.com","classroomId":null}]}
				""";

		mockMvc.perform(post("/api/v0/cohorts/{cohortId}/trainees", cohortId)
						.with(csrf())
						.contentType("application/json")
						.content(request))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v0/members/cohorts/{cohortId}/trainees", cohortId)
						.with(csrf())
						.contentType("application/json")
						.content(request))
				.andExpect(status().isNotFound());
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableMethodSecurity
	static class MethodSecurityConfiguration {
	}
}
