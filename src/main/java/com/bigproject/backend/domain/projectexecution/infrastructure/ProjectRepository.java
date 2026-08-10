package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    // 테넌트 격리: 단건 조회에도 항상 orgId를 같이 건다.
    // findById만 두면 남의 기관 프로젝트를 ID만 알면 조회할 수 있게 된다
    // 삭제된 회차는 조회되지 않는다 — 지운 회차가 상세로 열리면 삭제가 되지 않은 것처럼 보인다(9차 R4)
    Optional<Project> findByProjectIdAndOrgIdAndDeletedAtIsNull(UUID projectId, UUID orgId);

    // 목록 화면 — "미프 5차, 미프 4차..." 최신순. 삭제된 회차는 빠진다
    List<Project> findByCohortIdAndOrgIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID cohortId, UUID orgId);

    /**
     * 프로젝트 생성 시 이름 중복 체크용.
     *
     * <p><b>삭제된 회차도 센다.</b> {@code uq_project_cohort_id_name}이 부분 인덱스가 아니라
     * ({@code WHERE deleted_at IS NULL}이 없다) 소프트 삭제된 행도 그 이름을 계속 점유하기 때문이다.
     * 살아 있는 것만 세면 검사는 통과하고 INSERT가 DB 제약에서 터져 500이 난다 —
     * 사용자에게는 409로 "이미 있는 이름"이라고 알려 주는 편이 맞다(9차 R4).
     */
    boolean existsByCohortIdAndOrgIdAndName(UUID cohortId, UUID orgId, String name);

    /**
     * 다음 sequence_no 채번용. <b>삭제된 회차도 포함해</b> 최대값을 본다 —
     * {@code uq_project_cohort_id_sequence_no}도 부분 인덱스가 아니라, 살아 있는 것만 세면
     * 회차를 지운 뒤 새로 만들 때 번호가 겹친다.
     */
    @Query("select coalesce(max(p.sequenceNo), 0) from Project p where p.cohortId = :cohortId and p.orgId = :orgId")
    int findMaxSequenceNo(@Param("cohortId") UUID cohortId, @Param("orgId") UUID orgId);

    // 자식 엔티티(요구사항 등) 오케스트레이션 전, 소유권만 가볍게 확인할 때. 삭제된 회차는 없는 것으로 본다
    boolean existsByProjectIdAndOrgIdAndDeletedAtIsNull(UUID projectId, UUID orgId);

    // "미프 N차" 라벨 계산용 — MINI_PROJECT만, 삭제 제외, sequence_no 순서대로
    List<Project> findByCohortIdAndOrgIdAndProjectCategoryAndDeletedAtIsNullOrderBySequenceNoAsc(
            UUID cohortId, UUID orgId, ProjectCategory projectCategory);

    /**
     * 위 조회의 여러 기수 판. 라벨을 한 건 만들 때마다 기수의 미니프로젝트 전량을 다시 읽던 것을
     * 한 번으로 접기 위한 것이다(11차 R1) — 교안 섹션 조회가 이 반복 때문에 10초 가까이 걸렸다.
     */
    List<Project> findByCohortIdInAndOrgIdAndProjectCategoryAndDeletedAtIsNullOrderBySequenceNoAsc(
            Collection<UUID> cohortIds, UUID orgId, ProjectCategory projectCategory);

    /** 라벨 대상 프로젝트를 ID 목록으로 한 번에 읽는다(11차 R1). 삭제된 회차는 라벨을 만들지 않는다. */
    List<Project> findByProjectIdInAndOrgIdAndDeletedAtIsNull(Collection<UUID> projectIds, UUID orgId);
}
