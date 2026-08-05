package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalInvitationDispatcher {
	private final InvitationPersistenceService persistenceService;
	private final InvitationMailSender mailSender;

	@Transactional
	public PendingInvitation inviteManager(
			InvitationContext context,
			InviteManagerRequest request,
			Role targetRole,
			AuthUser actor,
			String requestId
	) {
		PendingInvitation invitation = persistenceService.createManagerInvitation(
				context,
				request,
				targetRole,
				actor,
				requestId
		);
		try {
			log.info(
					"매니저 초대 메일 발송 시작: tokenId={}, organizationId={}, role={}",
					invitation.tokenId(),
					invitation.context().organizationId(),
					invitation.role()
			);
			mailSender.sendManagerInvitation(invitation);
			persistenceService.markInvitationSent(invitation);
			log.info(
					"매니저 초대 메일 발송 성공: tokenId={}, organizationId={}, role={}",
					invitation.tokenId(),
					invitation.context().organizationId(),
					invitation.role()
			);
		} catch (RuntimeException exception) {
			logDeliveryFailure("매니저", invitation, exception);
			throw new InvitationDeliveryException(
					"초대 메일 발송에 실패하여 계정 정보를 저장하지 않았습니다.",
					exception
			);
		}
		return invitation;
	}

	@Transactional
	public PendingInvitation inviteTrainee(
			InvitationContext context,
			RegisterTraineesRequest.Trainee trainee,
			AuthUser actor,
			String requestId
	) {
		PendingInvitation invitation = persistenceService.createTraineeInvitation(
				context,
				trainee,
				actor,
				requestId
		);
		try {
			log.info(
					"교육생 초대 메일 발송 시작: tokenId={}, organizationId={}, cohortId={}",
					invitation.tokenId(),
					invitation.context().organizationId(),
					invitation.context().cohortId()
			);
			mailSender.sendTraineeInvitation(invitation, trainee.name().trim());
			persistenceService.markInvitationSent(invitation);
			log.info(
					"교육생 초대 메일 발송 성공: tokenId={}, organizationId={}, cohortId={}",
					invitation.tokenId(),
					invitation.context().organizationId(),
					invitation.context().cohortId()
			);
		} catch (RuntimeException exception) {
			logDeliveryFailure("교육생", invitation, exception);
			throw new InvitationDeliveryException(
					"초대 메일 발송에 실패하여 교육생 정보를 저장하지 않았습니다.",
					exception
			);
		}
		return invitation;
	}

	private void logDeliveryFailure(
			String invitationType,
			PendingInvitation invitation,
			RuntimeException exception
	) {
		Throwable rootCause = exception;
		while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
			rootCause = rootCause.getCause();
		}
		log.error(
				"{} 초대 메일 발송 실패: tokenId={}, organizationId={}, exceptionType={}, rootCauseType={}, rootCauseMessage={}",
				invitationType,
				invitation.tokenId(),
				invitation.context().organizationId(),
				exception.getClass().getName(),
				rootCause.getClass().getName(),
				rootCause.getMessage(),
				exception
		);
	}
}
