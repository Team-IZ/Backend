package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.domain.member.domain.InvitationPurpose;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountActivationRepository {
	Optional<AccountActivationTarget> findTargetForUpdate(
			String tokenHash,
			UUID userId,
			InvitationPurpose purpose,
			Instant activatedAt
	);

	boolean activateUser(
			UUID userId,
			int expectedRowVersion,
			String name,
			String passwordHash,
			Instant activatedAt
	);

	void saveConsentRecords(List<ConsentRecord> consentRecords);

	boolean activateTraineeMembership(UUID userId, UUID invitationTokenId, Instant activatedAt);

	boolean markInvitationUsed(UUID tokenId, String requestId, Instant usedAt);
}
