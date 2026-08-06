package projectexecution.infrastructure;

import projectexecution.domain.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByTeamIdAndOrgId(UUID teamId, UUID orgId);

    // 프로젝트 하나에 속한 팀 전체 목록
    List<Team> findByProjectIdAndOrgId(UUID projectId, UUID orgId);
}
