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

		@Schema(description = "조회한 회차의 표시용 메타. 담당 밖 회차 ID를 넣으면 null")
		RoundResponse round) {

	public static InterviewListResponse from(InterviewListResult result) {
		return new InterviewListResponse(
				result.items().stream().map(InterviewCaseResponse::from).toList(),
				result.total(),
				result.counts(),
				result.riskCounts(),
				result.round() == null ? null : RoundResponse.from(result.round()));
	}

	@Schema(description = "회차 메타")
	public record RoundResponse(

			UUID assessmentRoundId,

			@Schema(description = "드롭다운·헤더 문구. 회차 번호가 프로젝트 안에서만 유일해 프로젝트명을 함께 붙인다",
					example = "미니프로젝트 3차")
			String label,

			@Schema(description = """
					`PENDING`이면 화면이 **"이 회차는 아직 결과가 없어요"** 를 그린다.
					리포트 발행 전이라 위험 판정 자체가 없는 상태다.
					""", example = "READY")
			String resultStatus,

			@Schema(description = "1차인가. true면 **위험 유형이 붙지 않는다** — 비교할 직전 회차가 없다(9-5)")
			boolean firstRound,

			@Schema(description = "리포트 발행 시각. **위험 판정 등재 시각이기도 하다**")
			Instant publishedAt,

			@Schema(description = """
					발행 후 경과일. 상단 경고줄(`N일째 안 끝났습니다`)에 쓴다.
					**대기는 개인별이 아니라 회차 경과다** — 리포트가 일괄 발행되므로 회차 안에서 모두 같은 값이다.
					""", example = "6")
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

			@Schema(description = "판정 근거 문구. 서버가 만든 문장을 그대로 표시한다",
					example = "2단 이하 1 → 2")
			String riskSummary,

			@Schema(description = """
					브리프 상태. **버튼 문구가 여기서 갈린다.**

					| 값 | 버튼 | 동작 |
					|---|---|---|
					| `NONE` | 브리프 생성 | `POST .../brief` — AI 생성, 수 초 대기 |
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

			@Schema(description = "지난 면담에서 정한 `다음에 할 것`. 없으면 null",
					example = "담당 기능 흐름 그려오기")
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
