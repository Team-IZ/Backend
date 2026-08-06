package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.domain.RepositoryVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RepositoryVerificationRepository extends JpaRepository<RepositoryVerification, UUID> {

	/** 멱등 재요청 판정용. 같은 {@code requestId}로 이미 접수된 확인 실행이 있으면 새로 만들지 않는다. */
	Optional<RepositoryVerification> findByRequestId(UUID requestId);
}
