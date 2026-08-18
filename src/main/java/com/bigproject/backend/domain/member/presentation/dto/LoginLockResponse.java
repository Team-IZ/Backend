package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.application.AccountLockService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "로그인 차단 설정·해제 결과")
public record LoginLockResponse(
		@Schema(description = "조치 대상 계정")
		UUID userId,
		@Schema(
				description = "차단이 끝나는 시각. 해제했으면 값이 비어 있다.",
				example = "2026-08-18T09:00:00Z",
				nullable = true)
		Instant lockedUntil,
		@Schema(description = "지금 차단 상태인지. 해제했으면 false다.")
		boolean locked,
		@Schema(
				description = """
						이 조치로 끊은 로그인 세션(리프레시 토큰) 수. **해제일 때는 항상 0**이다.
						차단은 새 로그인만 막으므로, 이미 열려 있는 세션을 함께 끊어야 실제로 막힌다.""",
				example = "2")
		int revokedSessionCount
) {
	public static LoginLockResponse from(AccountLockService.LockResult result) {
		return new LoginLockResponse(
				result.userId(),
				result.lockedUntil(),
				result.lockedUntil() != null,
				result.revokedSessionCount()
		);
	}
}
