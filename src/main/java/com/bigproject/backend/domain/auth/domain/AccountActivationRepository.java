package com.bigproject.backend.domain.auth.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountActivationRepository {
	/**
	 * 토큰 해시로 초대 상태를 통째로 읽고 확정 대상 행을 잠근다. <b>쓸 수 있는지 걸러 내지 않는다</b> —
	 * 판정은 {@link InvitationStateClassifier}가 하며, 해석 단계와 같은 판정을 공유해야
	 * 링크를 열 때와 제출할 때의 안내가 갈리지 않는다.
	 *
	 * <p>목적·대상 사용자도 조건에 넣지 않는다. 어긋나면 서비스가 무효로 판정한다 —
	 * SQL 조건으로 두면 "목적이 다름"과 "만료"가 다시 빈 값 하나로 합쳐진다.
	 */
	Optional<InvitationState> findStateForUpdate(String tokenHash);

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
