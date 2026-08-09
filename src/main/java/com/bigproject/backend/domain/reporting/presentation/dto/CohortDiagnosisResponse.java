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
 * @param excluded    {@code report_snapshot.summary_payload}의 {@code excluded} 키에서 읽는다
 *                    (2026-08-08). 제외 사유별 집계가 {@code report_metric} 어느 섹션에도 없어
 *                    페이로드 경로를 쓴다. 생성 파이프라인이 아직 이 키를 안 채우면 0이다.
 *                    {@code measurement_attempt}를 지금 세는 방법도 있지만 그러면 스냅샷 안에서
 *                    이 숫자만 계속 변한다.
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

	/**
	 * 모수에서 빠진 인원을 사유별로.
	 *
	 * <p>2026-08-08부터 실제 값이 들어온다 — {@code report_snapshot.summary_payload}의
	 * {@code excluded} 키를 읽는다({@code CohortReportQueryRepository.findExcluded}).
	 * 그전까지는 {@link #empty()}가 하드코딩돼 있어 화면 요약 탭의
	 * `전체 응시 − 미응시 − 무효 − 중단 = 채점` 뺄셈이 맞지 않았다.
	 *
	 * <p>🔴 <b>생성 파이프라인이 이 키를 채워야 한다.</b> 안 채우면 여전히 0이다 —
	 * 다만 이제는 조회 경로가 준비돼 있어 파이프라인만 붙이면 값이 따라온다.
	 */
	public record Excluded(int notTaken, int invalid, int interrupted) {

		/** 페이로드에 {@code excluded} 키가 없는 옛 스냅샷용 기본값. */
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
	 *
	 * <h2>🔴 프론트엔드 수정 필요 — {@code level0}이 화면 타입에 없다</h2>
	 *
	 * <p>Frontend {@code operator/report/_/api/types.ts}의 {@code ReachDistribution}은
	 * {@code level1}~{@code level4} + {@code unasked} <b>5개</b>뿐이고 {@code ReachLevel}도
	 * {@code 1|2|3|4}다. 그래서 {@code DistributionBar.tsx}가 분모를
	 * {@code level1+level2+level3+level4+unasked}로 계산해 <b>0단 인원을 통째로 빠뜨린다</b> —
	 * 막대 폭 비율이 전부 부풀려지고, 같은 행의 "2단 이하" 컬럼은 0단을 포함한
	 * {@code belowLevel2Count}를 쓰기 때문에 <b>막대와 숫자가 서로 다른 모집단</b>을 말하게 된다.
	 * {@code labels.ts}의 CSV 내보내기에도 0단 열이 없다.
	 *
	 * <p><b>백엔드는 {@code level0}을 계속 보낸다.</b> 빼면 0단 학생이 막대에도
	 * {@code unasked}에도 안 들어가 어디에서도 세어지지 않고, {@code belowLevel2Count}
	 * (= 0단+1단+2단)를 프론트가 검증할 방법도 사라진다.
	 *
	 * <p>프론트 {@code labels.ts}가 이미 이 문제를 알고 있다 —
	 * "실제 디자인 토큰은 {@code --color-reach-0}~{@code reach-4}(5단계)인데 목업 범례는 4단계만
	 * 정의한다 … 도달 단계가 4단인지 5단인지 실측 대조가 필요하다(PR에 남김)".
	 * 지금이 그 대조 시점이고, 답은 <b>5단</b>이다.
	 *
	 * <p>프론트 수정 범위: {@code types.ts}에 {@code level0} 추가 ·
	 * {@code DistributionBar.tsx}의 {@code total}·{@code segments}·{@code SEGMENT_COLOR} ·
	 * {@code labels.ts}의 라벨·색·CSV 열.
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
	 *
	 * <h2>정렬은 클라이언트 책임이다</h2>
	 *
	 * <p>이 배열은 <b>교안·섹션 순</b>으로 온다(SQL의 {@code ORDER BY cm.title, cs.sequence_no}).
	 * 심각도 순이 아니다. 요약 탭의 "2단 이하가 가장 많았던 개념"은 프론트가
	 * {@code concepts.slice(0, 3)}으로 앞 3개를 자르는데, <b>정렬 없이 자르면 가장 많지 않은
	 * 개념 3개가 뜬다</b>(프론트 주석의 "이미 심각도 내림차순으로 온다"는 mockDb 기준이다).
	 *
	 * <p>서버가 심각도로 정렬하지 않는 이유: 개념별 탭은 교안을 고칠 자리를 찾는 화면이라
	 * 교안 순서가 맞고, 요약 탭만 심각도 순이 필요하다. 한 배열을 두 화면이 다른 순서로 쓰므로
	 * <b>전량을 주고 필요한 쪽이 정렬</b>하는 편이 맞다. 프론트에서
	 * {@code belowLevel2Count / gradedCount} 내림차순으로 정렬한 뒤 자르면 된다.
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

	/**
	 * "기수 전체"도 한 행으로 온다 — 화면이 기준선(세로선)으로 쓴다.
	 *
	 * <p>🔴 <b>이 이름이 계약이다.</b> 화면이 {@code className === '기수 전체'}로 기준선 행을
	 * 찾는다(Frontend {@code DiagnosisSummary.tsx}·{@code ClassOps.tsx}). 뷰가 이 행을 안 주면
	 * {@code CohortReportServiceImpl.toClassRisk}가 합계로 만들어 붙인다 — 없으면 화면이
	 * 에러 없이 조용히 기준선을 안 그려서 발견이 늦다.
	 */
	public record ClassRiskRate(String className, int traineeCount, int atRiskCount) {
	}

	/**
	 * @param round ⚠ 항상 빈 문자열이다. 이 지표의 grain이 {@code CLASS_CONCEPT}(반 × 개념)이라
	 *              회차 축이 없다. 회차별 분해가 필요하면 지표 grain을 늘려야 한다.
	 *              <p>🔴 <b>프론트엔드 수정 필요.</b> 화면({@code ClassOps.tsx})은 이 필드를 쓰지
	 *              않지만 CSV 내보내기({@code labels.ts:exportReportCsv})는 집단 미달 블록의
	 *              `회차` 열에 그대로 넣는다 — 외부로 나가는 파일에 빈 열이 통째로 실린다.
	 *              데이터가 애초에 없으므로 <b>CSV에서 그 열을 빼는 편</b>이 맞다.
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
