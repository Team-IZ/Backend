package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysis;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 결과 반영 직전에 이 분석을 "내가 처리한다"고 선점한다(2026-08-20).
     *
     * <p>스케줄러는 인스턴스 가드가 없어(별도 수정으로 기본 꺼짐이 됐지만, 켜지면 여러 인스턴스가
     * 동시에 돈다) {@code findAllByStatusIn}이 돌려준 같은 행을 두 인스턴스가 동시에 집을 수 있다.
     * 그 둘이 그대로 {@code persistAnalysisResult}를 각자 돌리면 섹션·매핑 행이 두 배로 쌓인다 —
     * {@code CurriculumAnalysis.start()}의 상태 검사는 <b>메모리 안의 detached 엔티티</b>만 보므로
     * 다른 인스턴스가 이미 UPDATE한 DB 행을 볼 방법이 없다.
     *
     * <p>이 UPDATE가 실제 동시성 경계다. {@code expectedStatuses}에 안 걸리면(다른 인스턴스가
     * 먼저 RUNNING 이상으로 옮겨 놨다는 뜻) 0을 반환하고, 호출부는 그 결과 저장을 건너뛴다 —
     * 지는 쪽이 조용히 물러나는 것이 정상 경로이지 오류가 아니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CurriculumAnalysis a
               SET a.status = :newStatus
             WHERE a.analysisId = :analysisId
               AND a.status IN :expectedStatuses
            """)
    int claimForCompletion(
            @Param("analysisId") UUID analysisId,
            @Param("expectedStatuses") Collection<CurriculumAnalysisStatus> expectedStatuses,
            @Param("newStatus") CurriculumAnalysisStatus newStatus);
}