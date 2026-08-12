package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ProjectCurriculum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProjectCurriculumRepository extends JpaRepository<ProjectCurriculum, UUID> {

    // "쓰인 회차" 조회용 — 이 교안 버전을 연결한 프로젝트 전체
    List<ProjectCurriculum> findAllByCurriculumVersionId(UUID curriculumVersionId);

    /**
     * 교안 <b>한 벌의 모든 버전</b>을 쓰는 연결(13차 R1).
     *
     * <p>교안 목록의 {@code usedProjectCount}가 버전이 아니라 교안 기준으로 세므로
     * 상세도 같은 기준이어야 한다 — 두 자리가 다른 기준으로 세면 한 화면 전환 안에서
     * 서로를 부정하는 숫자가 나온다.
     */
    List<ProjectCurriculum> findAllByCurriculumVersionIdIn(Collection<UUID> curriculumVersionIds);

    // concept-candidates 조회용 — 이 프로젝트가 연결한 교안 버전 전체
    List<ProjectCurriculum> findAllByProjectIdAndOrgId(UUID projectId, UUID orgId);

    // 상세 조회용 — 화면이 그리는 순서(sequence_no)가 곧 표시 순서라 정렬해서 준다
    List<ProjectCurriculum> findAllByProjectIdAndOrgIdOrderBySequenceNoAsc(UUID projectId, UUID orgId);

    // 목록 화면의 `교안 2개` 셀용. 항목마다 링크 전체를 읽어 세면 목록 하나에 조회가 회차 수만큼 붙는다.
    long countByProjectIdAndOrgId(UUID projectId, UUID orgId);

    boolean existsByProjectIdAndCurriculumVersionId(UUID projectId, UUID curriculumVersionId);

    List<ProjectCurriculum> findAllByProjectIdOrderBySequenceNoDesc(UUID projectId);
}
