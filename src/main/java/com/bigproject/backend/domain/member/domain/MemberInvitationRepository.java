package com.bigproject.backend.domain.member.domain;

import com.bigproject.backend.domain.member.presentation.dto.ManagerAssignmentRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemberInvitationRepository {
	Optional<InvitationContext> findActiveOrganization(UUID organizationId);

	Optional<InvitationContext> findInvitableCohort(UUID cohortId);

	boolean existsUserByNormalizedEmail(String normalizedEmail);

	boolean existsOrganizationTraineeByNormalizedEmail(UUID organizationId, String normalizedEmail);

	void validateManagerAssignments(UUID organizationId, List<ManagerAssignmentRequest> assignments);

	void validateClassroom(UUID organizationId, UUID cohortId, UUID classroomId);

	UUID createPendingUser(UUID organizationId, String email, String normalizedEmail, String name, Role role, String passwordHash, Instant now);

	void saveToken(InvitationToken token);

	void invalidatePreviousTokens(InvitationToken replacement, Instant invalidatedAt);

	void saveManagerAssignments(UUID memberId, UUID organizationId, UUID assignedBy, List<ManagerAssignmentRequest> assignments, Instant assignedAt);

	void saveTraineeMembership(UUID memberId, UUID tokenId, UUID organizationId, UUID cohortId, UUID classroomId, UUID assignedBy, Instant joinedAt);
}
