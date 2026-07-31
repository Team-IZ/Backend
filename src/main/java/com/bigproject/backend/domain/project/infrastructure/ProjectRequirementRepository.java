package com.bigproject.backend.domain.project.infrastructure;

import com.bigproject.backend.domain.project.domain.ProjectRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRequirementRepository extends JpaRepository<ProjectRequirement, UUID> {

    Optional<ProjectRequirement> findByRequirementIdAndOrgId(UUID requirementId, UUID orgId);

    // 화면의 "요구사항" 목록 (좋아요 버튼, 댓글 작성, 정렬 기능)을
    // 등록 순서(sequenceNo)대로, 비활성화된 건 빼고 보여준다
    List<ProjectRequirement> findByProjectIdAndOrgIdAndActiveTrueOrderBySequenceNoAsc(
            UUID projectId, UUID orgId);
}
