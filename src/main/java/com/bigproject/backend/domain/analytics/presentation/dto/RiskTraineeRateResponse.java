package com.bigproject.backend.domain.analytics.presentation.dto;

import com.bigproject.backend.domain.analytics.domain.CohortRiskComparison;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeSort;
import com.bigproject.backend.domain.analytics.domain.RoundAggregationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "기수 전체·반별 회차 위험 교육생 비율")
public record RiskTraineeRateResponse(
		@Schema(description = "집계 대상 기수 ID")
		UUID cohortId,
		@Schema(description = "집계 대상 프로젝트 분류이며 미니프로젝트만 지원합니다.", example = "MINI_PROJECT")
		String projectCategory,
		@Schema(description = "조회 대상을 한 프로젝트로 좁혔으면 그 프로젝트 ID이고 좁히지 않았으면 null입니다.")
		UUID projectId,
		@Schema(description = """
				회차 범위 필터를 적용하기 전의 등록 회차 수입니다.
				화면의 회차 범위 선택지를 그리는 데 쓰며 rounds 길이와 다를 수 있습니다.
				""", example = "6")
		int totalRegisteredRoundCount,
		@Schema(description = "실제로 적용된 정렬 기준")
		RiskTraineeSort appliedSort,
		@Schema(description = "격자의 회차 열 정의이며 프로젝트 운영 순서·회차 번호 오름차순입니다.")
		List<RoundColumn> rounds,
		@Schema(description = "기수 전체 행")
		CohortRiskSummary cohortSummary,
		@Schema(description = "반 행 목록")
		List<ClassRiskSummary> classes
) {

	@Schema(description = """
			회차 열 정의.

			roundNo는 ProjectAssessmentRound.round_no이며 (project_id, round_no) UNIQUE라
			프로젝트마다 1부터 다시 시작합니다. 기수에 미니프로젝트가 여러 건이면 같은 roundNo가
			여러 열에 나타나므로 열을 구분할 때 projectId를 함께 보아야 합니다.
			""")
	public record RoundColumn(
			UUID assessmentRoundId,
			@Schema(description = "회차 번호이며 프로젝트 안에서만 유일합니다.", example = "1")
			int roundNo,
			@Schema(description = "회차 이름", example = "K8s 배포 실습")
			String roundName,
			UUID projectId,
			String projectName,
			@Schema(description = "NOT_STARTED(시작 전) / NOT_AGGREGATED(리포트 미발행) / AGGREGATED(발행 완료)")
			RoundAggregationStatus aggregationStatus
	) {
	}

	@Schema(description = "회차 격자 한 칸")
	public record RiskCell(
			UUID assessmentRoundId,
			int roundNo,
			RoundAggregationStatus aggregationStatus,
			@Schema(description = "미집계 인원을 제외한 분모입니다.", example = "24")
			long eligibleCount,
			@Schema(description = "위험 유형(단계 하락·지속 저점)을 하나라도 가진 고유 교육생 수입니다.", example = "6")
			long riskCount,
			@Schema(description = "riskCount / eligibleCount 비율이며 집계 전이거나 분모가 0이면 null입니다.", example = "0.2500")
			BigDecimal riskRate,
			@Schema(description = """
					같은 회차의 기수 전체 비율과 견준 방향입니다.
					BETTER(낮음) / SAME(같음) / WORSE(높음)이며 임계 구간 없이 단순 비교합니다.
					기수 전체 행이거나 두 비율 중 하나라도 없으면 null입니다.
					""")
			CohortRiskComparison comparisonToCohort,
			@Schema(description = "미집계 내역")
			ExclusionBreakdown exclusion
	) {
	}

	@Schema(description = """
			미집계 내역. 세 값 모두 분모에서 제외한 인원이며 eligibleCount와 합치면 회차의 전체 수행 대상자가 됩니다.
			무효 확인 중(validity_review_status=PENDING)은 아직 무효로 확정되지 않아 분모에 남습니다.
			""")
	public record ExclusionBreakdown(
			@Schema(description = "미응시(terminal_reason_code=NOT_ATTENDED) 인원이며 분모에서 제외합니다.")
			long notAttendedCount,
			@Schema(description = "중단(terminal_reason_code=SESSION_INCOMPLETE) 인원이며 분모에서 제외합니다.")
			long sessionIncompleteCount,
			@Schema(description = "무효 응시(validity_review_status=CONFIRMED_INVALID) 인원이며 분모에서 제외합니다.")
			long invalidAttemptCount
	) {

		public long total() {
			return notAttendedCount + sessionIncompleteCount + invalidAttemptCount;
		}
	}

	@Schema(description = "기수 전체 행")
	public record CohortRiskSummary(
			@Schema(description = "중도 이탈하지 않은 현재 기수 교육생 수", example = "250")
			long traineeCount,
			@Schema(description = "중도 이탈한 교육생 수", example = "1")
			long withdrawnCount,
			@Schema(description = """
					최근 발행 회차 기준 미집계 합계입니다. 화면의 '채점에서 빠진 사람' 열에 대응합니다.
					발행된 회차가 없으면 세 값이 모두 0입니다.
					""")
			ExclusionBreakdown exclusionRollup,
			List<RiskCell> cells
	) {
	}

	@Schema(description = "반 행")
	public record ClassRiskSummary(
			UUID classId,
			String className,
			@Schema(description = "중도 이탈하지 않은 현재 반 교육생 수", example = "25")
			long traineeCount,
			@Schema(description = "중도 이탈한 교육생 수", example = "1")
			long withdrawnCount,
			@Schema(description = "최근 발행 회차 기준 미집계 합계입니다.")
			ExclusionBreakdown exclusionRollup,
			List<RiskCell> cells
	) {
	}
}
