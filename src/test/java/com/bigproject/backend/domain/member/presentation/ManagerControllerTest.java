package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.application.ManagerRosterService;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import com.bigproject.backend.global.config.ApiPathConfig;
import com.bigproject.backend.global.security.CurrentUserResolver;
import com.bigproject.backend.global.security.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ManagerController.class)
@Import({ApiPathConfig.class, ManagerControllerTest.MethodSecurityConfiguration.class})
class ManagerControllerTest {
	private static final UUID ORG_ID = UUID.randomUUID();

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ManagerRosterService managerRosterService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@MockitoBean
	private AuthUserRepository authUserRepository;

	@MockitoBean
	private CurrentUserResolver currentUserResolver;

	@TestConfiguration
	@EnableMethodSecurity
	static class MethodSecurityConfiguration {
	}

	/**
	 * 컨트롤러가 기관을 {@code authentication.getDetails()}에서 꺼내므로 {@code @WithMockUser}로는
	 * 부족하다 — details가 UUID가 아니면 ORGANIZATION_CONTEXT_MISSING으로 500이 난다.
	 */
	private static RequestPostProcessor operator() {
		return authenticationWithOrg("ROLE_OPERATOR");
	}

	private static RequestPostProcessor authenticationWithOrg(String authority) {
		UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
				"operator@green.com", "n/a", List.of(new SimpleGrantedAuthority(authority)));
		token.setDetails(ORG_ID);
		return authentication(token);
	}

	private void stubEmptyRoster() {
		Page<ManagerRosterRepository.ManagerRosterRow> emptyPage = new PageImpl<>(List.of());
		when(managerRosterService.findManagers(any(), any(), any(), any(), any(), any()))
				.thenReturn(new ManagerRosterService.RosterResult(emptyPage, Map.of(), 0));
	}

	@Test
	void servesTheRosterAtTheManagersPath() throws Exception {
		stubEmptyRoster();

		mockMvc.perform(get("/api/v0/managers").with(operator())).andExpect(status().isOk());
	}

	/** 역할 파라미터를 없앤 게 이 이름 변경의 요점이라, 안 넘겨도 200이어야 한다. */
	@Test
	void needsNoRoleParameter() throws Exception {
		stubEmptyRoster();

		mockMvc.perform(get("/api/v0/managers").with(operator())).andExpect(status().isOk());

		verify(managerRosterService).findManagers(eq(ORG_ID), isNull(), isNull(), isNull(),
				eq(ManagerRosterSort.NAME), any());
	}

	@Test
	void passesTheCohortScopeThrough() throws Exception {
		stubEmptyRoster();
		UUID cohortId = UUID.randomUUID();

		mockMvc.perform(get("/api/v0/managers")
						.with(operator())
						.param("cohortId", cohortId.toString())
						.param("status", "ACTIVE"))
				.andExpect(status().isOk());

		verify(managerRosterService).findManagers(
				eq(ORG_ID), eq(cohortId), eq(AccountStatus.ACTIVE), isNull(), eq(ManagerRosterSort.NAME), any());
	}

	/** 남아 있던 role 파라미터를 넘겨도 무시하고 200이어야 한다(알 수 없는 쿼리 파라미터). */
	@Test
	void ignoresALeftoverRoleParameter() throws Exception {
		stubEmptyRoster();

		mockMvc.perform(get("/api/v0/managers").with(operator()).param("role", "MANAGER"))
				.andExpect(status().isOk());
	}

	@Test
	void rejectsNonOperators() throws Exception {
		mockMvc.perform(get("/api/v0/managers").with(authenticationWithOrg("ROLE_MANAGER")))
				.andExpect(status().isForbidden());
	}
}
