package com.bigproject.backend.domain.analytics.presentation.dto;

import com.bigproject.backend.domain.analytics.domain.CohortRiskComparison;
import com.bigproject.backend.domain.analytics.domain.RiskTraineeLevel;
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
		@Schema(description = "조회 대상을 한 프로젝트로 좁혔으면 그 프로젝트 ID이고 좁히지 않았으면 null입니다.", nullable = true)
		UUID projectId,
		@Schema(description = """
				회차 범위 필터를 적용하기 전의 등록 회차 수입니다.
				화면의 회차 범위 선택지를 그리는 데 쓰며 rounds 길이와 다를 수 있습니다.
				""", example = "6")
		int totalRegisteredRoundCount,
		@Schema(description = "실제로 적용된 정렬 기준")
		RiskTraineeSort appliedSort,
		@Schema(description = "행 계층이며 CLASS면 classes가, TEAM이면 teams가 채워집니다.")
		RiskTraineeLevel level,
		@Schema(description = "격자의 회차 열 정의이며 프로젝트 운영 순서·회차 번호 오름차순입니다.")
		List<RoundColumn> rounds,
		@Schema(description = "기수 전체 행이며 팀 계층에서도 색 판정 기준으로 함께 내려갑니다.")
		CohortRiskSummary cohortSummary,
		@Schema(description = """
				반 행 목록입니다. level=CLASS면 기수의 모든 반이 담기고,
				level=TEAM이면 선택된 반 1건만 담깁니다 — 화면 상단 '반 전체' 요약 행이자
				teams[].cells[].comparisonToCohort의 비교 기준입니다.
				""")
		List<ClassRiskSummary> classes,
		@Schema(description = "팀 행 목록이며 level=CLASS이면 빈 배열입니다.")
		List<TeamRiskSummary> teams
) {

	@Schema(description = """
			회차 열 정의.

			## 회차 번호가 두 축입니다 (12차 R1)

			| 필드 | 무엇 | 어디서 유일한가 |
			|---|---|---|
			| `cohortRoundNo` | **기수 안의 회차 순번** | 기수 안에서 유일 — **열 키로 쓰세요** |
			| `roundNo` | 프로젝트 안의 응시 번호 | 프로젝트 안에서만 유일 |

			**`cohortRoundNo`가 요청의 `fromRoundNo`·`toRoundNo`와 같은 축입니다.**
			받은 값을 그대로 범위 조건에 다시 넣을 수 있습니다.

			`roundNo`는 `(project_id, round_no)` UNIQUE라 프로젝트마다 1부터 다시 시작하며,
			미니프로젝트는 프로젝트당 이해도 확인 회차가 1건뿐이라 **늘 1**입니다.
			그래서 이것을 격자의 열 키로 쓰면 여섯 열이 전부 같은 키가 됩니다.
			""")
	public record RoundColumn(
			@Schema(description = "회차 ID이며 ProjectAssessmentRound.assessment_round_id입니다.")
			UUID assessmentRoundId,
			@Schema(description = "프로젝트 안의 응시 번호이며 **프로젝트 안에서만 유일합니다**. "
					+ "미니프로젝트는 늘 1이라 열 키로 쓸 수 없습니다 — `cohortRoundNo`를 쓰세요.", example = "1")
			int roundNo,
			@Schema(description = "**기수 안의 회차 순번.** 요청의 `fromRoundNo`·`toRoundNo`와 같은 축이라 "
					+ "받은 값을 그대로 범위 조건에 다시 넣을 수 있습니다. 격자의 열 키로 쓰기에 알맞습니다(12차 R1).",
					example = "4")
			int cohortRoundNo,
			@Schema(description = "회차 이름", example = "K8s 배포 실습")
			String roundName,
			@Schema(description = "회차가 속한 프로젝트 ID입니다.")
			UUID projectId,
			@Schema(description = "회차가 속한 프로젝트 이름이며 화면의 '미프 N차' 표기의 기준입니다.", example = "미니프로젝트 2")
			String projectName,
			@Schema(description = "NOT_STARTED(시작 전) / NOT_AGGREGATED(리포트 미발행) / AGGREGATED(발행 완료)")
			RoundAggregationStatus aggregationStatus
	) {
	}

	@Schema(description = "회차 격자 한 칸")
	public record RiskCell(
			@Schema(description = "이 칸이 속한 회차 ID이며 rounds[].assessmentRoundId와 짝을 이룹니다. "
					+ "칸을 열에 잇는 것은 이 값으로 하세요.")
			UUID assessmentRoundId,
			@Schema(description = "프로젝트 안의 응시 번호이며 **프로젝트 안에서만 유일합니다**(rounds[].roundNo와 같은 값).",
					example = "1")
			int roundNo,
			@Schema(description = "**기수 안의 회차 순번**이며 rounds[].cohortRoundNo와 같은 값입니다(12차 R1).",
					example = "4")
			int cohortRoundNo,
			@Schema(description = "NOT_STARTED(시작 전) / NOT_AGGREGATED(리포트 미발행) / AGGREGATED(발행 완료)")
			RoundAggregationStatus aggregationStatus,
			@Schema(description = "미집계 인원을 제외한 분모입니다.", example = "24")
			long eligibleCount,
			@Schema(description = """
					분자입니다. 위험 유형(단계 하락·지속 저점)이 확정된 고유 교육생 수이며,
					**1차 회차에서는 관찰(observedRiskCount)도 함께 셉니다**.
					1차는 비교할 직전 회차가 없어 두 유형이 모두 `관찰`로만 등재되기 때문입니다.
					2차 이후 열은 확정된 위험만 셉니다.
					""", example = "6")
			long riskCount,
			@Schema(description = """
					riskCount 중 관찰로 잡힌 인원이며 riskCount의 부분집합입니다. 1차 회차가 아니면 항상 0입니다.
					1차 열의 값을 2차 이후 열과 그대로 이어 추이로 읽으면 안 된다는 신호로 쓰세요 —
					1차 분자는 `2단 미만 문제 1개 이상`이라는 더 넓은 기준입니다.
					""", example = "4")
			long observedRiskCount,
			@Schema(description = "riskCount / eligibleCount 비율이며 집계 전이거나 분모가 0이면 null입니다.", example = "0.2500",
					nullable = true)
			BigDecimal riskRate,
			@Schema(description = """
					같은 회차의 기준 비율과 견준 방향입니다. 반 행은 기수 전체 비율과, 팀 행은
					소속 반(classes[0]) 전체 비율과 비교합니다 — 팀 번호는 반 안에서만 유일해
					팀끼리는 소속 반 안에서만 비교가 성립하기 때문입니다.
					BETTER(낮음) / SAME(같음) / WORSE(높음)이며 임계 구간 없이 단순 비교합니다.
					기수 전체 행이거나 두 비율 중 하나라도 없으면 null입니다.
					""", nullable = true)
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
					조회 범위의 모든 회차를 유형별로 합산한 미집계 인원입니다.
					화면의 '채점에서 빠진 사람' 열에 대응하며 EXCLUSION_COUNT 정렬의 기준값입니다.
					회차별 내역은 cells[].exclusion에서 봅니다.
					""")
			ExclusionBreakdown exclusionRollup,
			@Schema(description = "기수 전체의 회차별 위험 비율 칸 목록이며 rounds와 같은 순서·길이입니다.")
			List<RiskCell> cells
	) {
	}

	@Schema(description = "반 행")
	public record ClassRiskSummary(
			@Schema(description = "반 ID이며 \"class\".class_id입니다.")
			UUID classId,
			@Schema(description = "반 이름", example = "C반")
			String className,
			@Schema(description = "중도 이탈하지 않은 현재 반 교육생 수", example = "25")
			long traineeCount,
			@Schema(description = "중도 이탈한 교육생 수", example = "1")
			long withdrawnCount,
			@Schema(description = "조회 범위의 모든 회차를 유형별로 합산한 미집계 인원입니다.")
			ExclusionBreakdown exclusionRollup,
			@Schema(description = """
					활성 담당 매니저 이름 목록입니다. 한 반에 여러 명이 배정될 수 있습니다.
					비어 있으면 화면의 '담당 없음'이며 대시보드의 미배정 경보와 같은 조건입니다.
					""")
			List<String> managerNames,
			@Schema(description = "이 반의 회차별 위험 비율 칸 목록이며 rounds와 같은 순서·길이입니다.")
			List<RiskCell> cells
	) {
	}

	@Schema(description = """
			팀 행.

			팀 번호는 반 안에서만 유일하고 team은 project_id에 종속이라 이 목록은 한 프로젝트·한 반으로
			좁혔을 때만 나옵니다. 팀당 인원이 4~5명이라 비율이 0%·25%·50% 같은 거친 값이 됩니다.
			""")
	public record TeamRiskSummary(
			@Schema(description = "팀 ID이며 team.team_id입니다.")
			UUID teamId,
			@Schema(description = "반 안에서의 팀 번호", example = "3")
			String teamNumber,
			@Schema(description = "팀 이름", example = "3팀")
			String teamName,
			@Schema(description = "이 팀이 속한 반 ID입니다.")
			UUID classId,
			@Schema(description = "이 팀이 속한 반 이름", example = "C반")
			String className,
			@Schema(description = "현재 팀에 속한 인원", example = "5")
			long memberCount,
			@Schema(description = "조회 범위의 모든 회차를 유형별로 합산한 미집계 인원입니다.")
			ExclusionBreakdown exclusionRollup,
			@Schema(description = "이 팀의 회차별 위험 비율 칸 목록이며 rounds와 같은 순서·길이입니다.")
			List<RiskCell> cells
	) {
	}
}
