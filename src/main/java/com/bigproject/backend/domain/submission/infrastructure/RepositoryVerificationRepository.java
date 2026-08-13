package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.domain.RepositoryVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RepositoryVerificationRepository extends JpaRepository<RepositoryVerification, UUID> {

	/**
	 * 멱등 재요청 판정용. 같은 키로 이미 접수된 확인 실행이 있으면 새로 만들지 않는다.
	 * {@code uq_repository_verification_request_idempotency_key}가 키당 1건을 보장한다.
	 */
	Optional<RepositoryVerification> findByRequestIdempotencyKey(UUID requestIdempotencyKey);
}
