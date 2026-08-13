package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.CommitEmail;
import com.bigproject.backend.domain.member.domain.CommitEmailRepository;
import com.bigproject.backend.domain.member.presentation.dto.CommitEmailResponse;
import com.bigproject.backend.domain.member.presentation.dto.UpdateCommitEmailRequest;
import com.bigproject.backend.global.security.CurrentUserResolver;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommitEmailServiceTest {
	private final CommitEmailRepository commitEmailRepository = mock(CommitEmailRepository.class);
	private final CurrentUserResolver currentUserResolver = mock(CurrentUserResolver.class);
	private final CommitEmailService service = new CommitEmailService(commitEmailRepository, currentUserResolver);

	private final UUID userId = UUID.randomUUID();

	@Test
	void reportsUnregisteredWhenCommitEmailIsNull() {
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(userId);
		when(commitEmailRepository.findByUserId(userId)).thenReturn(Optional.of(CommitEmail.unregistered()));

		CommitEmailResponse response = service.getMyCommitEmail();

		assertThat(response.registered()).isFalse();
		assertThat(response.commitEmail()).isNull();
		assertThat(response.status()).isNull();
	}

	@Test
	void normalizesCommitEmailBeforeStoring() {
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(userId);
		when(commitEmailRepository.updateCommitEmail(any(), any(), any(), any())).thenReturn(1);

		CommitEmailResponse response = service.updateMyCommitEmail(
				new UpdateCommitEmailRequest("  GilDong@Example.COM  ")
		);

		verify(commitEmailRepository).updateCommitEmail(
				eq(userId),
				eq("GilDong@Example.COM"),
				eq("gildong@example.com"),
				any(Instant.class)
		);
		// 원문은 표시용으로 보존하고 정규화 값은 중복 판정에만 쓴다.
		assertThat(response.commitEmail()).isEqualTo("GilDong@Example.COM");
		assertThat(response.status()).isEqualTo("PENDING");
		assertThat(response.verifiedAt()).isNull();
	}

	@Test
	void translatesUniqueViolationToConflictErrorCode() {
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(userId);
		when(commitEmailRepository.updateCommitEmail(any(), any(), any(), any()))
				.thenThrow(new DuplicateKeyException("uq_app_user_commit_email_active"));

		assertThatThrownBy(() -> service.updateMyCommitEmail(new UpdateCommitEmailRequest("taken@example.com")))
				.isInstanceOf(CommitEmailException.class)
				.extracting(exception -> ((CommitEmailException) exception).errorCode())
				.isEqualTo(CommitEmailErrorCode.COMMIT_EMAIL_ALREADY_USED);
	}

	@Test
	void reportsMemberNotFoundWhenNoRowUpdated() {
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(userId);
		when(commitEmailRepository.updateCommitEmail(any(), any(), any(), any())).thenReturn(0);

		assertThatThrownBy(() -> service.updateMyCommitEmail(new UpdateCommitEmailRequest("gildong@example.com")))
				.isInstanceOf(CommitEmailException.class)
				.extracting(exception -> ((CommitEmailException) exception).errorCode())
				.isEqualTo(CommitEmailErrorCode.MEMBER_NOT_FOUND);
	}
}
