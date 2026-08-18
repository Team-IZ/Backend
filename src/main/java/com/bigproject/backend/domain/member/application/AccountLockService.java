package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.RefreshTokenRepository;
import com.bigproject.backend.domain.member.domain.AccountLockRepository;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.infrastructure.AccountLockAuditLogger;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 운영자의 수동 로그인 차단·해제.
 *
 * <h2>정지(INACTIVE)와 무엇이 다른가</h2>
 *
 * <table>
 *   <tr><th></th><th>계정 정지</th><th>로그인 차단</th></tr>
 *   <tr><td>표현</td><td>{@code status = INACTIVE}</td><td>{@code login_blocked_until} 시각</td></tr>
 *   <tr><td>해제</td><td>사람이 다시 조작해야 한다</td><td><b>시각이 지나면 저절로 풀린다</b></td></tr>
 *   <tr><td>쓰는 자리</td><td>퇴사·계약 종료처럼 되돌리지 않을 변화</td><td>탈취 의심처럼 <b>지금 몇 시간</b> 막아야 할 때</td></tr>
 * </table>
 *
 * <p>정지밖에 없던 시절에는 "일단 몇 시간만 막자"에 정지를 쓰게 되고, 그러면 계정 이력에 퇴사와
 * 같은 흔적이 남는다({@code inactivated_reason_code}). 두 조작을 갈라 두는 이유다.
 *
 * <h2>차단은 세션까지 끊어야 완성된다</h2>
 *
 * <p>{@code login_blocked_until}은 <b>새 로그인</b>만 막는다. 이미 로그인해 둔 창은 리프레시
 * 토큰으로 계속 연장되므로 그대로 두면 "차단했는데 그 사람은 계속 쓰고 있는" 상태가 된다 —
 * 탈취 의심 상황에서 정확히 막아야 하는 것이 그 세션이다. 그래서 차단과 토큰 폐기를
 * <b>같은 트랜잭션</b>에서 한다.
 *
 * <p>해제할 때는 토큰을 건드리지 않는다. 이미 끊어진 세션을 되살릴 방법도 없고, 되살릴 이유도 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountLockService {

	private final AccountLockRepository accountLockRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final AccountLockAuditLogger accountLockAuditLogger;
	private final CurrentUserResolver currentUserResolver;

	/**
	 * @param locked      {@code true}면 차단, {@code false}면 해제. <b>의사를 명시적으로 받는다</b> —
	 *                    시각만 받고 "비어 있으면 해제"로 정하면 본문을 빠뜨린 요청이 조용히 차단을 푼다
	 * @param lockedUntil 차단 종료 시각. {@code locked=true}면 필수이고 {@code false}면 무시된다
	 * @param reason      감사 로그에 남길 사유. {@code app_user}에 사유 컬럼이 없어
	 *                    {@code audit_log.after_snapshot}에 들어간다
	 */
	@Transactional
	public LockResult updateLoginLock(
			UUID organizationId,
			UUID userId,
			boolean locked,
			Instant lockedUntil,
			String reason,
			String requestId
	) {
		if (locked && lockedUntil == null) {
			// 기한 없는 차단은 만들지 않는다 — 저절로 풀리는 것이 이 방식의 요점이다.
			throw new ApiException(MemberErrorCode.LOCK_UNTIL_REQUIRED);
		}
		// 해제 요청에 시각이 실려 와도 무시한다. 둘을 함께 반영할 방법이 없고, 둘 중 무엇을 따랐는지
		// 응답만 보고는 알 수 없는 상태를 만들지 않는다.
		Instant effectiveLockedUntil = locked ? lockedUntil : null;

		AccountLockRepository.LockTarget target = accountLockRepository.findUser(organizationId, userId)
				.orElseThrow(() -> new ApiException(MemberErrorCode.LOCK_TARGET_NOT_FOUND));

		UUID actorUserId = currentUserResolver.resolveCurrentMemberId();
		if (actorUserId.equals(userId) && locked) {
			// 해제는 막지 않는다 — 자기 차단을 푸는 것은 아무도 잠기게 하지 않는다.
			throw new ApiException(MemberErrorCode.LOCK_SELF_NOT_ALLOWED);
		}

		if (accountLockRepository.updateLoginBlockedUntil(userId, effectiveLockedUntil) != 1) {
			throw new ApiException(MemberErrorCode.LOCK_TARGET_NOT_FOUND);
		}

		int revokedSessions = locked
				? refreshTokenRepository.revokeAllByAdmin(userId, actorUserId, Instant.now())
				: 0;

		accountLockAuditLogger.record(
				target.organizationId(),
				actorUserId,
				userId,
				target.lockedUntil(),
				effectiveLockedUntil,
				reason,
				requestId
		);

		return new LockResult(userId, effectiveLockedUntil, revokedSessions);
	}

	/**
	 * @param revokedSessionCount 이 조작으로 끊은 리프레시 토큰 수. 해제일 때는 항상 0이다
	 */
	public record LockResult(UUID userId, Instant lockedUntil, int revokedSessionCount) {
	}
}
