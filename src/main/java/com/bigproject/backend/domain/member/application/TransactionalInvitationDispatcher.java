package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 초대 3단계(자리 확보 → 메일 발송 → 결과 기록)를 순서대로 엮는다.
 *
 * <p><b>이 클래스에는 트랜잭션이 없다.</b> 각 단계는 {@link InvitationPersistenceService}가 REQUIRES_NEW로
 * 따로 커밋한다. 예전에는 이 메서드 전체가 하나의 트랜잭션이라 <b>메일 발송이 실패하면 계정 자리까지
 * 롤백</b>됐다 — 목록에 아무 행도 남지 않아 목업 case 4·5의 "지정됐지만 초대 메일이 나가지 않았습니다 +
 * [재발송]"을 그릴 수 없었고, 같은 주소로 다시 초대했을 때 중복인지 재시도인지도 구분되지 않았다.
 *
 * <p>지금은 발송이 실패해도 자리와 초대 원장이 남고, 원장 상태만 {@code DELIVERY_FAILED}가 된다.
 * 호출부에는 예외를 그대로 올려 화면이 실패를 알 수 있게 한다(발송 실패는 성공이 아니다).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalInvitationDispatcher {
	private final InvitationPersistenceService persistenceService;
	private final InvitationMailSender mailSender;

	/**
	 * 초대 메일을 다시 보낸다(목업 SA-02 ② [재발송]).
	 *
	 * <p>최초 발송과 흐름이 같다 — 토큰 발급 → 메일 → 결과 기록. 다르게 처리하지 않는다.
	 * 원장은 그대로 두고 토큰만 새로 발급하므로 {@code resend_count}가 정확히 쌓인다.
	 */
	public PendingInvitation resend(
			MemberInvitationRepository.ResendableInvitation target,
			AuthUser actor,
			String requestId
	) {
		PendingInvitation invitation = persistenceService.recreateInvitationToken(target, actor, requestId);
		try {
			log.info(
					"초대 재발송 시작: tokenId={}, invitationId={}, purpose={}",
					invitation.tokenId(),
					invitation.invitationId(),
					target.purpose()
			);
			sendByPurpose(target, invitation);
			persistenceService.markInvitationResent(invitation);
			log.info("초대 재발송 성공: tokenId={}, invitationId={}", invitation.tokenId(), invitation.invitationId());
		} catch (RuntimeException exception) {
			throw deliveryFailed("재발송", invitation, exception);
		}
		return invitation;
	}

	private void sendByPurpose(
			MemberInvitationRepository.ResendableInvitation target,
			PendingInvitation invitation
	) {
		switch (target.purpose()) {
			case INVITE_SUPER_ADMIN -> mailSender.sendSuperAdminInvitation(invitation);
			case INVITE_OPERATOR_MANAGER -> mailSender.sendManagerInvitation(invitation);
			case INVITE_TRAINEE -> mailSender.sendTraineeInvitation(invitation, target.name());
			default -> throw new IllegalStateException("재발송할 수 없는 초대 목적입니다: " + target.purpose());
		}
	}

	public PendingInvitation inviteSuperAdmin(
			String email,
			AuthUser actor,
			String requestId
	) {
		PendingInvitation invitation = persistenceService.createSuperAdminInvitation(email, actor, requestId);
		try {
			log.info("슈퍼어드민 초대 메일 발송 시작: tokenId={}", invitation.tokenId());
			mailSender.sendSuperAdminInvitation(invitation);
			persistenceService.markInvitationSent(invitation);
			log.info("슈퍼어드민 초대 메일 발송 성공: tokenId={}", invitation.tokenId());
		} catch (RuntimeException exception) {
			throw deliveryFailed("슈퍼어드민", invitation, exception);
		}
		return invitation;
	}

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
			throw deliveryFailed("매니저", invitation, exception);
		}
		return invitation;
	}

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
			throw deliveryFailed("교육생", invitation, exception);
		}
		return invitation;
	}

	/**
	 * 발송 실패를 원장에 남기고 예외로 변환한다.
	 *
	 * <p>기록이 또 실패해도 원래 예외를 삼키지 않는다 — 그러면 "왜 메일이 안 갔는지"가 사라지고
	 * 기록 실패라는 2차 원인만 남는다. 기록 실패는 로그로만 남기고 원인은 그대로 올린다.
	 */
	private InvitationDeliveryException deliveryFailed(
			String invitationType,
			PendingInvitation invitation,
			RuntimeException exception
	) {
		logDeliveryFailure(invitationType, invitation, exception);
		try {
			persistenceService.markInvitationFailed(invitation, rootCauseMessage(exception));
		} catch (RuntimeException recordFailure) {
			log.error(
					"{} 초대 발송 실패를 원장에 기록하지 못했습니다: tokenId={}",
					invitationType,
					invitation.tokenId(),
					recordFailure
			);
		}
		return new InvitationDeliveryException(
				"초대 메일 발송에 실패했습니다. 계정 자리와 초대 기록은 남아 있어 재발송할 수 있습니다.",
				exception
		);
	}

	private void logDeliveryFailure(
			String invitationType,
			PendingInvitation invitation,
			RuntimeException exception
	) {
		Throwable rootCause = rootCause(exception);
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

	private String rootCauseMessage(RuntimeException exception) {
		Throwable rootCause = rootCause(exception);
		String message = rootCause.getMessage();
		String reason = rootCause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
		// failure_reason은 TEXT지만 로그성 문자열이 무한정 길어지지 않도록 자른다.
		return reason.length() > 1000 ? reason.substring(0, 1000) : reason;
	}

	private Throwable rootCause(Throwable exception) {
		Throwable rootCause = exception;
		while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
			rootCause = rootCause.getCause();
		}
		return rootCause;
	}
}
