package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionalInvitationDispatcherTest {

	/**
	 * 메일 발송이 실패해도 <b>계정 자리와 초대 원장은 남긴다</b>(목업 case 4·5).
	 *
	 * <p>예전에는 이 메서드 전체가 한 트랜잭션이라 발송이 실패하면 계정 자리까지 롤백됐다. 그러면
	 * 목록에 아무 행도 남지 않아 "지정됐지만 초대 메일이 나가지 않았습니다 + [재발송]"을 그릴 수 없고,
	 * 같은 주소로 다시 초대했을 때 중복인지 재시도인지 구분되지 않는다.
	 *
	 * <p>지금은 실패를 원장에 {@code DELIVERY_FAILED}로 기록한 뒤 예외를 올린다 —
	 * 발송 실패는 성공이 아니므로 호출부에는 계속 실패로 알린다.
	 */
	@Test
	void recordsDeliveryFailureAndKeepsInvitationWhenMailFails() {
		InvitationPersistenceService persistenceService = mock(InvitationPersistenceService.class);
		InvitationMailSender mailSender = mock(InvitationMailSender.class);
		TransactionalInvitationDispatcher dispatcher = new TransactionalInvitationDispatcher(
				persistenceService,
				mailSender
		);
		UUID organizationId = UUID.randomUUID();
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest("operator@example.com", null);
		AuthUser actor = new AuthUser(
				UUID.randomUUID(),
				null,
				"admin@example.com",
				"Admin",
				"hash",
				"ACTIVE",
				true,
				null,
				Role.SUPER_ADMIN,
				null
		);
		Instant now = Instant.now();
		PendingInvitation invitation = new PendingInvitation(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				request.email(),
				"raw-token",
				Role.OPERATOR,
				now,
				now.plusSeconds(3600),
				context
		);
		when(persistenceService.createManagerInvitation(context, request, Role.OPERATOR, actor, "request-1"))
				.thenReturn(invitation);
		doThrow(new MailSendException("SMTP failure"))
				.when(mailSender).sendManagerInvitation(invitation);

		assertThatThrownBy(() -> dispatcher.inviteManager(context, request, Role.OPERATOR, actor, "request-1"))
				.isInstanceOf(InvitationDeliveryException.class)
				.hasMessageContaining("재발송할 수 있습니다")
				.hasRootCauseMessage("SMTP failure");

		var ordered = inOrder(persistenceService, mailSender);
		ordered.verify(persistenceService)
				.createManagerInvitation(context, request, Role.OPERATOR, actor, "request-1");
		ordered.verify(mailSender).sendManagerInvitation(invitation);
		// 실패가 원장에 남아야 화면이 [재발송] 대상을 찾을 수 있다.
		ordered.verify(persistenceService).markInvitationFailed(eq(invitation), contains("SMTP failure"));
		// 실패했으므로 발송 완료로 표시하지 않는다.
		verify(persistenceService, never()).markInvitationSent(invitation);
	}

	/**
	 * 대량 발송은 <b>청크마다 보내고 곧바로 기록</b>해야 한다.
	 *
	 * <p>전량을 보낸 뒤 한 번에 기록하면 900명이 다 나갈 때까지 원장이 PENDING으로 멈춰 있어
	 * 진행률 폴링이 0%에서 100%로 튄다. 중간에 인스턴스가 죽으면 이미 나간 메일까지 전부
	 * "안 나간 것"으로 남아 안전망이 다시 보내게 된다.
	 *
	 * <p>이 테스트가 없으면 기록을 루프 밖으로 빼도 기능은 그대로 동작하고 진행률만 죽는다.
	 */
	@Test
	void recordsEachChunkAsSoonAsItIsSent() {
		InvitationPersistenceService persistenceService = mock(InvitationPersistenceService.class);
		InvitationMailSender mailSender = mock(InvitationMailSender.class);
		TransactionalInvitationDispatcher dispatcher = new TransactionalInvitationDispatcher(
				persistenceService,
				mailSender
		);
		ReflectionTestUtils.setField(dispatcher, "mailBatchSize", 2);
		when(mailSender.sendTraineeInvitations(anyList())).thenReturn(Map.of());
		List<InvitationMailSender.TraineeInvitationMail> mails = new ArrayList<>();
		for (int index = 0; index < 5; index++) {
			mails.add(new InvitationMailSender.TraineeInvitationMail(
					traineeInvitation("trainee" + index + "@example.com"), "교육생" + index));
		}

		dispatcher.sendTraineeInvitations(mails);

		// 5건을 2건씩 → 청크 3개. 발송과 기록이 청크 수만큼 짝지어 일어난다.
		ArgumentCaptor<List<InvitationMailSender.TraineeInvitationMail>> sentCaptor =
				ArgumentCaptor.forClass(List.class);
		verify(mailSender, times(3)).sendTraineeInvitations(sentCaptor.capture());
		assertThat(sentCaptor.getAllValues()).extracting(List::size).containsExactly(2, 2, 1);
		ArgumentCaptor<List<PendingInvitation>> recordedCaptor = ArgumentCaptor.forClass(List.class);
		verify(persistenceService, times(3)).markInvitationsSent(recordedCaptor.capture());
		assertThat(recordedCaptor.getAllValues()).extracting(List::size).containsExactly(2, 2, 1);
	}

	/** 청크 하나가 통째로 실패해도 나머지 청크는 계속 나가고, 실패한 것만 돌아온다. */
	@Test
	void keepsSendingRemainingChunksWhenOneChunkFails() {
		InvitationPersistenceService persistenceService = mock(InvitationPersistenceService.class);
		InvitationMailSender mailSender = mock(InvitationMailSender.class);
		TransactionalInvitationDispatcher dispatcher = new TransactionalInvitationDispatcher(
				persistenceService,
				mailSender
		);
		ReflectionTestUtils.setField(dispatcher, "mailBatchSize", 1);
		PendingInvitation first = traineeInvitation("first@example.com");
		PendingInvitation second = traineeInvitation("second@example.com");
		when(mailSender.sendTraineeInvitations(anyList())).thenAnswer(invocation -> {
			List<InvitationMailSender.TraineeInvitationMail> chunk = invocation.getArgument(0);
			UUID tokenId = chunk.get(0).invitation().tokenId();
			return tokenId.equals(first.tokenId()) ? Map.of(tokenId, "SMTP failure") : Map.of();
		});

		Set<UUID> failed = dispatcher.sendTraineeInvitations(List.of(
				new InvitationMailSender.TraineeInvitationMail(first, "첫째"),
				new InvitationMailSender.TraineeInvitationMail(second, "둘째")
		));

		assertThat(failed).containsExactly(first.tokenId());
		verify(persistenceService).markInvitationFailed(eq(first), contains("SMTP failure"));
		verify(persistenceService).markInvitationsSent(List.of(second));
	}

	private static PendingInvitation traineeInvitation(String email) {
		Instant now = Instant.now();
		return new PendingInvitation(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				email,
				"raw-token",
				Role.TRAINEE,
				now,
				now.plusSeconds(3600),
				new InvitationContext(UUID.randomUUID(), "AIVLE", UUID.randomUUID(), "7기")
		);
	}
}
