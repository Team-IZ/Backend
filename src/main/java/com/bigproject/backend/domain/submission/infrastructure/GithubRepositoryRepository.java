package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.domain.Repository;
import com.bigproject.backend.domain.submission.domain.RepositoryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code repository} 테이블 접근.
 *
 * <p>이름 앞에 Github을 붙인 이유는 Spring Data의 {@code org.springframework.data.repository.Repository}와
 * 도메인 엔티티 {@code Repository}가 이미 이름을 쓰고 있어, {@code RepositoryRepository}로 두면 어느
 * 것을 import했는지 읽는 사람이 매번 확인해야 하기 때문이다.
 */
public interface GithubRepositoryRepository extends JpaRepository<Repository, UUID> {

	/** uq_repository_active_per_team 이 팀당 ACTIVE 1건을 보장하므로 결과는 0 또는 1건이다. */
	Optional<Repository> findByTeamIdAndStatus(UUID teamId, RepositoryStatus status);
}
