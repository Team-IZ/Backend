package com.bigproject.backend.domain.organization.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 기관 상세 &gt; 오퍼레이터 탭(목업 SA-02 ②)이 쓰는 계정 조회/상태 변경 포트.
 *
 * <p>오퍼레이터 계정은 app_user에 저장되지만, 이 탭은 "기관에 들어갈 수 있는 사람"을 관리하는 테넌트 운영 화면이라
 * organization 도메인에 둔다. member 도메인의 매니저 조회는 기관 내부(기수·반 배정) 맥락이라 성격이 다르다.
 *
 * <p>{@link OrganizationStatsRepository}와 같은 패턴(인터페이스는 domain, JDBC 구현은 infrastructure)을 따른다.
 */
public interface OrganizationOperatorRepository {

	/** 기관의 오퍼레이터 계정 목록. 삭제된 계정은 제외하고, 정지된 계정은 포함한다(목업: 퇴사한 계정도 지우지 않고 정지로 남긴다). */
	List<OperatorAccount> findOperators(UUID organizationId);

	/** 대상 계정이 이 기관의 오퍼레이터인지 확인하며 함께 조회한다. */
	Optional<OperatorAccount> findOperator(UUID organizationId, UUID memberId);

	/** 기관의 활성(ACTIVE) 오퍼레이터 수. 마지막 1인 정지 차단 판정에 쓴다. */
	int countActiveOperators(UUID organizationId);

	/**
	 * 계정 상태를 변경한다. 변경된 행 수를 반환한다.
	 *
	 * <p>{@code ck_app_user_status_3}이 status='INACTIVE'일 때 {@code inactivated_at}·
	 * {@code inactivated_by}·{@code inactivated_reason_code}를 <b>한 세트로</b> 요구하므로
	 * 정지 정보를 함께 받는다. ACTIVE로 되돌릴 때는 이 값들을 비운다 — 남겨 두면
	 * "지금 활성인데 정지 이력이 붙어 있는" 모순된 행이 된다.
	 *
	 * @param inactivatedBy 정지시킨 사람. ACTIVE로 되돌릴 때는 쓰이지 않는다.
	 * @param reasonCode    정지 사유 코드. ACTIVE로 되돌릴 때는 쓰이지 않는다.
	 * @param reason        사람이 읽을 설명(선택). 코드가 아니라 자유 텍스트 컬럼에 들어간다.
	 */
	int updateOperatorStatus(
			UUID memberId,
			OperatorAccountStatus status,
			UUID inactivatedBy,
			AccountInactivationReason reasonCode,
			String reason
	);

	/** 대기 중인 오퍼레이터 초대 토큰을 조회한다(취소 대상 검증용). */
	Optional<PendingOperatorInvitation> findPendingInvitation(UUID organizationId, UUID tokenId);

	/**
	 * 초대 토큰을 무효화한다(취소). one_time_token은 append-only가 아니라 무효화 컬럼을 갖고 있어
	 * invalidated_at/invalidated_reason을 채우는 방식으로 처리한다.
	 */
	int invalidateInvitation(UUID tokenId, String reason);

	/**
	 * 초대 원장을 CANCELLED로 닫는다.
	 *
	 * <p>토큰만 무효화하면 {@code user_invitation}은 SENT로 남아 두 가지가 어긋난다.
	 * 하나는 감사·통계가 "발송된 초대"로 계속 세는 것이고, 다른 하나는 부분 유니크 인덱스
	 * {@code uq_user_invitation_incomplete}(status IN PENDING·SENT·DELIVERY_FAILED·EXPIRED)가
	 * (기관, 이메일, 역할) 조합을 계속 점유해 <b>같은 주소로 재초대할 수 없게 되는 것</b>이다.
	 *
	 * <p>{@code ck_user_invitation_updated_at_4}가 cancelled_at·cancelled_by를 함께 요구한다.
	 */
	int cancelInvitationLedger(UUID invitationId, UUID cancelledBy);

	/**
	 * 오퍼레이터 계정 한 줄.
	 *
	 * @param name        초대만 되고 아직 활성화되지 않은 계정은 이름이 비어 있을 수 있다(목업에서는 `—`로 표기).
	 * @param invitedAt   최초 초대 시각(one_time_token.issued_at 중 가장 이른 값). 초대 이력이 없으면 null.
	 * @param lastLoginAt 한 번도 로그인하지 않았으면 null(목업에서는 `대기 중`으로 표기).
	 */
	record OperatorAccount(
			UUID memberId,
			String name,
			String email,
			OperatorAccountStatus status,
			Instant invitedAt,
			Instant lastLoginAt,
			UUID pendingInvitationTokenId,

			/**
			 * 가장 최근 초대의 메일 발송이 실패했는지({@code user_invitation.status = 'DELIVERY_FAILED'}).
			 * 목업 case 4·5의 `지정됐지만 초대 메일이 나가지 않았습니다` + [재발송] 배지 근거다.
			 *
			 * <p>계정 상태(PENDING)와 구분해서 내려준다 — 둘 다 "아직 활성화 안 됨"이지만 화면이 할 말이 다르다.
			 * 정상 초대는 "수락 대기", 발송 실패는 "재발송 필요"다.
			 */
			boolean invitationDeliveryFailed
	) {
	}

	/**
	 * 취소 가능한(아직 사용/무효화되지 않은) 초대 토큰.
	 *
	 * @param invitationId 이 토큰이 속한 초대 원장. 취소 시 원장도 함께 닫아야 하므로 같이 가져온다.
	 */
	record PendingOperatorInvitation(UUID tokenId, UUID memberId, UUID invitationId, String targetEmail) {
	}
}
