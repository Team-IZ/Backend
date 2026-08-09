package com.bigproject.backend.domain.member.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 매니저 계정 조작(정지·재활성·초대 취소) 원천 포트.
 *
 * <p>오퍼레이터 쪽 {@code OrganizationOperatorRepository}와 <b>같은 테이블·같은 규칙</b>을 쓴다 —
 * 다른 것은 역할 코드({@code MANAGER})와 기관 경계를 세우는 자리뿐이다. 9차 R7이 지적한 대로
 * 오퍼레이터에는 있고 매니저에는 없던 세 조작을 같은 모양으로 맞춘다.
 *
 * <p>초대 목적 코드({@code one_time_token.purpose})는 v06에서 오퍼레이터·매니저가
 * {@code INVITE_OPERATOR_MANAGER} 하나로 합쳐졌다. 그래서 <b>토큰만 보고는 매니저 초대인지 알 수 없고</b>,
 * 이 포트의 조회는 토큰 주인의 역할이 MANAGER인지까지 함께 확인한다 — 아니면 오퍼레이터 초대를
 * 매니저 경로로 취소할 수 있다.
 */
public interface ManagerAccountRepository {

	/** 이 기관의 MANAGER 계정 하나. 다른 역할·다른 기관이면 비어 있다. */
	Optional<ManagerAccount> findManager(UUID orgId, UUID managerId);

	/**
	 * 계정 상태를 바꾼다. INACTIVE로 내릴 때 {@code ck_app_user_status_3}이 요구하는
	 * 정지 시각·정지자·사유 코드를 한 세트로 함께 쓴다.
	 *
	 * @param rawStatus {@code app_user.status} 원문(ACTIVE·INACTIVE)
	 * @return 갱신된 행 수
	 */
	int updateManagerStatus(UUID managerId, String rawStatus, UUID inactivatedBy, String reasonCode, String reason);

	/** 아직 수락·취소되지 않은 매니저 초대 토큰 하나. */
	Optional<PendingManagerInvitation> findPendingInvitation(UUID orgId, UUID tokenId);

	/** 토큰을 무효화한다 — 이미 나간 메일의 링크가 죽는다. */
	int invalidateInvitation(UUID tokenId, String reasonCode);

	/** 초대 원장을 CANCELLED로 닫는다. 닫지 않으면 같은 주소로 재초대할 수 없다. */
	int cancelInvitationLedger(UUID invitationId, UUID cancelledBy);

	/** @param rawStatus {@code app_user.status} 원문(PENDING·ACTIVE·INACTIVE) */
	record ManagerAccount(UUID memberId, String rawStatus) {
	}

	record PendingManagerInvitation(UUID tokenId, UUID memberId, UUID invitationId, String targetEmail) {
	}
}
