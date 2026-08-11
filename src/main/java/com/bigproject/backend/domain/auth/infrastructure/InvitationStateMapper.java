package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.InvitationState;
import com.bigproject.backend.domain.auth.infrastructure.jpa.OneTimeTokenJpaRepository.InvitationStateProjection;
import com.bigproject.backend.domain.member.domain.Role;

/**
 * 진단 조회 결과를 도메인 레코드로 옮긴다. 해석 경로와 활성화 경로가 같은 투영을 쓰므로
 * 변환도 한 곳에 둔다 — 두 벌이면 한쪽만 컬럼이 늘어나 판정이 갈린다.
 */
final class InvitationStateMapper {
	private InvitationStateMapper() {
	}

	static InvitationState toState(InvitationStateProjection row) {
		return new InvitationState(
				row.getTokenId(),
				row.getUserId(),
				row.getOrganizationId(),
				row.getEmail(),
				row.getName(),
				Role.valueOf(row.getRoleCode()),
				row.getRowVersion(),
				row.getPurpose(),
				row.getInvitationStatus(),
				row.getUserStatus(),
				row.getOrganizationStatus(),
				isTrue(row.getCurrentToken()),
				isTrue(row.getEmailMatched()),
				isTrue(row.getOrganizationMatched()),
				isTrue(row.getUserDeleted()),
				isTrue(row.getOrganizationDeleted()),
				isTrue(row.getOnRoster()),
				row.getExpiresAt(),
				row.getUsedAt(),
				row.getInvalidatedAt(),
				row.getInvalidatedReason()
		);
	}

	/**
	 * NULL 은 거짓으로 읽는다. 비교식에 NULL 이 섞이면 결과가 참도 거짓도 아닌 UNKNOWN 인데,
	 * 판정에서는 "확인되지 않았다"가 곧 "통과시키면 안 된다"이므로 거짓이 안전한 쪽이다.
	 */
	private static boolean isTrue(Boolean value) {
		return Boolean.TRUE.equals(value);
	}
}
