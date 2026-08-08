package com.bigproject.backend.domain.reporting.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * OP-05 리포트 조회 포트.
 *
 * <h2>스냅샷을 두 개 읽는다</h2>
 *
 * <p>DB는 기수 리포트를 <b>두 벌</b>로 나눠 둔다 — {@code COHORT_CURRICULUM_DIAGNOSIS}(수업 진단)와
 * {@code COHORT_OUTCOME}(기수 결산). 뷰 명세도 "수업 진단과 다른 Report·Snapshot을 사용하고
 * 한 유형의 실패·미준비가 다른 유형의 발행본을 무효화하지 않는다"고 못박았다.
 *
 * <p>그런데 화면은 <b>문서 하나</b>다(빅프 제거로 탭이 합쳐졌다). 그래서 이 포트가 두 스냅샷을
 * 각각 읽고 서비스가 하나로 합친다. 결산 스냅샷이 아직 없으면 우수 교육생·반 위험·집단 미달
 * 세 섹션이 비고 화면은 {@code IN_PROGRESS}로 그린다 — 프론트 목업의 {@code REPORT_SCENARIO}
 * 동작과 같다.
 */
public interface CohortReportQueryRepository {

	/** 기수 기본 정보 + 두 리포트의 발행 상태. 기수가 없으면 빈 값. */
	java.util.Optional<CohortReportHeader> findHeader(UUID cohortId);

	/** 기수 규모(교육생·반 수). 스냅샷이 아니라 현재 값이다. */
	CohortScale findScale(UUID cohortId);

	/** 교안별 대상·평가·결측 요약. 수업 진단 스냅샷. */
	List<CurriculumSummaryRow> findCurriculumSummaries(UUID diagnosisSnapshotId);

	/** 개념 × 도달 단계 분포. 수업 진단 스냅샷. 한 개념이 단계 수만큼 행으로 나온다. */
	List<ConceptDistributionRow> findConceptDistribution(UUID diagnosisSnapshotId);

	/** 회차 × 개념 × 도달 단계. 수업 진단 스냅샷. 회차별 분포와 개념 목록을 여기서 만든다. */
	List<RoundConceptRow> findRoundConcepts(UUID diagnosisSnapshotId);

	/** 반별 위험자 비율. 결산 스냅샷. */
	List<ClassRiskRow> findClassRisks(UUID outcomeSnapshotId);

	/** 반 × 개념 집단 미달. 결산 스냅샷. */
	List<GroupShortfallRow> findGroupShortfalls(UUID outcomeSnapshotId);

	/** 우수 교육생. 결산 스냅샷. */
	List<TopStudentRow> findTopStudents(UUID outcomeSnapshotId);

	/** 우수 교육생이 우수로 뽑힌 회차. 결산 스냅샷. */
	List<TopStudentRoundRow> findTopStudentRounds(UUID outcomeSnapshotId);

	/**
	 * @param diagnosisSnapshotId 수업 진단 활성 스냅샷. null이면 아직 발행 전이다.
	 * @param outcomeSnapshotId   기수 결산 활성 스냅샷. null이면 화면이 {@code IN_PROGRESS}다.
	 * @param requiredRoundCount  등록된 전체 회차 수. 8 고정이 아니다(OP-02 §4-2).
	 * @param completedRoundCount 끝난 회차 수.
	 */
	record CohortReportHeader(
			UUID cohortId,
			String cohortName,
			LocalDate startDate,
			LocalDate endDate,
			UUID diagnosisReportId,
			UUID diagnosisSnapshotId,
			Instant diagnosisPublishedAt,
			UUID outcomeReportId,
			UUID outcomeSnapshotId,
			Instant outcomePublishedAt,
			int requiredRoundCount,
			int completedRoundCount
	) {
	}

	record CohortScale(int traineeCount, int classCount) {
	}

	/**
	 * @param eligibleCount 대상 인원. 화면 {@code totalSubmissionCount}의 원천.
	 * @param assessedCount 실제 평가된 인원. 화면 {@code gradedCount}.
	 */
	record CurriculumSummaryRow(
			UUID curriculumVersionId,
			String curriculumName,
			int eligibleCount,
			int assessedCount,
			int missingCount
	) {
	}

	/**
	 * @param reachedLevel <b>0~4</b>. 0은 통과한 축이 하나도 없다는 뜻이다.
	 * @param participantCount 그 단계에 있는 인원.
	 * @param sectionTitle 교안 섹션 제목. 화면 `section` 문자열의 앞부분.
	 */
	record ConceptDistributionRow(
			UUID teachesId,
			String conceptName,
			UUID curriculumVersionId,
			String curriculumName,
			Integer curriculumVersionNo,
			String sectionTitle,
			Integer sectionPageStart,
			Integer sectionPageEnd,
			int reachedLevel,
			int participantCount,
			Integer denominator
	) {
	}

	record RoundConceptRow(
			UUID roundId,
			String roundName,
			UUID conceptId,
			String conceptName,
			Integer reachedLevel,
			int participantCount,
			Integer denominator,
			Integer missingCount
	) {
	}

	/** "기수 전체"도 한 행으로 온다 — 화면이 기준선으로 쓴다. */
	record ClassRiskRow(UUID classId, String className, int traineeCount, int atRiskCount) {
	}

	record GroupShortfallRow(
			UUID classId,
			String className,
			UUID teachesId,
			String conceptName,
			String curriculumName,
			String sectionTitle,
			Integer sectionPageStart,
			Integer sectionPageEnd,
			int shortfallCount,
			int totalCount
	) {
	}

	record TopStudentRow(UUID userId, String name, String className, int topCount) {
	}

	/** @param roundNo 우수로 뽑힌 회차 번호. 화면 `miniTopRounds[]`. */
	record TopStudentRoundRow(UUID userId, int roundNo) {
	}
}
