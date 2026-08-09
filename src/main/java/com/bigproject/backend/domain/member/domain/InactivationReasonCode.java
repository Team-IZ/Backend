package com.bigproject.backend.domain.member.domain;

/**
 * 계정 비활성화 사유(11차 R6).
 *
 * <p>{@code app_user.inactivated_reason_code}의 값이며 <b>DB가 다섯 값으로 못 박고 있다</b> —
 * {@code ck_app_user_inactivated_reason_code CHECK (inactivated_reason_code IN
 * ('RESIGNED','ADMIN_SUSPENDED','CONTRACT_ENDED','SECURITY_ACTION','OTHER'))}.
 *
 * <p>예전에는 응답 타입이 {@code string}이라 프론트가 라벨 표를 만들어 두고도 모르는 코드가 오면
 * 조용히 빈칸이 됐다. 실제로 스펙 설명에는 {@code SECURITY}라고 적혀 있었지만 DB가 내보내는 값은
 * {@code SECURITY_ACTION}이었고, {@code OTHER}는 설명에 아예 없었다. enum이면 값이 늘 때
 * 생성 타입이 바뀌어 <b>타입 검사가 라벨 누락을 잡는다</b>.
 *
 * <p>화면 라벨은 프론트가 정한 것을 그대로 쓴다 — 퇴사 · 운영자 조치 · 계약 종료 · 보안 조치 · 기타.
 */
public enum InactivationReasonCode {

	/** 본인 퇴사. */
	RESIGNED,

	/** 운영자가 정지시킨 것. 교육생·매니저 정지 화면에서 오는 정지는 전부 이 값이다. */
	ADMIN_SUSPENDED,

	/** 계약 종료. */
	CONTRACT_ENDED,

	/** 보안 조치. <b>스펙 설명에 적혀 있던 {@code SECURITY}가 아니라 이 값이다.</b> */
	SECURITY_ACTION,

	/** 위 넷에 들어가지 않는 사유. 상세 사유는 {@code inactivatedReason}에 따로 남는다. */
	OTHER;

	/**
	 * DB 문자열을 enum으로. 값이 없으면(활성 계정) null이다.
	 *
	 * <p>DB CHECK가 다섯 값을 강제하므로 <b>모르는 값이 오면 데이터가 규약 밖에서 바뀐 것</b>이다.
	 * 조용히 null로 삼키면 비활성 계정인데 사유가 없는 것처럼 보이므로 그대로 실패시킨다.
	 */
	public static InactivationReasonCode from(String rawValue) {
		if (rawValue == null || rawValue.isBlank()) {
			return null;
		}
		return valueOf(rawValue);
	}
}
