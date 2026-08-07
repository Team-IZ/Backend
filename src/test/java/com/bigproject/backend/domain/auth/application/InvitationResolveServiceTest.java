package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.InvitationRecipient;
import com.bigproject.backend.domain.auth.domain.InvitationResolveRepository;
import com.bigproject.backend.domain.auth.presentation.dto.InvitationResolveRequest;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvitationResolveServiceTest {
	private final InvitationResolveRepository repository = mock(InvitationResolveRepository.class);
	private final OneTimeTokenHasher tokenHasher = new OneTimeTokenHasher();
	private final InvitationResolveService service = new InvitationResolveService(repository, tokenHasher);

	@Test
	void resolvesPendingUserFromValidInvitationToken() throws Exception {
		UUID userId = UUID.randomUUID();
		String email = "invitee@example.com";
		String tokenHash = tokenHasher.hash("raw-token");
		when(repository.findResolvableByTokenHash(
				org.mockito.ArgumentMatchers.eq(tokenHash),
				org.mockito.ArgumentMatchers.any(Instant.class)
		)).thenReturn(Optional.of(new InvitationRecipient(userId, email, Role.TRAINEE)));

		var response = service.resolve(new InvitationResolveRequest(" raw-token "));

		assertThat(response.userId()).isEqualTo(userId);
		assertThat(response.email()).isEqualTo(email);
		assertThat(response.role()).isEqualTo(Role.TRAINEE);
		String json = new ObjectMapper().writeValueAsString(response);
		assertThat(json).contains("\"user_id\":\"" + userId + "\"");
		assertThat(json).doesNotContain("\"userId\"");
		assertThat(json).contains("\"role\":\"TRAINEE\"");
		ArgumentCaptor<Instant> resolvedAt = ArgumentCaptor.forClass(Instant.class);
		verify(repository).findResolvableByTokenHash(
				org.mockito.ArgumentMatchers.eq(tokenHash),
				resolvedAt.capture()
		);
		assertThat(resolvedAt.getValue()).isNotNull();
	}

	@Test
	void rejectsInvalidExpiredUsedOrInvalidatedInvitationWithSameMessage() {
		String tokenHash = tokenHasher.hash("invalid-token");
		when(repository.findResolvableByTokenHash(
				org.mockito.ArgumentMatchers.eq(tokenHash),
				org.mockito.ArgumentMatchers.any(Instant.class)
		)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.resolve(new InvitationResolveRequest("invalid-token")))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
					assertThat(exception.getStatusCode().value()).isEqualTo(400);
					assertThat(exception.getReason()).isEqualTo("유효하지 않거나 만료된 초대입니다.");
				});
	}
}
