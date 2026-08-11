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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	private final PasswordEncoder passwordEncoder;
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
				now
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
				now
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

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PendingInvitation createTraineeInvitation(
			InvitationContext context,
			RegisterTraineesRequest.Trainee trainee,
			AuthUser actor,
			String requestId
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
				now
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
					userId, organizationId, email, normalizedEmail, name, role, pendingPasswordHash(), now
			);
			return userId;
		}

		if (invitationRepository.existsUserByNormalizedEmail(normalizedEmail)) {
			throw new InvitationConflictException("이미 등록되었거나 초대된 이메일입니다.");
		}
		return invitationRepository.createPendingUser(
				organizationId, email, normalizedEmail, name, role, pendingPasswordHash(), now
		);
	}

	private String pendingPasswordHash() {
		return passwordEncoder.encode(UUID.randomUUID().toString());
	}
}
