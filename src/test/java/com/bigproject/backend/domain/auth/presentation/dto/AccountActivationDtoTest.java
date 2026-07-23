package com.bigproject.backend.domain.auth.presentation.dto;

import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountActivationDtoTest {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void readsManagerSignupUserIdFromSnakeCaseProperty() throws Exception {
		UUID userId = UUID.randomUUID();
		String json = """
				{
				  "user_id": "%s",
				  "invitationToken": "raw-token",
				  "name": "매니저",
				  "password": "SafePass1!",
				  "passwordConfirmation": "SafePass1!",
				  "serviceTermsAgreed": true,
				  "privacyCollectionAgreed": true
				}
				""".formatted(userId);

		ManagerSignupRequest request = objectMapper.readValue(json, ManagerSignupRequest.class);

		assertThat(request.userId()).isEqualTo(userId);
	}

	@Test
	void writesActivatedUserIdAsSnakeCaseProperty() throws Exception {
		UUID userId = UUID.randomUUID();

		String json = objectMapper.writeValueAsString(new ActivateAccountResponse(
				userId,
				"invitee@example.com",
				"초대 사용자",
				Role.TRAINEE,
				true
		));

		assertThat(json).contains("\"user_id\":\"" + userId + "\"");
		assertThat(json).doesNotContain("\"userId\"");
	}
}
