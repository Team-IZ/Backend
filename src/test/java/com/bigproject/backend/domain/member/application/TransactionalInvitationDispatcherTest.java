package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.ManagerInvitationRole;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionalInvitationDispatcherTest {

	@Test
	void propagatesMailFailureSoInvitationTransactionCanRollBack() {
		InvitationPersistenceService persistenceService = mock(InvitationPersistenceService.class);
		InvitationMailSender mailSender = mock(InvitationMailSender.class);
		TransactionalInvitationDispatcher dispatcher = new TransactionalInvitationDispatcher(
				persistenceService,
				mailSender
		);
		UUID organizationId = UUID.randomUUID();
		InvitationContext context = InvitationContext.organization(organizationId, "AIVLE");
		InviteManagerRequest request = new InviteManagerRequest(
				"lead@example.com",
				ManagerInvitationRole.LEAD_MANAGER,
				null,
				List.of()
		);
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
				request.email(),
				"raw-token",
				request.role().toRole(),
				now,
				now.plusSeconds(3600),
				context
		);
		when(persistenceService.createManagerInvitation(context, request, actor, "request-1"))
				.thenReturn(invitation);
		doThrow(new MailSendException("SMTP failure"))
				.when(mailSender).sendManagerInvitation(invitation);

		assertThatThrownBy(() -> dispatcher.inviteManager(context, request, actor, "request-1"))
				.isInstanceOf(InvitationDeliveryException.class)
				.hasMessageContaining("저장하지 않았습니다");

		var ordered = inOrder(persistenceService, mailSender);
		ordered.verify(persistenceService).createManagerInvitation(context, request, actor, "request-1");
		ordered.verify(mailSender).sendManagerInvitation(invitation);
	}
}
