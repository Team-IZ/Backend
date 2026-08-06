package projectexecution.infrastructure;

import projectexecution.domain.ProjectExtractionScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ProjectExtractionScopeRepository
        extends JpaRepository<ProjectExtractionScope, UUID> {

    // 지금 이 순간 유효한 추출 범위 설정 하나 (기간형 조회, TeamMembership과 동일 패턴)
    @Query("""
            select s from ProjectExtractionScope s
            where s.checkpointId = :checkpointId
              and s.effectiveFrom <= :at
              and (s.effectiveTo is null or s.effectiveTo > :at)
            """)
    Optional<ProjectExtractionScope> findEffectiveAt(@Param("checkpointId") UUID checkpointId,
                                                     @Param("at") OffsetDateTime at);
}
