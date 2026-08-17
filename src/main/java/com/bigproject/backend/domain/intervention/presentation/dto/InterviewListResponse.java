package com.bigproject.backend.domain.intervention.presentation.dto;

import com.bigproject.backend.domain.intervention.application.InterviewService;
import com.bigproject.backend.domain.intervention.application.InterviewService.InterviewCaseView;
import com.bigproject.backend.domain.intervention.application.InterviewService.InterviewListResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MG-03 면담 목록 응답. */
@Schema(description = "면담 목록 조회 결과")
public record InterviewListResponse(

		@Schema(description = "면담 케이스 목록. 정렬은 서버가 정하며 클라이언트가 바꿀 수 없다")
		List<InterviewCaseResponse> items,

		@Schema(description = "필터가 적용된 결과 건수", example = "9")
		int total,

		@Schema(description = "상태별 개수. **필터와 무관한 회차 전체 기준**이다",
				example = "{\"PLANNED\": 5, \"DONE\": 4, \"EXCLUDED\": 0}")
		Map<String, Long> counts,

		@Schema(description = "위험 유형별 개수. **필터와 무관한 회차 전체 기준**이다",
				example = "{\"INVALID\": 1, \"LOW_PERSISTENT\": 3, \"DECLINE\": 5, \"OBSERVE\": 0}")
		Map<String, Long> riskCounts,

		@Schema(description = """
				**반 필터 드롭다운** 재료. 이 매니저의 담당 반 전부입니다.

				상태·위험 유형과 달리 값 집합을 고정할 수 없어 서버가 줍니다 — 매니저마다 담당이 다릅니다.
				`counts`처럼 **필터와 무관한 전체 목록**입니다: `items[]`의 `className`으로 유도하면
				반 필터를 걸었을 때 나머지 반이 드롭다운에서 사라집니다.
				""")
		List<ClassOptionResponse> classes,

		// 29차 R2 ② — 담당 밖 회차 ID면 null인데 타입이 그것을 말하지 않고 있었다.
		@Schema(description = "조회한 회차의 표시용 메타. 담당 밖 회차 ID를 넣으면 null", nullable = true)
		RoundResponse round) {

	public static InterviewListResponse from(InterviewListResult result) {
		return new InterviewListResponse(
				result.items().stream().map(InterviewCaseResponse::from).toList(),
				result.total(),
				result.counts(),
				result.riskCounts(),
				result.classes().stream()
						.map(option -> new ClassOptionResponse(option.classId(), option.className()))
						.toList(),
				result.round() == null ? null : RoundResponse.from(result.round()));
	}

	@Schema(description = "담당 반")
	public record ClassOptionResponse(
			UUID classId,

			@Schema(description = "반 이름", example = "A반")
			String className) {
	}

	@Schema(description = "회차 메타")
	public record RoundResponse(

			UUID assessmentRoundId,

			@Schema(description = "드롭다운·헤더 문구. 회차 번호가 프로젝트 안에서만 유일해 프로젝트명을 함께 붙인다",
					example = "미니프로젝트 3차")
			String label,

			@Schema(description = """
					**위험 판정이 끝났는가.** `PENDING`이면 화면이 **"이 회차는 아직 결과가 없어요"** 를
					그린다 — 그때는 `items`도 비어 있다.

					🔴 **32차 R1 — 기준이 리포트 발행에서 위험 판정으로 바뀌었다.**
					종전에는 `publishedAt`으로 판정해서, 운영자가 발행을 미루면 **판정은 끝났는데
					`PENDING`**이 나갔다. 그 상태에서 위험 유형이 붙은 `items`가 함께 나가
					한 응답이 서로 다른 말을 했다.

					두 축은 의도적으로 독립이다 — 판정은 「마지막 응시 마감 + 1시간 1분」에 돌고
					발행 시각은 운영자가 정한다. 지금은 `items`가 있는데 `PENDING`인 조합이
					**구조적으로 나올 수 없다**(후보 등재 조건이 곧 판정 완료다).

					발행 쪽 값은 `publishedAt`·`daysSincePublish`가 그대로 답한다.
					""", example = "READY")
			String resultStatus,

			@Schema(description = "1차인가. true면 **위험 유형이 붙지 않는다** — 비교할 직전 회차가 없다(9-5)")
			boolean firstRound,

			@Schema(description = """
					리포트 발행 시각. **발행 전이면 `null`이다.**

					⚠️ **위험 판정 등재 시각이 아니다**(32차 R1에서 정정). 판정은 「마지막 응시 마감 +
					1시간 1분」에 돌고 발행은 운영자가 정하는 별개 시점이라, 발행이 미뤄지면 이 값만
					비어 있고 판정은 이미 끝나 있다. 판정 여부는 `resultStatus`가 답한다.
					""", nullable = true)
			Instant publishedAt,

			@Schema(description = """
					발행 후 경과일. 상단 경고줄(`N일째 안 끝났습니다`)에 쓴다. **발행 전이면 `null`이며**
					그때는 그 경고줄을 그리지 않는다(0으로 두면 「0일째」가 된다).

					**대기는 개인별이 아니라 회차 경과다** — 리포트가 일괄 발행되므로 회차 안에서 모두 같은 값이다.
					""", example = "6", nullable = true)
			Integer daysSincePublish) {

		public static RoundResponse from(InterviewService.RoundView view) {
			return new RoundResponse(
					view.assessmentRoundId(),
					view.label(),
					view.resultStatus(),
					view.firstRound(),
					view.publishedAt(),
					view.daysSincePublish());
		}
	}

	@Schema(description = "면담 케이스 한 건")
	public record InterviewCaseResponse(

			@Schema(description = "케이스 ID(`interview_candidate.candidate_id`). 브리프·제외·무효 확인이 모두 이 값을 쓴다")
			UUID caseId,

			@Schema(description = "교육생 ID. 이름 클릭 시 MG-06 상세로 간다")
			UUID traineeId,

			@Schema(description = "교육생 이름", example = "김민준")
			String name,

			UUID classId,

			@Schema(description = "반 이름", example = "A반")
			String className,

			@Schema(description = """
					면담 진행 상태.

					| 값 | 뜻 |
					|---|---|
					| `PLANNED` | 등재됐고 아직 안 만남 |
					| `DONE` | 만났다(면담 종결) |
					| `EXCLUDED` | 매니저가 이번 회차 대상에서 뺐다. 되돌릴 수 있다 |

					`진행 중`은 없다 — 면담하는 30분 동안만 존재하고 그동안 매니저는 화면을 안 본다.
					""", example = "PLANNED")
			String status,

			@Schema(description = """
					위험 유형.

					| 값 | 뜻 |
					|---|---|
					| `INVALID` | 무효 응시. 판정 전이라 **목록 맨 위에 온다** |
					| `LOW_PERSISTENT` | 지속 저점 |
					| `DECLINE` | 단계 하락 |
					| `OBSERVE` | 관찰. 1차라 비교할 직전 회차가 없어 유형이 붙지 않는다 |
					""", example = "DECLINE")
			String riskType,

			@Schema(description = """
					판정 근거 문구. 서버가 만든 문장을 그대로 표시한다.
					**대괄호 태그(`[SEVERE]` 등)는 붙지 않는다**(30차 R8).
					""", example = "평균 도달 단계 2.33 → 1.67. 2단 미만 2개.")
			String riskSummary,

			@Schema(description = """
					브리프 상태. **버튼 문구가 여기서 갈린다.**

					| 값 | 버튼 | 동작 |
					|---|---|---|
					| `NONE` | 브리프 생성 | `POST .../brief` — AI 생성, **20~30초**(32차 R7 실측 24초) |
					| `FAILED` | 다시 생성 | `POST .../brief` 재시도 |
					| `DRAFT` | 브리프 열기 | `GET .../brief` — 즉시 |
					| `CONFIRMED` | 브리프 수정 | `GET .../brief` — 즉시 |
					""", example = "NONE")
			String briefState,

			@Schema(description = """
					무효 확인 API(`PATCH /assessment-attempts/{attemptId}/validity`) 호출에 쓸 수행 ID.
					화면은 이 값을 따로 조회할 경로가 없어 목록이 실어 보낸다.
					""")
			UUID attemptId,

			@Schema(description = "무효 확인을 `그대로 두기`로 마쳤는가. true면 판정 근거가 `확인 완료 · 그대로 유지`로 바뀐다")
			boolean voidConfirmed,

			@Schema(description = """
					무효 확인이 아직 안 끝났는가. true면 `[브리프 열기]` 대신 **`[무효 확인]`을 먼저 보여준다** —
					브리프를 먼저 만들면 briefType(STANDARD/INVALID_ATTEMPT)을 정할 수 없다.
					""")
			boolean voidReviewPending,

			@Schema(description = "제외 시각. `EXCLUDED`일 때만")
			Instant excludedAt,

			@Schema(description = "제외한 매니저 이름. `EXCLUDED`일 때만", example = "박지현")
			String excludedBy,

			@Schema(description = "면담 종결 시각. `DONE`일 때만")
			Instant interviewedAt,

			// 29차 R2 ② — 지난 면담이 없거나 그때 계획을 안 적었으면 null이다.
			@Schema(description = "지난 면담에서 정한 `다음에 할 것`. 없으면 null",
					example = "담당 기능 흐름 그려오기", nullable = true)
			String nextAction) {

		public static InterviewCaseResponse from(InterviewCaseView view) {
			return new InterviewCaseResponse(
					view.caseId(),
					view.traineeId(),
					view.name(),
					view.classId(),
					view.className(),
					view.status(),
					view.riskType(),
					view.riskSummary(),
					view.briefState(),
					view.attemptId(),
					view.voidConfirmed(),
					view.voidReviewPending(),
					view.excludedAt(),
					view.excludedBy(),
					view.interviewedAt(),
					view.nextAction());
		}
	}
}
