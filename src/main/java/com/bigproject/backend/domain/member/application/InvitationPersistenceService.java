package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.InvitationToken;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.ManagerAssignmentRequest;
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

	@Transactional
	public PendingInvitation createManagerInvitation(
			InvitationContext context,
			InviteManagerRequest request,
			AuthUser actor,
			String requestId
	) {
		String email = request.email().trim();
		String normalizedEmail = EmailNormalizer.normalize(email);
		ensureNewEmail(normalizedEmail);
		Role invitedRole = request.role().toRole();
		List<ManagerAssignmentRequest> assignments = managerAssignments(request);
		invitationRepository.validateManagerAssignments(context.organizationId(), assignments);

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
		PendingInvitation invitation = createToken(
				context,
				memberId,
				email,
				normalizedEmail,
				invitedRole,
				InvitationPurpose.INVITE_MANAGER,
				managerPayload(request),
				actor.userId(),
				requestId,
				now
		);
		invitationRepository.saveManagerAssignments(
				memberId,
				context.organizationId(),
				actor.userId(),
				assignments,
				now
		);
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
		PendingInvitation invitation = createToken(
				context,
				memberId,
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
				token.tokenId(),
				email,
				rawToken,
				role,
				now,
				token.expiresAt(),
				context
		);
	}

	private Map<String, Object> managerPayload(InviteManagerRequest request) {
		Map<String, Object> payload = basePayload();
		payload.put("role", request.role().name());
		payload.put("cohortId", request.cohortId());
		payload.put("classroomIds", request.classroomIds());
		return payload;
	}

	private List<ManagerAssignmentRequest> managerAssignments(InviteManagerRequest request) {
		if (request.cohortId() == null) {
			return List.of();
		}
		return List.of(new ManagerAssignmentRequest(request.cohortId(), request.classroomIds()));
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
		if (invitationRepository.existsUserByNormalizedEmail(normalizedEmail)) {
			throw new InvitationConflictException("이미 등록되었거나 초대된 이메일입니다.");
		}
	}

	private String pendingPasswordHash() {
		return passwordEncoder.encode(UUID.randomUUID().toString());
	}
}
