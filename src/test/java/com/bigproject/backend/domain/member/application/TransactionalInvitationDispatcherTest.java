package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
}
