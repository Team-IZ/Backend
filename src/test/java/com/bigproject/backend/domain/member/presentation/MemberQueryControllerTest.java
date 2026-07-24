package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.application.MemberQueryService;
import com.bigproject.backend.domain.member.application.TraineeCsvParser;
import com.bigproject.backend.domain.member.application.TraineeCsvRow;
import com.bigproject.backend.domain.member.domain.MemberSortField;
import com.bigproject.backend.domain.member.domain.SortDirection;
import com.bigproject.backend.domain.member.presentation.dto.ManagerSummaryResponse;
import com.bigproject.backend.domain.member.domain.TraineeInvitationFailureStatus;
import com.bigproject.backend.domain.member.presentation.dto.MemberListResponse;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import com.bigproject.backend.domain.member.presentation.dto.TraineeListResponse;
import com.bigproject.backend.global.config.ApiPathConfig;
import com.bigproject.backend.global.security.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
	private TraineeCsvParser traineeCsvParser;

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
		UUID managerId = UUID.randomUUID();
		when(memberQueryService.findManagers(
				any(), isNull(), isNull(), isNull(), anyInt(), anyInt(), any(), any(), anyString()
		)).thenReturn(new MemberListResponse(List.of(
				new ManagerSummaryResponse(
						managerId,
						"홍길동",
						"manager@example.com",
						"담당",
						List.of("7기"),
						"활성화",
						LocalDate.of(2026, 7, 24)
				)
		), 0, 20, 1, 1));

		mockMvc.perform(get("/api/v0/members")
						.param("organizationId", organizationId.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].memberId").value(managerId.toString()))
				.andExpect(jsonPath("$.content[0].name").value("홍길동"))
				.andExpect(jsonPath("$.content[0].email").value("manager@example.com"))
				.andExpect(jsonPath("$.content[0].role").value("담당"))
				.andExpect(jsonPath("$.content[0].cohortNames[0]").value("7기"))
				.andExpect(jsonPath("$.content[0].status").value("활성화"))
				.andExpect(jsonPath("$.content[0].lastLoginDate").value("2026-07-24"))
				.andExpect(jsonPath("$.content[0].lastLoginAt").doesNotExist())
				.andExpect(jsonPath("$.content[0].assignments").doesNotExist());

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
	void uploadsTraineeCsvAtCanonicalCohortPath() throws Exception {
		UUID cohortId = UUID.randomUUID();
		MockMultipartFile file = new MockMultipartFile(
				"file",
				"trainees.csv",
				"text/csv",
				"이름,이메일\n교육생,trainee@example.com\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
		);
		List<TraineeCsvRow> rows = List.of(new TraineeCsvRow(2, "교육생", "trainee@example.com"));
		when(traineeCsvParser.parse(any())).thenReturn(rows);
		when(memberInvitationService.inviteTraineesFromCsv(cohortId, rows, "lead@example.com", null))
				.thenReturn(new RegisterTraineesResponse(1, 1, 1, List.of()));

		mockMvc.perform(multipart("/api/v0/cohorts/{cohortId}/trainees", cohortId)
						.file(file)
						.with(csrf()))
				.andExpect(status().isCreated());

		mockMvc.perform(multipart("/api/v0/members/cohorts/{cohortId}/trainees", cohortId)
						.file(file)
						.with(csrf()))
				.andExpect(status().isNotFound());

		verify(memberInvitationService).inviteTraineesFromCsv(cohortId, rows, "lead@example.com", null);
	}

	@Test
	@WithMockUser(username = "lead@example.com", roles = "LEAD_MANAGER")
	void directlyInvitesMultipleTraineesWithJson() throws Exception {
		UUID cohortId = UUID.randomUUID();
		RegisterTraineesRequest request = new RegisterTraineesRequest(List.of(
				new RegisterTraineesRequest.Trainee("교육생1", "trainee1@example.com"),
				new RegisterTraineesRequest.Trainee("교육생2", "trainee2@example.com")
		));
		when(memberInvitationService.inviteTrainees(cohortId, request, "lead@example.com", "direct-1"))
				.thenReturn(new RegisterTraineesResponse(2, 2, 2, List.of()));

		mockMvc.perform(post("/api/v0/cohorts/{cohortId}/trainees/invitations", cohortId)
						.contentType(MediaType.APPLICATION_JSON)
						.header("X-Request-Id", "direct-1")
						.content("""
								{
								  "trainees": [
								    {"name": "교육생1", "email": "trainee1@example.com"},
								    {"name": "교육생2", "email": "trainee2@example.com"}
								  ]
								}
								""")
						.with(csrf()))
				.andExpect(status().isCreated());

		verify(memberInvitationService).inviteTrainees(cohortId, request, "lead@example.com", "direct-1");
	}

	@Test
	@WithMockUser(username = "lead@example.com", roles = "LEAD_MANAGER")
	void returnsRowFailureForInvalidDirectTraineeEmail() throws Exception {
		UUID cohortId = UUID.randomUUID();
		RegisterTraineesRequest request = new RegisterTraineesRequest(List.of(
				new RegisterTraineesRequest.Trainee("홍길동", "fdsafsdafsafsaffa")
		));
		when(memberInvitationService.inviteTrainees(cohortId, request, "lead@example.com", null))
				.thenReturn(new RegisterTraineesResponse(
						1,
						0,
						0,
						List.of(new RegisterTraineesResponse.Failure(
								1,
								"fdsafsdafsafsaffa",
								TraineeInvitationFailureStatus.INVALID_EMAIL_FORMAT.code()
						))
				));

		mockMvc.perform(post("/api/v0/cohorts/{cohortId}/trainees/invitations", cohortId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "trainees": [
								    {"name": "홍길동", "email": "fdsafsdafsafsaffa"}
								  ]
								}
								""")
						.with(csrf()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.requestedCount").value(1))
				.andExpect(jsonPath("$.registeredCount").value(0))
				.andExpect(jsonPath("$.invitationSentCount").value(0))
				.andExpect(jsonPath("$.failures[0].row").value(1))
				.andExpect(jsonPath("$.failures[0].email").value("fdsafsdafsafsaffa"))
				.andExpect(jsonPath("$.failures[0].status").value(1));

		verify(memberInvitationService).inviteTrainees(cohortId, request, "lead@example.com", null);
	}

	@Test
	@WithMockUser(username = "lead@example.com", roles = "LEAD_MANAGER")
	void rejectsBlankDirectTraineeName() throws Exception {
		mockMvc.perform(post("/api/v0/cohorts/{cohortId}/trainees/invitations", UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "trainees": [
								    {"name": "", "email": "trainee@example.com"}
								  ]
								}
								""")
						.with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableMethodSecurity
	static class MethodSecurityConfiguration {
	}
}
