package com.bigproject.backend.domain.project.infrastructure;

import com.bigproject.backend.domain.project.domain.ProjectRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRequirementRepository extends JpaRepository<ProjectRequirement, UUID> {

    /**
     * 프로젝트의 현재 유효한 요구사항 전체 — 화면에 내려줄 목록이자 diff의 "기존" 쪽 재료.
     * sequence_no 순서로 받아야 화면이 보여준 순서와 저장된 순서가 어긋나지 않는다.
     */
    List<ProjectRequirement> findAllByProjectIdAndOrgIdAndActiveTrueOrderBySequenceNoAsc(
            UUID projectId, UUID orgId);

    /**
     * 특정 요구사항의 현재 활성 버전 하나. retire() 대상을 찾을 때 쓴다.
     * 부분 유니크 인덱스(project_id, requirement_key WHERE active)가 보장하듯
     * 결과는 0건 아니면 1건이다.
     */
    Optional<ProjectRequirement> findByProjectIdAndOrgIdAndRequirementKeyAndActiveTrue(
            UUID projectId, UUID orgId, String requirementKey);
}