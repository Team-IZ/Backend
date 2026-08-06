
package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.CurriculumSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CurriculumSectionRepository extends JpaRepository<CurriculumSection, UUID> {

    // 특정 성공 분석이 만든 섹션 전체 — 표시 순서는 sequence_no
    List<CurriculumSection> findAllBySourceAnalysisIdOrderBySequenceNoAsc(UUID sourceAnalysisId);
}