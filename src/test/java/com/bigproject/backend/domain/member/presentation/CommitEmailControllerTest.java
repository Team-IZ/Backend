package com.bigproject.backend.domain.member.presentation;

import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.application.CommitEmailErrorCode;
import com.bigproject.backend.domain.member.application.CommitEmailException;
import com.bigproject.backend.domain.member.application.CommitEmailService;
import com.bigproject.backend.domain.member.presentation.dto.CommitEmailResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CommitEmailController.class)
@Import({
		ApiPathConfig.class,
		CommitEmailExceptionHandler.class,
		CommitEmailControllerTest.MethodSecurityConfiguration.class
})
class CommitEmailControllerTest {
	private static final String PATH = "/api/v0/members/me/commit-email";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CommitEmailService commitEmailService;

	@MockitoBean
	private JwtProvider jwtProvider;

	@MockitoBean
	private AuthUserRepository authUserRepository;

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void returnsCommitEmailAtCanonicalPath() throws Exception {
		when(commitEmailService.getMyCommitEmail()).thenReturn(new CommitEmailResponse(
				true,
				"gildong@example.com",
				"PENDING",
				null,
				null,
				Instant.parse("2026-08-06T09:14:02Z")
		));

		mockMvc.perform(get(PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.registered").value(true))
				.andExpect(jsonPath("$.commitEmail").value("gildong@example.com"))
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	@WithMockUser(username = "manager@example.com", roles = "MANAGER")
	void rejectsNonTrainee() throws Exception {
		mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void returnsInvalidEmailFormatCodeForMalformedAddress() throws Exception {
		mockMvc.perform(put(PATH)
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"commitEmail\":\"not-an-email\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("INVALID_EMAIL_FORMAT"));
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void returnsConflictCodeWhenCommitEmailTaken() throws Exception {
		when(commitEmailService.updateMyCommitEmail(any()))
				.thenThrow(new CommitEmailException(CommitEmailErrorCode.COMMIT_EMAIL_ALREADY_USED));

		mockMvc.perform(put(PATH)
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"commitEmail\":\"taken@example.com\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("COMMIT_EMAIL_ALREADY_USED"));
	}

	@Test
	@WithMockUser(username = "trainee@example.com", roles = "TRAINEE")
	void returnsOkNotCreatedOnFirstRegistration() throws Exception {
		when(commitEmailService.updateMyCommitEmail(any())).thenReturn(new CommitEmailResponse(
				true,
				"gildong@example.com",
				"PENDING",
				null,
				null,
				Instant.parse("2026-08-06T09:14:02Z")
		));

		mockMvc.perform(put(PATH)
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"commitEmail\":\"gildong@example.com\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@TestConfiguration
	@EnableMethodSecurity
	static class MethodSecurityConfiguration {
	}
}
