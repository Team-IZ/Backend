package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysis;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * 위 조회의 여러 버전 판(18차 R2). 교안 목록이 버전마다 최신 분석을 따로 읽으면
     * 목록 하나에 조회가 교안 수만큼 붙는다 — 전량을 최신순으로 한 번에 읽고
     * 호출부가 버전별 첫 건만 취한다.
     *
     * <p>정렬이 {@code requestedAt DESC}라 <b>같은 버전 안에서 첫 건이 최신</b>이다.
     * 성공 여부를 가리지 않는 이유는 화면이 `분석 중`·`분석 실패`도 그려야 하기 때문이다.
     */
    List<CurriculumAnalysis> findAllByVersionIdInOrderByRequestedAtDesc(Collection<UUID> versionIds);

    Optional<CurriculumAnalysis> findByVersionIdAndIdempotencyKey(UUID versionId, UUID idempotencyKey);

    long countByVersionId(java.util.UUID versionId);
    // 스케줄러가 PENDING/RUNNING 건을 전부(버전 무관) 찾을 때 사용
    List<CurriculumAnalysis> findAllByStatusIn(List<CurriculumAnalysisStatus> statuses);
}