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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
	@Transactional
	public PendingInvitation createSuperAdminInvitation(
			String rawEmail,
			AuthUser actor,
			String requestId
	) {
		String email = rawEmail.trim();
		String normalizedEmail = EmailNormalizer.normalize(email);
		ensureNewEmail(normalizedEmail);

		InvitationContext context = InvitationContext.platform();
		Instant now = Instant.now();
		UUID memberId = invitationRepository.createPendingUser(
				null,
				email,
				normalizedEmail,
				email,
				Role.SUPER_ADMIN,
				pendingPasswordHash(),
				now
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

	@Transactional
	public PendingInvitation createManagerInvitation(
			InvitationContext context,
			InviteManagerRequest request,
			Role invitedRole,
			AuthUser actor,
			String requestId
	) {
		String email = request.email().trim();
		String normalizedEmail = EmailNormalizer.normalize(email);
		ensureNewEmail(normalizedEmail);
		if (request.cohortId() != null) {
			invitationRepository.validateCohort(context.organizationId(), request.cohortId());
		}

		Instant now = Instant.now();
		UUID memberId = invitationRepository.createPendingUser(
				context.organizationId(),
				email,
				normalizedEmail,
				email,
				invitedRole,
				pendingPasswordHash(),
				now
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

	@Transactional
	public PendingInvitation createTraineeInvitation(
			InvitationContext context,
			RegisterTraineesRequest.Trainee trainee,
			AuthUser actor,
			String requestId
	) {
		String email = trainee.email().trim();
		String normalizedEmail = EmailNormalizer.normalize(email);
		ensureNewEmail(normalizedEmail);

		Instant now = Instant.now();
		UUID memberId = invitationRepository.createPendingUser(
				context.organizationId(),
				email,
				normalizedEmail,
				trainee.name().trim(),
				Role.TRAINEE,
				pendingPasswordHash(),
				now
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
		invitationRepository.saveTraineeMembership(
				memberId,
				invitation.tokenId(),
				context.organizationId(),
				context.cohortId(),
				null,
				actor.userId(),
				now
		);
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

	@Transactional
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

	private void ensureNewEmail(String normalizedEmail) {
		if (invitationRepository.existsUserByNormalizedEmail(normalizedEmail)
				|| invitationRepository.existsIncompleteInvitationByNormalizedEmail(normalizedEmail)) {
			throw new InvitationConflictException("이미 등록되었거나 초대된 이메일입니다.");
		}
	}

	private String pendingPasswordHash() {
		return passwordEncoder.encode(UUID.randomUUID().toString());
	}
}
