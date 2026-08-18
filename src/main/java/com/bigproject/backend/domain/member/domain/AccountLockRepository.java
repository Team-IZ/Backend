package com.bigproject.backend.domain.member.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 운영자가 계정의 로그인을 직접 막고 푸는 원천 포트.
 *
 * <h2>왜 컬럼은 있는데 채우는 곳이 없었나</h2>
 *
 * <p>{@code app_user.login_blocked_until}은 처음부터 있었고 로그인 경로가 <b>읽기까지</b> 했다
 * ({@code AuthService.validateAccount} → 429 {@code LOGIN_TEMPORARILY_BLOCKED}). 그런데 값을 쓰는
 * 코드가 어디에도 없어서(세 곳이 {@code NULL}로 초기화만 했다) <b>영원히 발동하지 않는 검사</b>였다.
 * 계정 탈취가 의심될 때 운영자가 할 수 있는 일이 "정지(INACTIVE)"뿐이었는데, 그건 되돌리는 절차와
 * 이력이 무거워 <b>몇 시간만 막고 싶은</b> 상황에 맞지 않는다.
 *
 * <p>상태({@code status})가 아니라 <b>시각</b>으로 표현하는 것은 정의서의 결정이다 —
 * "LOCKED 상태는 사용하지 않으며 일시 지연은 login_blocked_until로 표현한다".
 * 시각이라 <b>지나면 저절로 풀린다</b>는 것이 이 방식의 요점이다.
 */
public interface AccountLockRepository {

	/** 이 기관의 계정 하나. 다른 기관이거나 삭제됐으면 비어 있다. */
	Optional<LockTarget> findUser(UUID organizationId, UUID userId);

	/**
	 * 로그인 차단 종료 시각을 쓴다. {@code null}이면 해제다.
	 *
	 * <p>해제할 때 {@code failed_login_count}도 함께 0으로 되돌린다 — 카운터를 남겨 두면 다음 실패
	 * 한 번에 다시 막힌다. "풀었다"는 말과 실제 동작이 어긋나는 자리다.
	 *
	 * @return 갱신된 행 수
	 */
	int updateLoginBlockedUntil(UUID userId, Instant lockedUntil);

	/**
	 * @param status         {@code app_user.status} 원문(PENDING·ACTIVE·INACTIVE)
	 * @param lockedUntil    지금 걸려 있는 차단 종료 시각. 없으면 {@code null}
	 */
	record LockTarget(
			UUID userId,
			UUID organizationId,
			String email,
			String name,
			String roleCode,
			String status,
			Instant lockedUntil
	) {
	}
}
