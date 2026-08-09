package com.bigproject.backend.domain.organization.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 오퍼레이터 계정 상태. app_user.status의 DB CHECK(ck_app_user_status)와 값을 맞춘다.
 *
 * <p>목업 SA-02 ② 오퍼레이터 탭의 배지 매핑:
 * <table>
 *   <tr><th>목업 표기</th><th>이 enum</th></tr>
 *   <tr><td>활성</td><td>{@link #ACTIVE}</td></tr>
 *   <tr><td>초대됨</td><td>{@link #PENDING}</td></tr>
 *   <tr><td>정지 · 퇴사</td><td>{@link #INACTIVE}</td></tr>
 * </table>
 *
 * <p><b>v06에서 LOCKED가 사라졌다.</b> app_user.status CHECK가 ('PENDING','ACTIVE','INACTIVE')로 좁혀지고,
 * 로그인 연속 실패로 인한 일시 차단은 상태값이 아니라 {@code app_user.login_blocked_until} 시각으로 표현한다
 * (정의서: "LOCKED 상태는 사용하지 않으며 일시 지연은 login_blocked_until로 표현"). 즉 잠김은 파생 값이며,
 * 현재 시각과 비교해 판정할 대상이라 계정 상태 배지에는 들어가지 않는다.
 *
 * <p>목업의 `메일 발송 실패` 배지는 v06에서 표현 가능해졌다 — 다만 계정 상태가 아니라
 * {@code user_invitation.status = 'DELIVERY_FAILED'}로 초대 원장에 남는다. 이 enum이 아니라 초대 조회에서 다룬다.
 *
 * <p>member 도메인의 {@code AccountStatus}와 값이 겹치지만, 여기서 그 enum을 쓰지 않는 이유는
 * organization 도메인이 member 도메인 타입에 묶이지 않게 하기 위함이다(DB 문자열에서 직접 매핑한다).
 */
@Schema(name = "OperatorAccountStatus", description = "오퍼레이터·슈퍼어드민 계정 상태. ACTIVE(활성) · PENDING(초대됨, 수락 전) · INACTIVE(정지·퇴사). v06에서 LOCKED는 폐지됐다 — 로그인 연속 실패로 인한 일시 차단은 상태가 아니라 login_blocked_until 시각이다.", enumAsRef = true)
public enum OperatorAccountStatus {
	PENDING,
	ACTIVE,
	INACTIVE
}
