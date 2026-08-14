package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.InvitationToken;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 초대 자료의 영속 담당.
 *
 * <p>모든 쓰기 메서드가 <b>REQUIRES_NEW</b>다. 초대는 "계정 자리 확보 → 메일 발송 → 결과 기록" 3단계인데
 * 이 셋이 한 트랜잭션에 묶이면 <b>메일 발송이 실패할 때 계정 자리까지 롤백된다.</b> 그러면 목업 case 4·5가
 * 요구하는 "자리는 남기고 [재발송]"을 그릴 수 없고, 같은 주소로 다시 초대했을 때 중복 초대인지 재시도인지
 * 구분할 수도 없어진다. 호출부({@code OperatorServiceImpl.inviteOperator} 등)가 자기 트랜잭션을 열어 두는
 * 경우가 있어 전파 속성으로 끊어 둔다.
 */
@Service
@RequiredArgsConstructor
public class InvitationPersistenceService {
	private final MemberInvitationRepository invitationRepository;
	private final OneTimeTokenGenerator tokenGenerator;
	private final OneTimeTokenHasher tokenHasher;
	private final PendingPasswordHash pendingPasswordHash;
	private final ObjectMapper objectMapper;

	@Value("${invitation.expiration:PT24H}")
	private Duration invitationExpiration;

	/**
	 * 슈퍼어드민 초대(목업 SA-03 ②). 기관에 속하지 않으므로 org_id를 전부 NULL로 넣는다 —
	 * v07 {@code ck_user_invitation_org_id}가 SUPER_ADMIN에 대해 NULL을 강제한다.
	 *
	 * <p>매니저 초대와 달리 기수 검증도 담당 배정도 없다. 슈퍼어드민은 플랫폼 전역이라 배정할 대상이 없다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PendingInvitation createSuperAdminInvitation(
			String rawEmail,
			AuthUser actor,
			String requestId
	) {
		String email = rawEmail.trim();
		String normalizedEmail = EmailNormalizer.normalize(email);
		InvitationContext context = InvitationContext.platform();
		Instant now = Instant.now();
		// 이름은 null로 둔다 — 본인이 가입할 때 정한다. 화면은 이 값이 비어 있으면 `—`로 그린다.
		UUID memberId = resolveInvitationSlot(
				null, email, normalizedEmail, null, Role.SUPER_ADMIN, now
		);
		UUID invitationId = invitationRepository.createInvitation(
				null,
				email,
				normalizedEmail,
				Role.SUPER_ADMIN,
				null,
				actor.userId(),
				now,
				null
		);
		return createToken(
				context,
				memberId,
				invitationId,
				email,
				normalizedEmail,
				Role.SUPER_ADMIN,
				InvitationPurpose.INVITE_SUPER_ADMIN,
				superAdminPayload(),
				actor.userId(),
				requestId,
				now
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PendingInvitation createManagerInvitation(
			InvitationContext context,
			InviteManagerRequest request,
			Role invitedRole,
			AuthUser actor,
			String requestId
	) {
		String email = request.email().trim();
		String normalizedEmail = EmailNormalizer.normalize(email);
		if (request.cohortId() != null) {
			invitationRepository.validateCohort(context.organizationId(), request.cohortId());
		}

		Instant now = Instant.now();
		/*
		 * 이름은 null로 둔다 — 초대받은 본인이 가입할 때 정한다.
		 * 예전에는 여기에 이메일을 넣어서, 목록 화면 이름 칸에 `—` 대신 이메일 주소가 뜨고
		 * 이메일 칸과 같은 문자열이 두 번 보였다. app_user.name은 nullable이고
		 * ck_app_user_status_2도 status='PENDING'일 때 NULL을 허용한다.
		 */
		UUID memberId = resolveInvitationSlot(
				context.organizationId(), email, normalizedEmail, null, invitedRole, now
		);
		UUID invitationId = invitationRepository.createInvitation(
				context.organizationId(),
				email,
				normalizedEmail,
				invitedRole,
				request.cohortId(),
				actor.userId(),
				now,
				null
		);
		PendingInvitation invitation = createToken(
				context,
				memberId,
				invitationId,
				email,
				normalizedEmail,
				invitedRole,
				InvitationPurpose.INVITE_OPERATOR_MANAGER,
				managerPayload(request, invitedRole),
				actor.userId(),
				requestId,
				now
		);
		/*
		 * 반 배정은 초대 시점에 하지 않는다. manager_assignment.class_id가 NOT NULL이라 반이 정해지기 전에는
		 * 배정 행 자체를 만들 수 없고, 담당 반은 가입 이후 반 편성 화면에서 정한다.
		 * 초대 원장에는 담당 기수(target_cohort_id)만 남는다.
		 */
		return invitation;
	}

	/**
	 * @param batchRequestId 일괄 등록의 폴백으로 불릴 때의 잡 ID. 이 값이 있어야 그 행이 폴링 집계와
	 *                       안전망의 시야에 들어온다 — 배치가 깨져 이 경로로 내려온 행만 잡에서
	 *                       빠지면 진행률이 영영 100%가 되지 않는다
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PendingInvitation createTraineeInvitation(
			InvitationContext context,
			RegisterTraineesRequest.Trainee trainee,
			AuthUser actor,
			String requestId,
			String batchRequestId
	) {
		String email = trainee.email().trim();
		String normalizedEmail = EmailNormalizer.normalize(email);
		Instant now = Instant.now();
		UUID memberId = resolveInvitationSlot(
				context.organizationId(), email, normalizedEmail, trainee.name().trim(), Role.TRAINEE, now
		);
		UUID invitationId = invitationRepository.createInvitation(
				context.organizationId(),
				email,
				normalizedEmail,
				Role.TRAINEE,
				context.cohortId(),
				actor.userId(),
				now,
				batchRequestId
		);
		PendingInvitation invitation = createToken(
				context,
				memberId,
				invitationId,
				email,
				normalizedEmail,
				Role.TRAINEE,
				InvitationPurpose.INVITE_TRAINEE,
				traineePayload(trainee),
				actor.userId(),
				requestId,
				now
		);
		/*
		 * cohort_member는 실제 기수 소속만 표현한다. 초대 대기는 user_invitation.target_cohort_id에
		 * 남기고, 교육생이 링크를 수락하는 트랜잭션에서 ACTIVE 소속을 만든다.
		 */
		return invitation;
	}

	/**
	 * 교육생 자리를 <b>청크 단위로</b> 확보한다(대량 등록 전용).
	 *
	 * <p>{@link #createTraineeInvitation}을 행마다 부르면 행당 8왕복(SELECT 3 + INSERT 3 + UPDATE 1 + 커밋)이라
	 * 900명이 약 7,200왕복이 된다. Supavisor를 거치는 원격 DB에서는 그 지연이 그대로 응답 시간이 되어
	 * 게이트웨이 한도를 넘겼다. 여기서는 판정 3종을 벌크 조회로, 적재 3종을 배치 INSERT로 묶어
	 * 청크당 8왕복 안에서 끝낸다.
	 *
	 * <p><b>청크 하나가 한 트랜잭션이다.</b> 단건 경로가 REQUIRES_NEW인 이유는 "메일 발송이 실패하면
	 * 계정 자리까지 롤백된다"였는데, 지금은 발송이 자리 확보와 분리되어 뒤에 따로 일어나므로
	 * (개선 A) 그 이유가 이 경로에는 해당되지 않는다.
	 *
	 * <p><b>충돌은 예외가 아니라 결과로 돌려준다.</b> 진행 중 초대가 있거나 이미 계정이 있는 행은
	 * {@code invitation}이 {@code null}인 결과가 된다 — 900건 중 한 건 때문에 배치를 던지면
	 * 호출부가 행별로 판정할 수 없다.
	 *
	 * <p>다만 <b>사전 판정과 INSERT 사이의 경합</b>은 여전히 예외로 올라온다. 다른 운영자가 그 틈에 같은
	 * 주소를 등록하면 UNIQUE 위반이 나고 청크 전체가 롤백된다. 그 폴백(청크를 행 단위로 다시 처리)은
	 * 호출부인 {@code TransactionalInvitationDispatcher.reserveTrainees}가 담당한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<TraineeSlot> createTraineeInvitationChunk(
			InvitationContext context,
			List<TraineeSlotRequest> chunk,
			AuthUser actor,
			String baseRequestId
	) {
		Instant now = Instant.now();
		Set<String> normalizedEmails = new LinkedHashSet<>();
		for (TraineeSlotRequest request : chunk) {
			normalizedEmails.add(EmailNormalizer.normalize(request.email().trim()));
		}

		// 판정 3종을 각각 왕복 1회로. 단건 경로의 resolveInvitationSlot과 같은 조건을 쓴다.
		Set<String> incompleteInvitations = invitationRepository.findEmailsWithIncompleteInvitation(normalizedEmails);
		Map<String, UUID> reusableSlots = invitationRepository.findReusableInvitedUsers(normalizedEmails);
		Set<String> existingUsers = invitationRepository.findExistingUserEmails(normalizedEmails);

		List<MemberInvitationRepository.NewPendingUser> newUsers = new ArrayList<>();
		List<MemberInvitationRepository.NewInvitation> newInvitations = new ArrayList<>();
		List<InvitationToken> tokens = new ArrayList<>();
		Map<String, UUID> newTokenByEmail = new LinkedHashMap<>();
		List<TraineeSlot> slots = new ArrayList<>(chunk.size());
		String placeholderHash = pendingPasswordHash.value();

		for (TraineeSlotRequest request : chunk) {
			String email = request.email().trim();
			String name = request.name().trim();
			String normalizedEmail = EmailNormalizer.normalize(email);

			if (incompleteInvitations.contains(normalizedEmail)) {
				slots.add(TraineeSlot.conflict(request));
				continue;
			}

			UUID memberId;
			if (reusableSlots.containsKey(normalizedEmail)) {
				memberId = reusableSlots.get(normalizedEmail);
				/*
				 * 취소된 자리를 되살리는 경로는 단건 UPDATE로 둔다. 재초대는 대량 등록에서 드물어
				 * 배치로 묶어 얻는 이득이 코드 복잡도를 넘지 않는다.
				 */
				invitationRepository.reactivateInvitedUser(
						memberId, context.organizationId(), email, normalizedEmail,
						name, Role.TRAINEE, placeholderHash, now
				);
			} else if (existingUsers.contains(normalizedEmail)) {
				slots.add(TraineeSlot.conflict(request));
				continue;
			} else {
				memberId = UUID.randomUUID();
				newUsers.add(new MemberInvitationRepository.NewPendingUser(
						memberId, context.organizationId(), email, normalizedEmail,
						name, Role.TRAINEE, placeholderHash, now
				));
			}

			UUID invitationId = UUID.randomUUID();
			newInvitations.add(new MemberInvitationRepository.NewInvitation(
					invitationId, context.organizationId(), email, normalizedEmail,
					Role.TRAINEE, context.cohortId(), actor.userId(), now, baseRequestId
			));

			String rawToken = tokenGenerator.generate();
			InvitationToken token = new InvitationToken(
					UUID.randomUUID(),
					context.organizationId(),
					memberId,
					invitationId,
					email,
					normalizedEmail,
					InvitationPurpose.INVITE_TRAINEE,
					tokenHasher.hash(rawToken),
					json(traineePayload(new RegisterTraineesRequest.Trainee(name, email))),
					now,
					now.plus(invitationExpiration),
					actor.userId(),
					// 단건 경로와 같은 형식이다. 토큰별 추적 식별자이며 행 번호까지 남긴다.
					baseRequestId + ":" + request.row()
			);
			tokens.add(token);
			newTokenByEmail.put(normalizedEmail, token.tokenId());
			slots.add(TraineeSlot.reserved(request, new PendingInvitation(
					memberId, invitationId, token.tokenId(), email, rawToken,
					Role.TRAINEE, now, token.expiresAt(), context
			)));
		}

		invitationRepository.createPendingUsers(newUsers);
		invitationRepository.createInvitations(newInvitations);
		invitationRepository.saveTokens(tokens);
		invitationRepository.invalidatePreviousTokensForEmails(
				context.organizationId(), InvitationPurpose.INVITE_TRAINEE, newTokenByEmail, now
		);
		return slots;
	}

	/**
	 * 안전망이 이어받은 교육생 초대의 <b>토큰을 새로 발급한다.</b>
	 *
	 * <p>이어받은 쪽은 원래 토큰의 <b>원문을 모른다</b> — {@code one_time_token.token_hash}는 SHA-256이라
	 * 저장된 값에서 초대 링크를 되살릴 수 없다. 그래서 재발송과 같은 방식으로 새 토큰을 발급하고,
	 * 원래 토큰은 함께 REPLACED로 내린다. 재발송 뒤에도 옛 링크가 살아 있으면 유효한 가입 링크가 둘이 된다.
	 *
	 * <p>초대 원장은 건드리지 않는다 — 새로 만들면 재발송인지 새 초대인지 구분되지 않는다.
	 * 원장의 상태 전환({@code PENDING → SENT / DELIVERY_FAILED})은 발송 결과를 보고 호출부가 기록한다.
	 *
	 * <p><b>기관 하나 분량만 받는다.</b> 이전 토큰 무효화가 기관 단위 질의라 섞어서 부를 수 없다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<InvitationMailSender.TraineeInvitationMail> recreateTraineeInvitationTokens(
			UUID organizationId,
			List<MemberInvitationRepository.StalledInvitation> invitations
	) {
		Instant now = Instant.now();
		List<InvitationToken> tokens = new ArrayList<>(invitations.size());
		Map<String, UUID> newTokenByEmail = new LinkedHashMap<>();
		List<InvitationMailSender.TraineeInvitationMail> mails = new ArrayList<>(invitations.size());

		for (MemberInvitationRepository.StalledInvitation invitation : invitations) {
			String name = traineeName(invitation);
			String rawToken = tokenGenerator.generate();
			InvitationToken token = new InvitationToken(
					UUID.randomUUID(),
					organizationId,
					invitation.userId(),
					invitation.invitationId(),
					invitation.email(),
					invitation.normalizedEmail(),
					InvitationPurpose.INVITE_TRAINEE,
					tokenHasher.hash(rawToken),
					json(traineePayload(new RegisterTraineesRequest.Trainee(name, invitation.email()))),
					now,
					now.plus(invitationExpiration),
					invitation.invitedBy(),
					// 최초 발급분과 구분되게 남긴다. 어느 배치를 안전망이 이어받았는지 로그 없이도 짚을 수 있다.
					invitation.batchRequestId() + ":outbox"
			);
			tokens.add(token);
			newTokenByEmail.put(invitation.normalizedEmail(), token.tokenId());
			mails.add(new InvitationMailSender.TraineeInvitationMail(
					new PendingInvitation(
							invitation.userId(),
							invitation.invitationId(),
							token.tokenId(),
							invitation.email(),
							rawToken,
							Role.TRAINEE,
							now,
							token.expiresAt(),
							invitation.context()
					),
					name
			));
		}

		invitationRepository.saveTokens(tokens);
		invitationRepository.invalidatePreviousTokensForEmails(
				organizationId, InvitationPurpose.INVITE_TRAINEE, newTokenByEmail, now
		);
		return mails;
	}

	/**
	 * 인사말에 쓸 이름. 자리를 확보할 때 반드시 채우므로 비어 있을 수 없지만, 여기서 NPE가 나면
	 * <b>그 주기 전체가 멈춰</b> 나머지 초대까지 발송되지 않는다. 한 건의 인사말이 어색해지는 쪽이 낫다.
	 */
	private static String traineeName(MemberInvitationRepository.StalledInvitation invitation) {
		String name = invitation.name();
		return name == null || name.isBlank() ? invitation.email() : name;
	}

	/** 자리를 확보할 행 하나. */
	public record TraineeSlotRequest(int row, String name, String email) {
	}

	/** 자리 확보 결과. {@code invitation}이 {@code null}이면 이미 등록·초대된 이메일이라 건너뛴 행이다. */
	public record TraineeSlot(TraineeSlotRequest request, PendingInvitation invitation) {
		static TraineeSlot conflict(TraineeSlotRequest request) {
			return new TraineeSlot(request, null);
		}

		static TraineeSlot reserved(TraineeSlotRequest request, PendingInvitation invitation) {
			return new TraineeSlot(request, invitation);
		}

		public boolean isConflict() {
			return invitation == null;
		}
	}

	private PendingInvitation createToken(
			InvitationContext context,
			UUID memberId,
			UUID invitationId,
			String email,
			String normalizedEmail,
			Role role,
			InvitationPurpose purpose,
			Map<String, Object> payload,
			UUID issuedBy,
			String requestId,
			Instant now
	) {
		String rawToken = tokenGenerator.generate();
		InvitationToken token = new InvitationToken(
				UUID.randomUUID(),
				context.organizationId(),
				memberId,
				invitationId,
				email,
				normalizedEmail,
				purpose,
				tokenHasher.hash(rawToken),
				json(payload),
				now,
				now.plus(invitationExpiration),
				issuedBy,
				requestId
		);
		invitationRepository.saveToken(token);
		invitationRepository.invalidatePreviousTokens(token, now);
		return new PendingInvitation(
				memberId,
				invitationId,
				token.tokenId(),
				email,
				rawToken,
				role,
				now,
				token.expiresAt(),
				context
		);
	}

	/**
	 * 재발송용 토큰을 새로 발급한다. 이전 토큰은 {@code createToken} 안에서 REPLACED로 무효화된다 —
	 * 재발송 후에도 옛 링크가 살아 있으면 안 된다.
	 *
	 * <p>초대 원장은 그대로 두고 토큰만 바꾼다. 원장을 새로 만들면 재발송인지 새 초대인지 구분되지 않고
	 * {@code resend_count}도 셀 수 없다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PendingInvitation recreateInvitationToken(
			MemberInvitationRepository.ResendableInvitation invitation,
			AuthUser actor,
			String requestId
	) {
		return createToken(
				invitation.context(),
				invitation.userId(),
				invitation.invitationId(),
				invitation.email(),
				invitation.normalizedEmail(),
				invitation.targetRole(),
				invitation.purpose(),
				resendPayload(invitation),
				actor.userId(),
				requestId,
				Instant.now()
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markInvitationResent(PendingInvitation invitation) {
		invitationRepository.markInvitationResent(
				invitation.invitationId(),
				invitation.tokenId(),
				Instant.now()
		);
	}

	/**
	 * 메일 발송 실패를 원장에 기록한다(목업 case 4·5).
	 *
	 * <p><b>REQUIRES_NEW</b>인 이유: 호출부가 예외를 던져 바깥 트랜잭션을 롤백시키더라도 이 기록은 남아야 한다.
	 * 같은 트랜잭션에 실으면 "실패했다는 사실"까지 함께 사라져, 화면에는 초대가 아예 없었던 것처럼 보인다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markInvitationFailed(PendingInvitation invitation, String failureReason) {
		invitationRepository.markInvitationDeliveryFailed(
				invitation.invitationId(),
				invitation.tokenId(),
				failureReason,
				Instant.now()
		);
	}

	/**
	 * 발송 성공을 <b>배치로</b> 기록한다(대량 등록 전용).
	 *
	 * <p>{@link #markInvitationSent}를 행마다 부르면 900건이 1,800왕복(UPDATE + 커밋)이 된다.
	 * 한 트랜잭션의 배치 UPDATE로 묶으면 왕복이 청크 수만큼으로 줄어든다.
	 *
	 * <p>여기서 예외가 나면 전량이 롤백돼 원장이 PENDING으로 남는다. 메일은 이미 나갔으므로
	 * 그 상태는 "발송했는데 기록을 못 한" 것이며, 화면에는 재발송 가능으로 보인다 —
	 * 중복 발송이 되더라도 초대가 유실되는 것보다 낫다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markInvitationsSent(List<PendingInvitation> invitations) {
		if (invitations.isEmpty()) {
			return;
		}
		List<MemberInvitationRepository.SentInvitation> targets = new ArrayList<>(invitations.size());
		for (PendingInvitation invitation : invitations) {
			targets.add(new MemberInvitationRepository.SentInvitation(
					invitation.invitationId(), invitation.tokenId()
			));
		}
		invitationRepository.markInvitationsSent(targets, Instant.now());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markInvitationSent(PendingInvitation invitation) {
		invitationRepository.markInvitationSent(
				invitation.invitationId(),
				invitation.tokenId(),
				Instant.now()
		);
	}

	private Map<String, Object> managerPayload(InviteManagerRequest request, Role invitedRole) {
		Map<String, Object> payload = basePayload();
		payload.put("role", invitedRole.name());
		payload.put("cohortId", request.cohortId());
		return payload;
	}

	/** 재발송 토큰의 페이로드. 최초 발급 때와 같은 모양을 유지해 수락 경로가 두 갈래로 갈라지지 않게 한다. */
	private Map<String, Object> resendPayload(MemberInvitationRepository.ResendableInvitation invitation) {
		Map<String, Object> payload = basePayload();
		payload.put("role", invitation.targetRole().name());
		if (invitation.targetRole() == Role.TRAINEE) {
			payload.put("name", invitation.name());
		} else if (invitation.targetRole() != Role.SUPER_ADMIN) {
			payload.put("cohortId", invitation.cohortId());
		}
		payload.put("resent", true);
		return payload;
	}

	private Map<String, Object> superAdminPayload() {
		Map<String, Object> payload = basePayload();
		payload.put("role", Role.SUPER_ADMIN.name());
		return payload;
	}

	private Map<String, Object> traineePayload(RegisterTraineesRequest.Trainee trainee) {
		Map<String, Object> payload = basePayload();
		payload.put("name", trainee.name().trim());
		return payload;
	}

	private Map<String, Object> basePayload() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schemaVersion", 1);
		payload.put("generatedBy", "backend");
		return payload;
	}

	private String json(Map<String, Object> payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (Exception exception) {
			throw new IllegalStateException("초대 토큰 페이로드를 생성할 수 없습니다.", exception);
		}
	}

	/**
	 * 초대가 들어갈 계정 자리를 확보한다. 새로 만들거나, <b>취소된 초대의 자리를 되살린다.</b>
	 *
	 * <p>초대를 취소하면 계정은 지워지지 않고 INACTIVE로 남는다(이력 보존). 그 자리가 이메일을 계속
	 * 점유하기 때문에 예전에는 같은 주소로 재초대하면 409가 났다. 활성화된 적 없는 자리라면 새로 만들지 않고
	 * 그 자리를 PENDING으로 되돌린다 — 이력이 한 줄로 이어지고 유니크 제약도 건드리지 않는다.
	 *
	 * <p>진행 중인 초대가 남아 있으면(PENDING·SENT·DELIVERY_FAILED) 그건 재초대가 아니라 중복이므로 막는다.
	 */
	private UUID resolveInvitationSlot(
			UUID organizationId,
			String email,
			String normalizedEmail,
			String name,
			Role role,
			Instant now
	) {
		if (invitationRepository.existsIncompleteInvitationByNormalizedEmail(normalizedEmail)) {
			throw new InvitationConflictException("이미 등록되었거나 초대된 이메일입니다.");
		}

		Optional<UUID> reusable = invitationRepository.findReusableInvitedUser(normalizedEmail);
		if (reusable.isPresent()) {
			UUID userId = reusable.get();
			invitationRepository.reactivateInvitedUser(
					userId, organizationId, email, normalizedEmail, name, role, pendingPasswordHash.value(), now
			);
			return userId;
		}

		if (invitationRepository.existsUserByNormalizedEmail(normalizedEmail)) {
			throw new InvitationConflictException("이미 등록되었거나 초대된 이메일입니다.");
		}
		return invitationRepository.createPendingUser(
				organizationId, email, normalizedEmail, name, role, pendingPasswordHash.value(), now
		);
	}
}
