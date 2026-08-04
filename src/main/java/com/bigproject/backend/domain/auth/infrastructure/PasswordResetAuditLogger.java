package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.domain.PasswordResetAccount;
import com.bigproject.backend.domain.auth.domain.PasswordResetToken;
import com.bigproject.backend.domain.auth.infrastructure.jpa.AuditLogJpaRepository;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetAuditLogger {
	private final AuditLogJpaRepository auditLogRepository;
	private final OneTimeTokenHasher hasher;

	@Transactional
	public void recordRequestSuccess(PasswordResetAccount account, String requestId) {
		insert(
				account == null ? null : account.organizationId(),
				"AUTH.PASSWORD_RESET_REQUEST",
				"비밀번호 재설정 안내 요청 처리",
				account == null ? "PASSWORD_RESET_REQUEST" : "APP_USER",
				account == null ? requestUuid(requestId).toString() : account.userId().toString(),
				"SUCCESS",
				null,
				requestId
		);
	}

	@Transactional
	public void recordConfirmationSuccess(PasswordResetToken token, String requestId) {
		insert(
				token.organizationId(),
				"AUTH.PASSWORD_RESET_CONFIRMED",
				"비밀번호 재설정 확정",
				"ONE_TIME_TOKEN",
				token.tokenId().toString(),
				"SUCCESS",
				null,
				requestId
		);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailure(PasswordResetToken token, String requestId, String failureCode) {
		try {
			insert(
					token == null ? null : token.organizationId(),
					"AUTH.PASSWORD_RESET_REJECTED",
					"비밀번호 재설정 요청 거부 또는 실패",
					token == null ? "PASSWORD_RESET_REQUEST" : "ONE_TIME_TOKEN",
					token == null ? requestUuid(requestId).toString() : token.tokenId().toString(),
					"FAILURE",
					failureCode,
					requestId
			);
		} catch (RuntimeException exception) {
			log.error("비밀번호 재설정 실패 감사 로그를 저장하지 못했습니다: requestId={}, failureCode={}",
					requestId, failureCode);
		}
	}

	private void insert(
			UUID organizationId,
			String eventCode,
			String action,
			String targetType,
			String targetId,
			String result,
			String failureReason,
			String requestId
	) {
		Instant now = Instant.now();
		UUID auditId = UUID.randomUUID();
		UUID auditRequestId = requestUuid(requestId);
		String integrityHash = hasher.hash(String.join(
				"|",
				auditId.toString(),
				eventCode,
				targetType,
				targetId,
				result,
				now.toString(),
				auditRequestId.toString()
		));
		int inserted = auditLogRepository.insert(
				auditId,
				organizationId,
				eventCode,
				action,
				targetType,
				targetId,
				result,
				failureReason,
				auditRequestId,
				requestId,
				now,
				integrityHash
		);
		if (inserted != 1) {
			throw new IllegalStateException("비밀번호 재설정 감사 로그를 저장할 수 없습니다.");
		}
	}

	private UUID requestUuid(String requestId) {
		try {
			return UUID.fromString(requestId);
		} catch (IllegalArgumentException exception) {
			return UUID.nameUUIDFromBytes(requestId.getBytes(StandardCharsets.UTF_8));
		}
	}
}
