package com.bigproject.backend.domain.project.infrastructure;

import com.bigproject.backend.domain.project.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    // 테넌트 격리: 단건 조회에도 항상 orgId를 같이 건다.
    // findById만 두면 남의 기관 프로젝트를 ID만 알면 조회할 수 있게 된다
    Optional<Project> findByProjectIdAndOrgId(UUID projectId, UUID orgId);

    // 목록 화면 — "미프 5차, 미프 4차..." 최신순
    List<Project> findByCohortIdAndOrgIdOrderByCreatedAtDesc(UUID cohortId, UUID orgId);

    // 반 추가 모달처럼 프로젝트 생성 시 이름 중복 체크용
    boolean existsByCohortIdAndOrgIdAndNameAndDeletedAtIsNull(UUID cohortId, UUID orgId, String name);

    // 자식 엔티티(요구사항 등) 오케스트레이션 전, 소유권만 가볍게 확인할 때
    boolean existsByProjectIdAndOrgId(UUID projectId, UUID orgId);

    // "미프 N차" 라벨 계산용 — MINI_PROJECT만, 삭제 제외, sequence_no 순서대로
    List<Project> findByCohortIdAndOrgIdAndProjectCategoryAndDeletedAtIsNullOrderBySequenceNoAsc(
            UUID cohortId, UUID orgId, ProjectCategory projectCategory);
}
