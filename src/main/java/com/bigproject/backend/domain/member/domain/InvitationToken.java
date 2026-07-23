package com.bigproject.backend.domain.member.domain;

import java.time.Instant;
import java.util.UUID;

public record InvitationToken(
		UUID tokenId,
		UUID organizationId,
		UUID userId,
		String targetEmail,
		String normalizedTargetEmail,
		InvitationPurpose purpose,
		String tokenHash,
		String payload,
		Instant issuedAt,
		Instant expiresAt,
		UUID issuedBy,
		String issuedRequestId
) {
}
