package com.bigproject.backend.domain.member.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 매니저·교육생 계정 상태.
 *
 * <p><b>{@code LOCKED}는 9차 Q3-②에 따라 제거했다.</b> {@code OperatorAccountStatus}에서 v06에 폐지된 것과
 * 같은 이유이며, 여기서는 문서상의 정리가 아니라 <b>DB가 그 값을 가질 수 없다</b>는 사실이 근거다 —
 * {@code ck_app_user_status}가 {@code ('PENDING','ACTIVE','INACTIVE')} 세 값만 허용한다.
 * 로그인 연속 실패로 인한 일시 차단은 상태가 아니라 {@code app_user.login_blocked_until} 시각이고,
 * 그 차단은 로그인 API가 {@code 429 LOGIN_TEMPORARILY_BLOCKED}로 알린다.
 *
 * <p>안 오는 값을 타입에 두면 화면이 <b>도달할 수 없는 분기</b>를 계속 들고 있게 되므로 뺐다.
 *
 * <p>{@code INVITED}는 DB의 {@code PENDING}을 member 도메인 용어로 옮긴 것이다(경계에서 변환한다).
 */
@Schema(
		name = "AccountStatus",
		description = """
				계정 상태. `INVITED`(초대됨 — 아직 가입 전) · `ACTIVE`(활성) · `INACTIVE`(정지).

				`LOCKED`는 폐지됐다 — 로그인 연속 실패로 인한 일시 차단은 상태가 아니라
				`login_blocked_until` 시각이며, 로그인 API가 `429 LOGIN_TEMPORARILY_BLOCKED`로 알린다.""",
		enumAsRef = true
)
public enum AccountStatus {
	INVITED,
	ACTIVE,
	INACTIVE
}
