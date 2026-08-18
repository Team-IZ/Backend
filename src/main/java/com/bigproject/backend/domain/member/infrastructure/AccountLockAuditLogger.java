package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 로그인 차단 설정·해제를 {@code audit_log}에 남긴다.
 *
 * <h2>왜 감사 로그가 이 기능의 일부인가</h2>
 *
 * <p>차단은 <b>사람이 다른 사람의 로그인을 막는</b> 조작이다. 누가 언제 누구를 왜 막았는지가 남지
 * 않으면, 나중에 남는 것은 {@code login_blocked_until}에 찍힌 시각 하나뿐이고 그 값은 자동 차단
 * ({@code LoginAttemptThrottle})과 구분되지 않는다.
 *
 * <p>{@code actor_type}이 {@code 'USER'}인 것이 {@code PasswordResetAuditLogger}(=SYSTEM)와 다른
 * 점이다 — 그쪽은 시스템이 처리한 결과를 남기고, 이쪽은 <b>사람의 조치</b>를 남긴다.
 *
 * <h2>사유는 {@code after_snapshot}에 넣는다</h2>
 *
 * <p>{@code app_user}에는 차단 사유를 담을 컬럼이 없고, 이 하나를 위해 테이블을 늘리지 않는다
 * ({@code inactivated_reason}은 계정 정지용이라 여기 쓰면 정지와 차단이 뒤섞인다). 감사 로그의
 * 스냅샷 컬럼이 원래 이런 값을 담는 자리다.
 */
@Component
@RequiredArgsConstructor
public class AccountLockAuditLogger {

	private final JdbcTemplate jdbcTemplate;
	private final OneTimeTokenHasher hasher;

	public void record(
			UUID organizationId,
			UUID actorUserId,
			UUID targetUserId,
			Instant previousLockedUntil,
			Instant lockedUntil,
			String reason,
			String requestId
	) {
		Instant now = Instant.now();
		UUID auditId = UUID.randomUUID();
		UUID auditRequestId = requestUuid(requestId);
		String eventCode = lockedUntil == null ? "AUTH.LOGIN_LOCK_CLEARED" : "AUTH.LOGIN_LOCK_SET";
		String action = lockedUntil == null
				? "운영자가 계정의 로그인 차단을 해제했다"
				: "운영자가 계정의 로그인을 차단했다";
		String integrityHash = hasher.hash(String.join(
				"|",
				auditId.toString(),
				eventCode,
				"APP_USER",
				targetUserId.toString(),
				"SUCCESS",
				String.valueOf(lockedUntil),
				now.toString(),
				auditRequestId.toString()
		));

		jdbcTemplate.update("""
				INSERT INTO audit_log (
					audit_id, org_id, actor_user_id, actor_type, event_code, action,
					target_type, target_id, result, failure_reason,
					before_snapshot, after_snapshot, request_id, trace_id, correlation_id,
					source_ip, user_agent, occurred_at, recorded_at, integrity_hash
				) VALUES (
					?, ?, ?, 'USER', ?, ?,
					'APP_USER', ?, 'SUCCESS', NULL,
					?::jsonb, ?::jsonb, ?, ?, NULL,
					NULL, NULL, ?, ?, ?
				)
				""",
				auditId,
				organizationId,
				actorUserId,
				eventCode,
				action,
				targetUserId.toString(),
				snapshot(previousLockedUntil, null),
				snapshot(lockedUntil, reason),
				auditRequestId,
				requestId,
				Timestamp.from(now),
				Timestamp.from(now),
				integrityHash);
	}

	/** 값이 두 개뿐이라 문자열로 만든다. 사유는 사람이 적은 자유 문장이므로 따옴표를 이스케이프한다. */
	private String snapshot(Instant lockedUntil, String reason) {
		StringBuilder json = new StringBuilder("{\"loginBlockedUntil\":");
		json.append(lockedUntil == null ? "null" : "\"" + lockedUntil + "\"");
		if (reason != null && !reason.isBlank()) {
			json.append(",\"reason\":\"").append(escape(reason)).append('"');
		}
		return json.append('}').toString();
	}

	private String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", " ").replace("\r", " ").replace("\t", " ");
	}

	/**
	 * {@code audit_log.request_id}는 UUID 컬럼인데 헤더로 오는 값은 아무 문자열이다.
	 * {@code PasswordResetAuditLogger}와 <b>같은 방식</b>으로 변환한다 — 규칙이 갈리면 같은
	 * 요청 ID가 감사 로그에서 두 값으로 나뉜다.
	 */
	private UUID requestUuid(String requestId) {
		try {
			return UUID.fromString(requestId);
		} catch (IllegalArgumentException exception) {
			return UUID.nameUUIDFromBytes(requestId.getBytes(StandardCharsets.UTF_8));
		}
	}
}
