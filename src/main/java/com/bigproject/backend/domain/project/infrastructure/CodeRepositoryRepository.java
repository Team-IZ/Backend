package com.bigproject.backend.domain.project.infrastructure;

package com.bigproject.backend.domain.project.infrastructure;

import com.bigproject.backend.domain.project.domain.CodeRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 이름이 겹쳐 보이지만 정확하다 — CodeRepository 엔티티의 Repository라서
// CodeRepositoryRepository가 맞다. 엔티티 이름을 Repository로 안 지은 이유는
// Spring Data의 마커 인터페이스와 이름이 충돌하기 때문 (CodeRepository.java 참고)

public interface CodeRepositoryRepository extends JpaRepository<CodeRepository, UUID> {

    Optional<CodeRepository> findByRepositoryIdAndOrgId(UUID repositoryId, UUID orgId);

    List<CodeRepository> findByProjectIdAndOrgId(UUID projectId, UUID orgId);

    // team_id/owner_user_id 중 하나만 있으므로 둘을 따로 조회하는 메서드가 필요
    Optional<CodeRepository> findByProjectIdAndTeamId(UUID projectId, UUID teamId);

    Optional<CodeRepository> findByProjectIdAndOwnerUserId(UUID projectId, UUID ownerUserId);
}
