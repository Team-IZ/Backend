package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.AccountActivationRepository;
import com.bigproject.backend.domain.auth.domain.AccountActivationTarget;
import com.bigproject.backend.domain.auth.domain.ConsentRecord;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthCohortMemberJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuthUserJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.ConsentRecordJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.OneTimeTokenJpaRepository;
import com.bigproject.backend.domain.auth.infrastructure.jpa.UserInvitationJpaRepository;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JpaAccountActivationRepository implements AccountActivationRepository {
	private final AuthUserJpaRepository userRepository;
	private final OneTimeTokenJpaRepository tokenRepository;
	private final UserInvitationJpaRepository invitationRepository;
	private final ConsentRecordJpaRepository consentRepository;
	private final AuthCohortMemberJpaRepository cohortMemberRepository;

	@Override
	public Optional<AccountActivationTarget> findTargetForUpdate(
			String tokenHash,
			UUID userId,
			InvitationPurpose purpose,
			Instant activatedAt
	) {
		return tokenRepository.findActivationTargetForUpdate(tokenHash, userId, purpose.name(), activatedAt)
				.map(row -> new AccountActivationTarget(
						row.getTokenId(),
						row.getUserId(),
						row.getOrganizationId(),
						row.getEmail(),
						row.getName(),
						Role.valueOf(row.getRoleCode()),
						row.getRowVersion()
				));
	}

	@Override
	public boolean activateUser(
			UUID userId,
			int expectedRowVersion,
			String name,
			String passwordHash,
			Instant activatedAt
	) {
		return userRepository.activate(userId, expectedRowVersion, name, passwordHash, activatedAt) == 1;
	}

	@Override
	public void saveConsentRecords(List<ConsentRecord> consentRecords) {
		for (ConsentRecord consent : consentRecords) {
			int inserted = consentRepository.insert(
					consent.consentId(),
					consent.organizationId(),
					consent.userId(),
					consent.consentCode().name(),
					consent.policyVersion(),
					consent.agreed(),
					consent.capturedAt(),
					consent.captureChannel(),
					consent.sourceIp(),
					consent.userAgent(),
					consent.locale(),
					consent.evidenceHash()
			);
			if (inserted != 1) {
				throw new IllegalStateException("사용자 동의 이력을 저장할 수 없습니다.");
			}
		}
	}

	@Override
	public boolean activateTraineeMembership(UUID userId, UUID invitationTokenId, Instant activatedAt) {
		return cohortMemberRepository.activateByUserId(userId, activatedAt) == 1;
	}

	@Override
	public boolean markInvitationUsed(UUID tokenId, String requestId, Instant usedAt) {
		if (tokenRepository.markInvitationTokenUsed(tokenId, requestId, usedAt) != 1) {
			return false;
		}
		return invitationRepository.markAccepted(tokenId, usedAt) == 1;
	}
}
