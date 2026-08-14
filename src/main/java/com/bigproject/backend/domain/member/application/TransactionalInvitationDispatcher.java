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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
	 * 자리 확보를 몇 건씩 묶을지. 한 청크가 한 트랜잭션이므로 이 값이 곧 "경합 시 행 단위로 되돌릴 범위"다.
	 * 크게 잡으면 왕복이 줄고 폴백 비용이 커진다.
	 */
	@Value("${invitation.reservation-chunk-size:100}")
	private int reservationChunkSize;

	/**
	 * 한 번에 보내고 곧바로 기록할 건수. 발송기가 연결 하나로 보내는 단위와 같은 값을 쓴다 —
	 * 발송 단위보다 잘게 기록하려면 발송기 안까지 콜백이 들어가야 하고, 크게 잡으면 그만큼
	 * 진행률이 늦게 움직인다.
	 */
	@Value("${invitation.mail.batch-size:100}")
	private int mailBatchSize;

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

	/**
	 * 여러 교육생 자리를 <b>청크 단위로</b> 확보한다(1단계).
	 *
	 * <p>다른 초대(슈퍼어드민·매니저)와 달리 자리 확보와 발송을 분리하는 이유는 <b>건수</b>다.
	 * CSV 대량 등록은 한 요청에 수백 건이 오는데, 건마다 SMTP 연결을 새로 열면 그 고정비가 전체 소요의
	 * 대부분이 된다. 자리를 전량 확보한 뒤 연결 하나로 몰아 보내면 고정비를 청크당 한 번만 낸다.
	 *
	 * <p>단건 경로({@code InvitationPersistenceService.createTraineeInvitation})를 행마다 부르면
	 * 행당 8왕복이라 900명이 약 7,200왕복이 된다. 청크로 묶으면 청크당 8왕복이 되어 약 72왕복으로 줄어든다.
	 *
	 * <h2>배치가 깨질 때</h2>
	 *
	 * <p>사전 판정과 INSERT 사이에 다른 운영자가 같은 주소를 등록하면 UNIQUE 위반이 나고
	 * <b>그 청크 전체가 롤백된다.</b> 배치 INSERT는 행별 실패를 가려내지 못하기 때문이다.
	 *
	 * <p>그때 <b>그 청크만</b> 단건 경로로 다시 처리한다. 단건은 행마다 트랜잭션이 따로여서 문제 행만
	 * 실패하고 나머지는 통과한다. 느리지만 이 경로로 들어오는 것은 900건 중 한 청크(기본 100건)뿐이고,
	 * 그마저 두 운영자가 같은 순간에 같은 주소를 올렸을 때만이다.
	 *
	 * @return 행 순서를 유지한 자리 확보 결과. 충돌한 행은 {@code invitation}이 {@code null}이다
	 */
	public List<InvitationPersistenceService.TraineeSlot> reserveTrainees(
			InvitationContext context,
			List<InvitationPersistenceService.TraineeSlotRequest> requests,
			AuthUser actor,
			String baseRequestId
	) {
		List<InvitationPersistenceService.TraineeSlot> slots = new ArrayList<>(requests.size());
		for (int start = 0; start < requests.size(); start += reservationChunkSize) {
			List<InvitationPersistenceService.TraineeSlotRequest> chunk =
					requests.subList(start, Math.min(start + reservationChunkSize, requests.size()));
			try {
				slots.addAll(persistenceService.createTraineeInvitationChunk(context, chunk, actor, baseRequestId));
			} catch (DataIntegrityViolationException exception) {
				log.warn(
						"교육생 자리 일괄 확보가 제약 위반으로 롤백됐다. 이 청크만 행 단위로 다시 처리한다: "
								+ "chunkStart={}, chunkSize={}",
						start, chunk.size(), exception
				);
				slots.addAll(reserveOneByOne(context, chunk, actor, baseRequestId));
			}
		}
		return slots;
	}

	/** 배치가 깨진 청크의 폴백. 행마다 트랜잭션이 따로라 문제 행만 실패한다. */
	private List<InvitationPersistenceService.TraineeSlot> reserveOneByOne(
			InvitationContext context,
			List<InvitationPersistenceService.TraineeSlotRequest> chunk,
			AuthUser actor,
			String baseRequestId
	) {
		List<InvitationPersistenceService.TraineeSlot> slots = new ArrayList<>(chunk.size());
		for (InvitationPersistenceService.TraineeSlotRequest request : chunk) {
			try {
				PendingInvitation invitation = persistenceService.createTraineeInvitation(
						context,
						new RegisterTraineesRequest.Trainee(request.name(), request.email()),
						actor,
						baseRequestId + ":" + request.row(),
						baseRequestId
				);
				slots.add(new InvitationPersistenceService.TraineeSlot(request, invitation));
			} catch (InvitationConflictException | DataIntegrityViolationException exception) {
				// 이 행이 경합의 당사자다. 충돌로 표시하고 나머지 행을 계속 처리한다.
				slots.add(new InvitationPersistenceService.TraineeSlot(request, null));
			}
		}
		return slots;
	}

	/**
	 * 확보된 교육생 초대들의 메일을 <b>연결 하나로</b> 보내고 결과를 원장에 기록한다(2·3단계).
	 *
	 * <p><b>예외를 던지지 않는다.</b> 예전에는 발송 실패가 {@code InvitationDeliveryException}으로 올라가
	 * 호출부에서 요청 전체를 실패로 만들었다 — 500번째 행에서 메일이 한 번 튕기면 앞의 499건이 이미
	 * 커밋됐는데도 응답은 에러이고 성공·실패 목록까지 사라졌다. 지금은 실패한 초대만 돌려주므로
	 * 호출부가 "등록은 됐고 메일은 안 나간 행"을 구분해 응답할 수 있다.
	 *
	 * <p><b>청크마다 보내고 곧바로 기록한다.</b> 전량을 보낸 뒤 한 번에 기록하면 900명이 다 나갈 때까지
	 * 원장이 PENDING으로 멈춰 있어 진행률 폴링이 0%에서 100%로 튄다. 청크 단위로 커밋하면 화면이
	 * 발송이 실제로 진행되는 것을 볼 수 있고, 중간에 인스턴스가 죽어도 이미 나간 만큼은 SENT로 남는다.
	 *
	 * @return 메일이 나가지 못한 초대의 {@code tokenId}. 전부 성공하면 빈 집합
	 */
	public Set<UUID> sendTraineeInvitations(List<InvitationMailSender.TraineeInvitationMail> mails) {
		if (mails.isEmpty()) {
			return Set.of();
		}
		log.info("교육생 초대 메일 일괄 발송 시작: count={}", mails.size());

		Set<UUID> failed = new LinkedHashSet<>();
		for (int start = 0; start < mails.size(); start += mailBatchSize) {
			List<InvitationMailSender.TraineeInvitationMail> chunk =
					mails.subList(start, Math.min(start + mailBatchSize, mails.size()));
			failed.addAll(sendAndRecordChunk(chunk));
		}

		log.info(
				"교육생 초대 메일 일괄 발송 완료: sent={}, failed={}",
				mails.size() - failed.size(),
				failed.size()
		);
		return Set.copyOf(failed);
	}

	private Set<UUID> sendAndRecordChunk(List<InvitationMailSender.TraineeInvitationMail> mails) {
		Map<UUID, String> failures;
		try {
			failures = mailSender.sendTraineeInvitations(mails);
		} catch (RuntimeException exception) {
			// 발송기가 개별 실패로 나누지 못하고 터진 경우(설정 누락 등)는 전량 실패로 본다.
			log.error("교육생 초대 메일 일괄 발송이 통째로 실패했다: count={}", mails.size(), exception);
			String reason = rootCauseMessage(exception);
			failures = new LinkedHashMap<>();
			for (InvitationMailSender.TraineeInvitationMail mail : mails) {
				failures.put(mail.invitation().tokenId(), reason);
			}
		}

		/*
		 * 성공분은 한 번의 배치 UPDATE로 기록한다 — 행마다 부르면 900건이 1,800왕복(UPDATE + 커밋)이다.
		 * 실패분은 건수가 적고 사유 문자열이 행마다 달라 단건으로 둔다.
		 */
		List<PendingInvitation> sent = new ArrayList<>();
		for (InvitationMailSender.TraineeInvitationMail mail : mails) {
			PendingInvitation invitation = mail.invitation();
			String failureReason = failures.get(invitation.tokenId());
			if (failureReason == null) {
				sent.add(invitation);
				continue;
			}
			try {
				persistenceService.markInvitationFailed(invitation, failureReason);
			} catch (RuntimeException recordFailure) {
				// 기록 실패가 다음 행의 기록을 막지 않아야 한다. 원인은 로그로 남긴다.
				log.error("교육생 초대 발송 실패를 원장에 기록하지 못했습니다: tokenId={}", invitation.tokenId(), recordFailure);
			}
		}
		try {
			persistenceService.markInvitationsSent(sent);
		} catch (RuntimeException recordFailure) {
			log.error("교육생 초대 발송 성공을 원장에 기록하지 못했습니다: count={}", sent.size(), recordFailure);
		}
		return failures.keySet();
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
