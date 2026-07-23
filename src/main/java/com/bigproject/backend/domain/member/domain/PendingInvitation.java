package com.bigproject.backend.domain.member.domain;

import java.time.Instant;
import java.util.UUID;

public record PendingInvitation(
		UUID memberId,
		UUID tokenId,
		String email,
		String rawToken,
		Role role,
		Instant invitedAt,
		Instant expiresAt,
		InvitationContext context
) {
}
