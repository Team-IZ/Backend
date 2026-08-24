package com.bigproject.backend.domain.evaluation.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = """
		교육생 한 명의 채점 결과 상세. 결과 탭에서 사람을 클릭했을 때 오른쪽에 그리는 값이다.

		개념마다 도달 단계 한 칸과 축 4단계(코드이해 → 설계논리 → 대안비교 → 반례대응) 사다리가 있고,
		채점 근거는 그 안에 접혀 있다. **주고받은 대화 전문은 주지 않는다** — 매니저 화면의 계약이며
		학생 리포트에만 전문이 있다.
		""")
public record TraineeEvaluationDetailResponse(
		UUID projectId,
		UUID assessmentRoundId,
		int roundNo,
		UUID userId,
		String name,
		UUID classId,
		String className,
		@Schema(description = "회차 리포트 발행 여부. 발행 전에는 채점 근거(note)가 아직 없습니다.")
		boolean reportPublished,
		@Schema(description = """
				`AVAILABLE`(응시 완료) · `IN_PROGRESS`(응시 중) · `INCOMPLETE`(끝내지 못하고 창이 닫힘) ·
				`NOT_ATTENDED`(검증 세션을 하지 못함) · `INVALID`(무효 확정).

				🆕 `NOT_ATTENDED`는 **팀 미제출·코드 분석 실패까지 포함**합니다. 종전에는 그 둘이
				`IN_PROGRESS`로 와서 종료된 회차에 「응시 중」인 사람이 남았습니다.
				원인은 `notAttendedReason`으로 옵니다.

				🔴 **`AVAILABLE`이 아니면 `retryTarget`이 항상 false입니다** — 아직 풀지 않은 문제를
				2단 미달로 판정하면 응시 중인 사람이 전부 다시 보기 대상이 됩니다.
				""", example = "AVAILABLE")
		String resultStatus,
		@Schema(description = """
				검증 세션을 **왜** 하지 못했는지이며 `resultStatus`가 `NOT_ATTENDED`일 때만 값이 있습니다.

				`NO_SHOW`(볼 수 있었는데 안 봄 — 학생 책임) · `NOT_SUBMITTED`(팀 미제출로 문항 없음) ·
				`ANALYSIS_FAILED`(코드 분석 실패 — 시스템 귀책).

				🔴 뒤의 둘은 **볼 수 없었던** 경우라 학생 책임으로 읽으면 안 됩니다.
				""", example = "NOT_SUBMITTED", nullable = true)
		String notAttendedReason,
		@Schema(description = "개념별 결과이며 표시 순서 오름차순입니다.")
		List<Concept> concepts
) {

	// 30차 R2② — 이름을 명시하지 않으면 springdoc이 `Concept`으로 등록하고, 같은 이름을 쓰는
	// 히트맵의 열 머리(ManagerHeatmapResponse.Concept)와 부딪혀 <b>한쪽이 스펙에서 사라진다.</b>
	// 실제로 이 record가 이겨서, 히트맵을 붙이려던 화면이 있지도 않은 reachLevel·steps[]를
	// 반·팀 계층 응답에서 찾게 됐다.
	@Schema(name = "TraineeEvaluationConcept")
	public record Concept(
			UUID conceptId,
			String concept,
			int displayOrder,
			@Schema(description = "그 개념이 코드에 있어 문제가 만들어졌는지. false면 못한 것이 아니라 묻지 못한 것입니다.")
			boolean inCode,
			@Schema(description = "통과한 축 중 가장 높은 단계(0~4)", example = "2")
			int reachLevel,
			@Schema(description = "2단 미달이라 다시 보기 대상인 개념인지")
			boolean retryTarget,
			@Schema(description = """
					**실제로 물은 단계만** 옵니다. 앞 단계에서 멈추면 뒤 단계는 아예 배열에 없고,
					화면은 그 자리를 '미도달' 빈 칸으로 그립니다 — 0점이나 불합격과 구분해야 합니다.
					""")
			List<Step> steps
	) {
	}

	@Schema(name = "TraineeEvaluationStep", description = """
			축 한 단계의 판정.

			`passed`와 `helpCount`를 **따로** 읽어야 합니다. 힌트를 2회까지 받고도 통과할 수 있고,
			2회 받고도 기준을 못 넘으면 불합격입니다. 화면의 4범주(합격 · 합격(도움 1회) ·
			합격(도움 2회) · 불합격)는 이 둘을 조합해 만듭니다.
			""")
	public record Step(
			@Schema(description = "L1 · L2 · L3 · L4", example = "L2")
			String axisCode,
			@Schema(description = "축 순서 1~4. L1=코드이해, L2=설계논리, L3=대안비교, L4=반례대응입니다.", example = "2")
			int stepNo,
			@Schema(description = "이 단계를 통과했는가. `helpCount`와 **따로** 읽는다 — 힌트를 받고 통과한 경우도 true다")
			boolean passed,
			@Schema(description = "힌트를 받고 답한 횟수 0~2", example = "1")
			int helpCount,
			@Schema(description = "0~5점 원점수. 화면에는 노출하지 않는 내부 값이며 아직 채점되지 않았으면 null입니다.", nullable = true)
			Integer score,
			@Schema(description = "채점 근거 한 줄. 리포트 생성 전에는 null입니다.", nullable = true)
			String note
	) {
	}
}
