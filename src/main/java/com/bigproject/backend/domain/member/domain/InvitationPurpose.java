package com.bigproject.backend.domain.member.domain;

public enum InvitationPurpose {
	/**
	 * 슈퍼어드민 초대(목업 SA-03 ②). INVITE_OPERATOR_MANAGER를 재사용해도 동작은 하지만 값이 사실과
	 * 달라지고, 무엇보다 <b>수락 단계에서 목적으로 역할을 가릴 수 없게 된다</b> — 오퍼레이터 토큰으로
	 * 슈퍼어드민이 되는 권한 상승 경로가 열린다. v07 DDL의 ck_one_time_token_purpose에 대응한다.
	 */
	INVITE_SUPER_ADMIN,
	INVITE_OPERATOR_MANAGER,
	INVITE_TRAINEE,
	PASSWORD_RESET
}
