package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.ProjectCurriculum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectCurriculumRepository extends JpaRepository<ProjectCurriculum, UUID> {

    // "쓰인 회차" 조회용 — 이 교안 버전을 연결한 프로젝트 전체
    List<ProjectCurriculum> findAllByCurriculumVersionId(UUID curriculumVersionId);

    // concept-candidates 조회용 — 이 프로젝트가 연결한 교안 버전 전체
    List<ProjectCurriculum> findAllByProjectIdAndOrgId(UUID projectId, UUID orgId);

    // 상세 조회용 — 화면이 그리는 순서(sequence_no)가 곧 표시 순서라 정렬해서 준다
    List<ProjectCurriculum> findAllByProjectIdAndOrgIdOrderBySequenceNoAsc(UUID projectId, UUID orgId);

    // 목록 화면의 `교안 2개` 셀용. 항목마다 링크 전체를 읽어 세면 목록 하나에 조회가 회차 수만큼 붙는다.
    long countByProjectIdAndOrgId(UUID projectId, UUID orgId);

    boolean existsByProjectIdAndCurriculumVersionId(UUID projectId, UUID curriculumVersionId);

    List<ProjectCurriculum> findAllByProjectIdOrderBySequenceNoDesc(UUID projectId);
}
