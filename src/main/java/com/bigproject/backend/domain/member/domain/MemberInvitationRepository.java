package com.bigproject.backend.domain.member.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MemberInvitationRepository {
	Optional<InvitationContext> findActiveOrganization(UUID organizationId);

	Optional<InvitationContext> findInvitableCohort(UUID cohortId);

	boolean existsUserByNormalizedEmail(String normalizedEmail);

	boolean existsIncompleteInvitationByNormalizedEmail(String normalizedEmail);

	boolean existsOrganizationTraineeByNormalizedEmail(UUID organizationId, String normalizedEmail);

	/** 초대 대상 기수가 그 기관에 살아 있는지 확인한다. 매니저 초대의 담당 기수 검증용이다. */
	void validateCohort(UUID organizationId, UUID cohortId);

	UUID createPendingUser(UUID organizationId, String email, String normalizedEmail, String name, Role role, String passwordHash, Instant now);

	UUID createInvitation(
			UUID organizationId,
			String email,
			String normalizedEmail,
			Role targetRole,
			UUID targetCohortId,
			UUID invitedBy,
			Instant invitedAt
	);

	void saveToken(InvitationToken token);

	void markInvitationSent(UUID invitationId, UUID tokenId, Instant sentAt);

	void invalidatePreviousTokens(InvitationToken replacement, Instant invalidatedAt);

	void saveTraineeMembership(UUID memberId, UUID tokenId, UUID organizationId, UUID cohortId, UUID classroomId, UUID assignedBy, Instant joinedAt);
}
