package com.bigproject.backend.domain.member.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CommitEmailRepository {
	Optional<CommitEmail> findByUserId(UUID userId);

	/**
	 * 커밋 이메일을 PENDING으로 등록·변경한다. 갱신된 행 수를 반환한다.
	 * 검증 완료 경로(OAUTH/MANAGER_CONFIRMED)는 이 메서드의 범위가 아니다.
	 */
	int updateCommitEmail(UUID userId, String commitEmail, String normalizedCommitEmail, Instant updatedAt);
}
