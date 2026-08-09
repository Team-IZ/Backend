package com.bigproject.backend.domain.auth.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuditLogJpaRepository extends JpaRepository<AuditLogJpaEntity, UUID> {
	/**
	 * 재설정 안내와 초대 재발송이 <b>하나의</b> 쿨다운 창을 공유한다. 이벤트 코드를 나누어 세면
	 * 두 엔드포인트를 번갈아 호출해 메일 발송 속도를 두 배로 올릴 수 있다.
	 */
	@Query(value = """
			SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
			FROM audit_log
			WHERE event_code IN ('AUTH.PASSWORD_RESET_REQUEST', 'AUTH.INVITATION_RESEND_REQUEST')
				AND target_type = 'APP_USER'
				AND target_id = :targetId
				AND result = 'SUCCESS'
				AND occurred_at >= :requestedAfter
			""", nativeQuery = true)
	boolean hasRecentPasswordResetRequest(
			@Param("targetId") String targetId,
			@Param("requestedAfter") Instant requestedAfter
	);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			INSERT INTO audit_log (
				audit_id, org_id, actor_user_id, actor_type, event_code, action,
				target_type, target_id, result, failure_reason,
				before_snapshot, after_snapshot, request_id, trace_id, correlation_id,
				source_ip, user_agent, occurred_at, recorded_at, integrity_hash
			) VALUES (
				:auditId, :organizationId, NULL, 'SYSTEM', :eventCode, :action,
				:targetType, :targetId, :result, :failureReason,
				NULL, NULL, :requestId, :traceId, NULL,
				NULL, NULL, :occurredAt, :occurredAt, :integrityHash
			)
			""", nativeQuery = true)
	int insert(
			@Param("auditId") UUID auditId,
			@Param("organizationId") UUID organizationId,
			@Param("eventCode") String eventCode,
			@Param("action") String action,
			@Param("targetType") String targetType,
			@Param("targetId") String targetId,
			@Param("result") String result,
			@Param("failureReason") String failureReason,
			@Param("requestId") UUID requestId,
			@Param("traceId") String traceId,
			@Param("occurredAt") Instant occurredAt,
			@Param("integrityHash") String integrityHash
	);
}
