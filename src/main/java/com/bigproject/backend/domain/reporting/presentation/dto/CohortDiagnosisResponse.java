package com.bigproject.backend.domain.reporting.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * OP-05 리포트 응답. Frontend {@code src/features/operator/report/_/api/types.ts}의
 * {@code Report}와 1:1이다 — 프론트는 {@code getReport(cohortId)} 한 번만 부르고
 * 이 응답 하나로 5개 섹션을 전부 그린다.
 *
 * <p>매핑표에는 이 화면 엔드포인트가 4개였다(수업 진단·기수 결산·집단 미달·우수 교육생).
 * 빅프로젝트가 제품에서 빠지며 탭 2개가 1개로 합쳐졌고(Frontend #93·#94), 나머지 셋은
 * 이 응답의 필드가 됐다. <b>발행 시점에 얼린 스냅샷</b>이라 4번 나눠 부르면 서로 다른
 * 스냅샷을 볼 위험도 있었다.
 *
 * @param status   {@code CONFIRMED}는 기수 결산 스냅샷까지 발행됐다는 뜻이다.
 *                 {@code IN_PROGRESS}면 {@code topStudents}·{@code classRisk}·
 *                 {@code groupShortfalls} 세 배열이 비어 있다.
 * @param publishedAt 확정 전이면 {@code null}. 화면이 PDF를 잠그는 근거이기도 하다.
 * @param periodEnd   확정 전이면 아직 안 끝났으므로 {@code null}.
 * @param totalRounds 등록된 전체 회차 수. <b>8 고정이 아니다</b>(OP-02 §4-2).
 * @param excluded    ⚠ 현재 전부 0이다 — 제외 사유별 집계가 {@code report_metric} 어느
 *                    섹션에도 없다. 생성 파이프라인이 {@code report_snapshot.summary_payload}에
 *                    적재하도록 추가한 뒤 여기서 읽는다. {@code measurement_attempt}를 지금
 *                    세는 방법도 있지만 그러면 스냅샷 안에서 이 숫자만 계속 변한다.
 */
public record CohortDiagnosisResponse(
		String status,
		String cohortName,
		@Schema(description = "기수 결산 확정 시각. 확정 전이면 null — 화면이 PDF를 잠그는 근거다.", nullable = true)
		String publishedAt,
		int completedRounds,
		int totalRounds,
		String periodStart,
		@Schema(description = "집계 종료일. 확정 전이면 아직 끝나지 않았으므로 null", nullable = true)
		String periodEnd,
		int traineeCount,
		int classCount,
		int conceptCount,
		int curriculumCount,
		int gradedCount,
		int totalSubmissionCount,
		Excluded excluded,
		List<ConceptDiagnosis> concepts,
		List<RoundDiagnosis> rounds,
		List<TopStudent> topStudents,
		String classRiskRoundLabel,
		List<ClassRiskRate> classRisk,
		List<GroupShortfall> groupShortfalls
) {

	/** 모수에서 빠진 인원을 사유별로. 위 javadoc 참고 — 지금은 전부 0이다. */
	public record Excluded(int notTaken, int invalid, int interrupted) {

		public static Excluded empty() {
			return new Excluded(0, 0, 0);
		}
	}

	/**
	 * 도달 단계 분포. <b>0단이 있다</b> — 기획 {@code 10-measurement-foundation.md} §5가
	 * "여기에 0단 = 응답 없음/무성의를 둔다"로 정한 값이고, DB {@code report_metric.reached_level}
	 * (0~4)과 AI {@code reachedStage}(0~4)도 같은 눈금이다.
	 *
	 * <p>{@code unasked}는 분포와 <b>별도로</b> 센다. 그 학생 코드에 개념이 없어 문항이 안
	 * 만들어진 것이라 채점 실패도 0단도 아니다 — "못한 것"과 "안 물어본 것"은 다르다.
	 */
	public record ReachDistribution(
			int level0,
			int level1,
			int level2,
			int level3,
			int level4,
			int unasked
	) {
	}

	/**
	 * @param curriculumVersion {@code 'v' + curriculum_version.version_no} 형식(예: {@code v2}).
	 * @param section 교안 섹션. {@code 제목 (p.시작–끝)} 형식(예: {@code 네트워킹 (p.28–40)}).
	 *                구분자는 하이픈이 아니라 en-dash다.
	 * @param rounds  이 개념이 쓰인 회차 이름들.
	 * @param belowLevel2Count 2단 이하 인원. <b>0·1·2단의 합</b>이며 위험 판정선이자 재시험 대상선이다
	 *                         (DB {@code low_level_count = reached_level IN (0,1,2)}와 같은 정의).
	 */
	public record ConceptDiagnosis(
			String id,
			String name,
			String curriculumName,
			String curriculumVersion,
			String section,
			List<String> rounds,
			ReachDistribution distribution,
			int gradedCount,
			int totalCount,
			int belowLevel2Count
	) {
	}

	public record RoundDiagnosis(
			String id,
			String name,
			List<String> conceptNames,
			ReachDistribution distribution,
			int gradedCount,
			int totalCount,
			int belowLevel2Count
	) {
	}

	/** @param miniTopRounds 우수로 뽑힌 회차 번호(예: {@code [1, 2, 5]}). */
	public record TopStudent(
			String id,
			String name,
			String className,
			int miniTopCount,
			List<Integer> miniTopRounds
	) {
	}

	/** "기수 전체"도 한 행으로 온다 — 화면이 기준선(세로선)으로 쓴다. */
	public record ClassRiskRate(String className, int traineeCount, int atRiskCount) {
	}

	/**
	 * @param round ⚠ 항상 빈 문자열이다. 이 지표의 grain이 {@code CLASS_CONCEPT}(반 × 개념)이라
	 *              회차 축이 없다. 회차별 분해가 필요하면 지표 grain을 늘려야 한다.
	 */
	public record GroupShortfall(
			String conceptName,
			String curriculumName,
			String section,
			String round,
			String className,
			int shortfallCount,
			int totalCount
	) {
	}
}
