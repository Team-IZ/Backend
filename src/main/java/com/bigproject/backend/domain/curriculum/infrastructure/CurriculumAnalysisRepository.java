package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysis;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CurriculumAnalysisRepository extends JpaRepository<CurriculumAnalysis, UUID> {

    // 같은 version_id에 PENDING/RUNNING이 최대 1건이어야 한다는 불변식 확인용
    List<CurriculumAnalysis> findAllByVersionIdAndStatusIn(UUID versionId, List<CurriculumAnalysisStatus> statuses);

    // "최근 실행"과 "마지막 성공 실행"을 구분해야 한다(정의서) — 이건 후자
    Optional<CurriculumAnalysis> findFirstByVersionIdAndStatusOrderByCompletedAtDesc(
            UUID versionId, CurriculumAnalysisStatus status);

    // 이건 전자 — 화면의 latest_analysis_attempt
    Optional<CurriculumAnalysis> findFirstByVersionIdOrderByRequestedAtDesc(UUID versionId);

    Optional<CurriculumAnalysis> findByVersionIdAndIdempotencyKey(UUID versionId, UUID idempotencyKey);

    long countByVersionId(java.util.UUID versionId);
    // 스케줄러가 PENDING/RUNNING 건을 전부(버전 무관) 찾을 때 사용
    List<CurriculumAnalysis> findAllByStatusIn(List<CurriculumAnalysisStatus> statuses);
}