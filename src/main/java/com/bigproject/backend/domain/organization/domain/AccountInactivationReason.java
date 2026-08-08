package com.bigproject.backend.domain.organization.domain;

/**
 * 계정 정지 사유 코드. {@code app_user.inactivated_reason_code} 에 저장되며
 * DB CHECK({@code ck_app_user_inactivated_reason_code})와 1:1로 맞춘다.
 *
 * <p><b>자유 문장을 넣는 컬럼이 아니다.</b> 사람이 읽을 설명은 별도 컬럼
 * {@code inactivated_reason}(TEXT)에 담는다. 코드 컬럼에 설명을 넣으면 CHECK 위반으로
 * UPDATE 자체가 실패한다.
 *
 * <p>{@code ck_app_user_status_3}이 status='INACTIVE'일 때
 * {@code inactivated_at}·{@code inactivated_by}·{@code inactivated_reason_code} 세 개를
 * <b>한 세트로</b> 요구한다. 정지 처리는 반드시 셋을 함께 써야 한다.
 */
public enum AccountInactivationReason {

	/** 퇴사. */
	RESIGNED,

	/** 관리자가 계정을 정지시킴. 오퍼레이터·슈퍼어드민 정지와 초대 취소가 여기에 해당한다. */
	ADMIN_SUSPENDED,

	/** 계약 종료. */
	CONTRACT_ENDED,

	/** 보안 조치. */
	SECURITY_ACTION,

	/** 위 어디에도 들어맞지 않는 경우. */
	OTHER
}
