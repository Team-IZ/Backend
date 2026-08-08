package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.application.ManagerRosterService;
import com.bigproject.backend.domain.member.application.MemberInvitationService;
import com.bigproject.backend.domain.member.application.MemberProfileService;
import com.bigproject.backend.domain.member.application.TraineeCsvParser;
import com.bigproject.backend.domain.member.application.TraineeCsvRow;
import com.bigproject.backend.domain.member.application.TraineeRosterService;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.domain.TraineeInvitationFailureStatus;
import com.bigproject.backend.domain.member.presentation.dto.MemberProfileResponse;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import com.bigproject.backend.global.config.ApiPathConfig;
import com.bigproject.backend.global.security.CurrentUserResolver;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {MemberController.class, TraineeController.class})
@Import({ApiPathConfig.class, MemberQueryControllerTest.MethodSecurityConfiguration.class})
class MemberQueryControllerTest {
	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MemberInvitationService memberInvitationService;

	@MockitoBean
	private TraineeCsvParser traineeCsvParser;

	@MockitoBean
	private MemberProfileService memberProfileService;

	@MockitoBean
	private TraineeRosterService traineeRosterService;

	@MockitoBean
	private ManagerRosterService managerRosterService;

	@MockitoBean
	private CurrentUserResolver currentUserResolver;

	@MockitoBean
	private JwtProvider jwtProvider;

	@MockitoBean
	private AuthUserRepository authUserRepository;

	/**
	 * 새로고침하면 브라우저 메모리가 비워지는데 재발급 응답에는 토큰만 있어 역할을 알 수 없다.
	 * 역할을 브라우저 저장소에 남기지 않고 <b>부팅 → refresh → /me</b>로 세션을 복원하기 위한 경로다.
	 */
	@Test
	@WithMockUser(username = "lead@example.com", roles = "TRAINEE")
	void 역할에_상관없이_자기_정보를_돌려준다() throws Exception {
		UUID memberId = UUID.randomUUID();
		UUID organizationId = UUID.randomUUID();
		when(memberProfileService.currentMember()).thenReturn(new MemberProfileResponse(
				memberId,
				"lead@example.com",
				"홍길동",
				Role.TRAINEE,
				organizationId,
				AccountStatus.ACTIVE
		));

		mockMvc.perform(get("/api/v0/members/me"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.memberId").value(memberId.toString()))
				.andExpect(jsonPath("$.email").value("lead@example.com"))
				.andExpect(jsonPath("$.name").value("홍길동"))
				.andExpect(jsonPath("$.role").value("TRAINEE"))
				.andExpect(jsonPath("$.organizationId").value(organizationId.toString()))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	/**
	 * 매니저 목록은 {@code GET /managers}로 옮겼다. 이 슬라이스가 MemberController를 실제로 올리므로
	 * 여기서의 404는 '컨트롤러가 없어서'가 아니라 '이 컨트롤러가 더는 그 경로를 받지 않아서'다.
	 */
	@Test
	@WithMockUser(roles = "OPERATOR")
	void noLongerServesTheManagerRosterOnTheMembersPath() throws Exception {
		mockMvc.perform(get("/api/v0/members").param("role", "MANAGER"))
				.andExpect(status().isNotFound());
	}

	@Test
	@WithMockUser(username = "lead@example.com", roles = "OPERATOR")
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
	@WithMockUser(username = "lead@example.com", roles = "OPERATOR")
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
	@WithMockUser(username = "lead@example.com", roles = "OPERATOR")
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
	@WithMockUser(username = "lead@example.com", roles = "OPERATOR")
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

	/**
	 * 요청 스키마가 두 값만 받는지 본다. 예전에는 네 값짜리 AccountStatus를 받고 별도 검증 메서드로
	 * 걸러냈는데, 그 메서드가 {@code mutableStatus}라는 이름으로 스키마에 새어 나갔다.
	 */
	@Test
	@WithMockUser(username = "lead@example.com", roles = "OPERATOR")
	void rejectsAStatusOutsideActiveAndInactive() throws Exception {
		mockMvc.perform(patch("/api/v0/cohorts/{cohortId}/trainees/{traineeId}/status",
						UUID.randomUUID(), UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"status": "LOCKED", "reason": "테스트"}
								""")
						.with(csrf()))
				.andExpect(status().isBadRequest())
				// 스프링 기본 본문이 아니라 프로젝트 표준 에러 형식으로 나가야 한다.
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	@WithMockUser(username = "lead@example.com", roles = "OPERATOR")
	void rejectsAMissingStatus() throws Exception {
		mockMvc.perform(patch("/api/v0/cohorts/{cohortId}/trainees/{traineeId}/status",
						UUID.randomUUID(), UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"reason": "사유만 보냄"}
								""")
						.with(csrf()))
				.andExpect(status().isBadRequest());
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableMethodSecurity
	static class MethodSecurityConfiguration {
	}
}
