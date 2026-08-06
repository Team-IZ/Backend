package projectexecution.infrastructure;

import projectexecution.domain.ProjectRequirementAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRequirementAssessmentRepository
        extends JpaRepository<ProjectRequirementAssessment, UUID> {

    // 재판정하려면 먼저 "지금 최신 버전"을 찾아야 supersede()를 부를 수 있다.
    // 버전 체인에서 누구에게도 대체되지 않은(=아무도 supersedesAssessmentId로
    // 가리키지 않는) 행이 항상 최신이라는 점을 이용해 조회한다
    @Query("""
            select a from ProjectRequirementAssessment a
            where a.requirementId = :requirementId
              and a.checkpointId = :checkpointId
              and (:teamId is null or a.teamId = :teamId)
              and (:userId is null or a.userId = :userId)
              and not exists (
                  select 1 from ProjectRequirementAssessment b
                  where b.supersedesAssessmentId = a.assessmentId)
            """)
    Optional<ProjectRequirementAssessment> findLatest(@Param("requirementId") UUID requirementId,
                                                      @Param("checkpointId") UUID checkpointId,
                                                      @Param("teamId") UUID teamId,
                                                      @Param("userId") UUID userId);

    // 회차 하나에 대한 판정 전체 (팀·개인 통틀어서)
    List<ProjectRequirementAssessment> findByCheckpointIdAndOrgId(UUID checkpointId, UUID orgId);
}
