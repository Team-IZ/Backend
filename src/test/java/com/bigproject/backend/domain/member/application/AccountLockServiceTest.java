package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.RefreshTokenRepository;
import com.bigproject.backend.domain.member.domain.AccountLockRepository;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.infrastructure.AccountLockAuditLogger;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 운영자 수동 로그인 차단.
 *
 * <p>여기서 지키는 것은 <b>"차단했다"는 말과 실제 상태가 같은가</b>이다. 컬럼 하나를 쓰는 것으로는
 * 차단이 완성되지 않는다 — 이미 열려 있는 세션이 리프레시 토큰으로 계속 연장되기 때문이다.
 */
class AccountLockServiceTest {

	private static final UUID ORGANIZATION_ID = UUID.randomUUID();
	private static final UUID TARGET_USER_ID = UUID.randomUUID();
	private static final UUID ACTOR_USER_ID = UUID.randomUUID();

	private final AccountLockRepository accountLockRepository = mock(AccountLockRepository.class);
	private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
	private final AccountLockAuditLogger auditLogger = mock(AccountLockAuditLogger.class);
	private final CurrentUserResolver currentUserResolver = mock(CurrentUserResolver.class);

	private final AccountLockService service = new AccountLockService(
			accountLockRepository, refreshTokenRepository, auditLogger, currentUserResolver);

	@BeforeEach
	void setUp() {
		when(currentUserResolver.resolveCurrentMemberId()).thenReturn(ACTOR_USER_ID);
		when(accountLockRepository.findUser(ORGANIZATION_ID, TARGET_USER_ID))
				.thenReturn(Optional.of(target(null)));
		when(accountLockRepository.updateLoginBlockedUntil(eq(TARGET_USER_ID), any())).thenReturn(1);
		when(accountLockRepository.updateLoginBlockedUntil(eq(TARGET_USER_ID), isNull())).thenReturn(1);
	}

	/**
	 * 차단은 <b>새 로그인만</b> 막는다. 토큰을 함께 끊지 않으면 이미 로그인해 둔 창이 계속 살아 있어
	 * "차단했는데 그 사람은 계속 쓰고 있는" 상태가 된다 — 탈취 의심 상황에서 정확히 막아야 하는 것이 그 세션이다.
	 */
	@Test
	void cutsTheSessionsThatAreAlreadyOpenWhenItLocks() {
		Instant lockedUntil = Instant.now().plus(Duration.ofHours(2));
		when(refreshTokenRepository.revokeAllByAdmin(eq(TARGET_USER_ID), eq(ACTOR_USER_ID), any()))
				.thenReturn(2);

		AccountLockService.LockResult result = service.updateLoginLock(
				ORGANIZATION_ID, TARGET_USER_ID, true, lockedUntil, "비정상 로그인 확인", "request-lock");

		assertThat(result.lockedUntil()).isEqualTo(lockedUntil);
		assertThat(result.revokedSessionCount()).isEqualTo(2);
		verify(accountLockRepository).updateLoginBlockedUntil(TARGET_USER_ID, lockedUntil);
		verify(refreshTokenRepository).revokeAllByAdmin(eq(TARGET_USER_ID), eq(ACTOR_USER_ID), any());
	}

	/** 사람이 사람을 막는 조작이라 이력이 남아야 한다. 남지 않으면 자동 차단과 구분되지 않는다. */
	@Test
	void leavesAnAuditTrailOfWhoLockedWhomAndWhy() {
		Instant lockedUntil = Instant.now().plus(Duration.ofHours(2));

		service.updateLoginLock(ORGANIZATION_ID, TARGET_USER_ID, true, lockedUntil, "탈취 의심", "request-lock");

		verify(auditLogger).record(
				ORGANIZATION_ID, ACTOR_USER_ID, TARGET_USER_ID, null, lockedUntil, "탈취 의심", "request-lock");
	}

	/** 이미 끊어진 세션은 되살릴 수도 없고 되살릴 이유도 없다. 해제는 컬럼만 비운다. */
	@Test
	void doesNotTouchTokensWhenItUnlocks() {
		when(accountLockRepository.findUser(ORGANIZATION_ID, TARGET_USER_ID))
				.thenReturn(Optional.of(target(Instant.now().plus(Duration.ofHours(1)))));

		AccountLockService.LockResult result = service.updateLoginLock(
				ORGANIZATION_ID, TARGET_USER_ID, false, null, "본인 확인 완료", "request-unlock");

		assertThat(result.lockedUntil()).isNull();
		assertThat(result.revokedSessionCount()).isZero();
		verify(refreshTokenRepository, never()).revokeAllByAdmin(any(), any(), any());
	}

	/**
	 * 오퍼레이터가 한 명뿐인 기관에서 자기를 막으면 <b>풀어 줄 사람이 없어진다</b> —
	 * 해제도 같은 권한을 요구하기 때문이다.
	 */
	@Test
	void refusesToLetAnOperatorLockThemselvesOut() {
		when(accountLockRepository.findUser(ORGANIZATION_ID, ACTOR_USER_ID))
				.thenReturn(Optional.of(new AccountLockRepository.LockTarget(
						ACTOR_USER_ID, ORGANIZATION_ID, "op@example.com", "운영자", "OPERATOR", "ACTIVE", null)));

		assertThatThrownBy(() -> service.updateLoginLock(
				ORGANIZATION_ID, ACTOR_USER_ID, true, Instant.now().plus(Duration.ofHours(1)), null, "request-self"))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.LOCK_SELF_NOT_ALLOWED));
		verify(accountLockRepository, never()).updateLoginBlockedUntil(any(), any());
	}

	/** 반대로 <b>자기 차단을 푸는 것</b>은 아무도 잠기게 하지 않으므로 막지 않는다. */
	@Test
	void stillLetsThemClearTheirOwnLock() {
		when(accountLockRepository.findUser(ORGANIZATION_ID, ACTOR_USER_ID))
				.thenReturn(Optional.of(new AccountLockRepository.LockTarget(
						ACTOR_USER_ID, ORGANIZATION_ID, "op@example.com", "운영자", "OPERATOR", "ACTIVE", null)));
		when(accountLockRepository.updateLoginBlockedUntil(eq(ACTOR_USER_ID), isNull())).thenReturn(1);

		assertThatCode(() -> service.updateLoginLock(
				ORGANIZATION_ID, ACTOR_USER_ID, false, null, null, "request-self"))
				.doesNotThrowAnyException();
	}

	/** 다른 기관의 계정도 여기로 온다 — 존재 여부를 알려 주지 않는다. */
	@Test
	void doesNotRevealAccountsOutsideTheCallersOrganization() {
		UUID stranger = UUID.randomUUID();
		when(accountLockRepository.findUser(ORGANIZATION_ID, stranger)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.updateLoginLock(
				ORGANIZATION_ID, stranger, true, Instant.now().plus(Duration.ofHours(1)), null, "request-x"))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.LOCK_TARGET_NOT_FOUND));
		verify(refreshTokenRepository, never()).revokeAllByAdmin(any(), any(), any());
	}

	/**
	 * 종료 시각이 비면 아무도 풀어 주지 않는 한 <b>영구 차단</b>이 된다. 저절로 풀린다는 것이 이
	 * 방식의 요점이므로, 기한 없는 차단은 만들 수 없어야 한다 — 그건 계정 정지가 할 일이다.
	 */
	@Test
	void refusesToCreateALockThatWouldNeverExpireOnItsOwn() {
		assertThatThrownBy(() -> service.updateLoginLock(
				ORGANIZATION_ID, TARGET_USER_ID, true, null, null, "request-no-until"))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.LOCK_UNTIL_REQUIRED));
		verify(accountLockRepository, never()).updateLoginBlockedUntil(any(), any());
	}

	/**
	 * 해제 요청에 시각이 실려 와도 무시한다. 둘을 함께 반영할 방법이 없는데 하나를 조용히 따르면,
	 * 무엇이 반영됐는지 응답만 보고는 알 수 없는 상태가 된다.
	 */
	@Test
	void ignoresAnExpiryThatCameAlongWithAnUnlock() {
		AccountLockService.LockResult result = service.updateLoginLock(
				ORGANIZATION_ID, TARGET_USER_ID, false, Instant.now().plus(Duration.ofHours(5)), null, "request-x");

		assertThat(result.lockedUntil()).isNull();
		verify(accountLockRepository).updateLoginBlockedUntil(TARGET_USER_ID, null);
	}

	private AccountLockRepository.LockTarget target(Instant lockedUntil) {
		return new AccountLockRepository.LockTarget(
				TARGET_USER_ID, ORGANIZATION_ID, "trainee@example.com", "교육생", "TRAINEE", "ACTIVE", lockedUntil);
	}
}
