package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraineeInvitationOutboxTest {
	private final MemberInvitationRepository invitationRepository = mock(MemberInvitationRepository.class);
	private final InvitationPersistenceService persistenceService = mock(InvitationPersistenceService.class);
	private final TransactionalInvitationDispatcher invitationDispatcher =
			mock(TransactionalInvitationDispatcher.class);
	private final TraineeInvitationOutbox outbox = new TraineeInvitationOutbox(
			invitationRepository, persistenceService, invitationDispatcher);

	TraineeInvitationOutboxTest() {
		ReflectionTestUtils.setField(outbox, "stallThreshold", Duration.ofMinutes(10));
		ReflectionTestUtils.setField(outbox, "claimLimit", 200);
	}

	/** 걸리는 것이 없는 것이 정상이다 — 그때 토큰을 발급하거나 메일을 열어서는 안 된다. */
	@Test
	void doesNothingWhenNoInvitationIsStalled() {
		when(invitationRepository.claimStalledTraineeInvitations(any(), any(), anyInt()))
				.thenReturn(List.of());

		assertThat(outbox.dispatchStalledInvitations()).isZero();

		verify(persistenceService, never()).recreateTraineeInvitationTokens(any(), anyList());
		verify(invitationDispatcher, never()).sendTraineeInvitations(anyList());
	}

	/**
	 * 스톨 기준은 <b>지금으로부터 임계 시간 이전</b>이다.
	 *
	 * <p>이 값을 잘못 잡으면 아직 보내는 중인 초대를 다른 인스턴스가 집어 교육생이 메일을 두 통 받는다.
	 * 배포본이 셋이라 조용히 넘어가지 않고 실제 중복 발송으로 드러난다.
	 */
	@Test
	void claimsOnlyInvitationsOlderThanTheStallThreshold() {
		when(invitationRepository.claimStalledTraineeInvitations(any(), any(), anyInt()))
				.thenReturn(List.of());
		Instant before = Instant.now();

		outbox.dispatchStalledInvitations();

		var claimedAt = org.mockito.ArgumentCaptor.forClass(Instant.class);
		var stalledBefore = org.mockito.ArgumentCaptor.forClass(Instant.class);
		verify(invitationRepository)
				.claimStalledTraineeInvitations(claimedAt.capture(), stalledBefore.capture(), eq(200));
		assertThat(claimedAt.getValue()).isAfterOrEqualTo(before);
		assertThat(Duration.between(stalledBefore.getValue(), claimedAt.getValue()))
				.isEqualTo(Duration.ofMinutes(10));
	}

	/**
	 * 기관이 섞여 들어오면 <b>기관별로 나눠</b> 토큰을 재발급해야 한다.
	 *
	 * <p>이전 토큰 무효화가 기관 단위 질의라, 섞어서 부르면 다른 기관의 토큰이 무효화 대상에서 빠지거나
	 * 잘못 걸린다. 한 기관이 실패해도 다른 기관의 회수까지 멈추지 않는 효과도 함께 얻는다.
	 */
	@Test
	void reissuesTokensPerOrganization() {
		UUID firstOrganization = UUID.randomUUID();
		UUID secondOrganization = UUID.randomUUID();
		MemberInvitationRepository.StalledInvitation first = stalled(firstOrganization, "a@example.com");
		MemberInvitationRepository.StalledInvitation second = stalled(firstOrganization, "b@example.com");
		MemberInvitationRepository.StalledInvitation third = stalled(secondOrganization, "c@example.com");
		when(invitationRepository.claimStalledTraineeInvitations(any(), any(), anyInt()))
				.thenReturn(List.of(first, second, third));
		when(persistenceService.recreateTraineeInvitationTokens(any(), anyList()))
				.thenAnswer(invocation -> mailsFor(invocation.getArgument(1)));

		assertThat(outbox.dispatchStalledInvitations()).isEqualTo(3);

		verify(persistenceService).recreateTraineeInvitationTokens(firstOrganization, List.of(first, second));
		verify(persistenceService).recreateTraineeInvitationTokens(secondOrganization, List.of(third));
		verify(invitationDispatcher, times(2)).sendTraineeInvitations(anyList());
	}

	/** 한 기관이 실패해도 나머지 기관의 초대는 계속 나가야 한다 — 안전망이 한 건에 통째로 멈추면 안전망이 아니다. */
	@Test
	void keepsDispatchingOtherOrganizationsWhenOneFails() {
		UUID failing = UUID.randomUUID();
		UUID healthy = UUID.randomUUID();
		MemberInvitationRepository.StalledInvitation broken = stalled(failing, "broken@example.com");
		MemberInvitationRepository.StalledInvitation fine = stalled(healthy, "fine@example.com");
		when(invitationRepository.claimStalledTraineeInvitations(any(), any(), anyInt()))
				.thenReturn(List.of(broken, fine));
		when(persistenceService.recreateTraineeInvitationTokens(eq(failing), anyList()))
				.thenThrow(new IllegalStateException("토큰을 발급할 수 없습니다."));
		when(persistenceService.recreateTraineeInvitationTokens(eq(healthy), anyList()))
				.thenAnswer(invocation -> mailsFor(invocation.getArgument(1)));

		assertThat(outbox.dispatchStalledInvitations()).isEqualTo(1);

		verify(invitationDispatcher, times(1)).sendTraineeInvitations(anyList());
	}

	private static List<InvitationMailSender.TraineeInvitationMail> mailsFor(
			List<MemberInvitationRepository.StalledInvitation> invitations
	) {
		Instant now = Instant.now();
		return invitations.stream()
				.map(invitation -> new InvitationMailSender.TraineeInvitationMail(
						new PendingInvitation(
								invitation.userId(),
								invitation.invitationId(),
								UUID.randomUUID(),
								invitation.email(),
								"raw-token",
								Role.TRAINEE,
								now,
								now.plusSeconds(3600),
								invitation.context()
						),
						invitation.name()))
				.toList();
	}

	private static MemberInvitationRepository.StalledInvitation stalled(UUID organizationId, String email) {
		return new MemberInvitationRepository.StalledInvitation(
				UUID.randomUUID(),
				UUID.randomUUID(),
				email,
				email,
				"교육생",
				UUID.randomUUID(),
				"batch-1",
				new InvitationContext(organizationId, "AIVLE", UUID.randomUUID(), "7기")
		);
	}
}
