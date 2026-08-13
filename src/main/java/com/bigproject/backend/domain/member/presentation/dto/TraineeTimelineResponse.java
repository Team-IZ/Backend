package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "교육생 상세(MG-06)의 이력 — 회차마다 그 회차에서 일어난 사건을 묶어 낸다")
public record TraineeTimelineResponse(
		UUID cohortId, UUID traineeId,
		@Schema(description = """
				필터를 적용한 **전체 이벤트 수**이며 화면 상단의 `이벤트 8건`이다.
				페이지와 무관하다 — 한 페이지에 담긴 수가 아니다.
				""", example = "8")
		int totalElements,
		@Schema(description = """
				회차 묶음이며 **최신 차수부터**다. 회차 안의 `events`는 일어난 순서(오름차순)라
				화면이 위에서 아래로 그리는 순서와 같다.

				페이지 단위는 이벤트가 아니라 **회차**다 — 회차 중간이 잘리면 화면이 그 회차를
				반쪽만 그리기 때문이다.
				""")
		List<Round> rounds,
		@Schema(description = "다음 페이지 커서. 없으면 null", nullable = true) String nextCursor,
		boolean hasNext) {

	/** 화면의 이벤트 필터 탭과 1:1이다. `다시 보기`는 REVIEW·REVIEW_CLOSED 둘을 함께 켠다. */
	public enum Type { ASSESSMENT, REPORT, REVIEW, REVIEW_CLOSED, INTERVIEW }

	@Schema(name = "TraineeTimelineRound", description = "회차 묶음 — 머리글과 그 안의 사건들")
	public record Round(
			@Schema(description = "평가 회차 ID. 상세 조회의 `rounds[].assessmentRoundId`와 같은 값이라 위험 배지를 이 값으로 맞춘다")
			UUID assessmentRoundId,
			UUID projectId,
			@Schema(description = "**기수 안의 회차 순번.** 화면 머리글 `미프 3차`에서 3이 이 값", example = "3")
			Integer cohortRoundNo,
			@Schema(description = "회차 이름", example = "3차 이해도 확인") String roundName,
			@Schema(description = "프로젝트 이름", example = "미니프로젝트 3") String projectName,
			@Schema(description = "회차 당시 팀 ID. 팀은 프로젝트마다 재편성되므로 사람이 아니라 회차에 붙는다",
					nullable = true)
			UUID teamId,
			@Schema(description = "회차 당시 팀 이름. 머리글의 `1팀`", example = "1팀", nullable = true)
			String teamName,
			@Schema(description = """
					회차 활동 시작이며 머리글 `07.12 – 07.21`의 앞이다. **최초 응시(INITIAL)의 응시 창**만
					본다 — 다시 보기 마감까지 넣으면 회차 기간이 몇 달로 늘어난다.
					""", nullable = true)
			OffsetDateTime startAt,
			@Schema(description = "회차 활동 종료이며 머리글의 뒤다", nullable = true)
			OffsetDateTime endAt,
			@Schema(description = "이 회차에서 일어난 사건들. **발생 시각 오름차순**") List<Event> events
	) {
	}

	@Schema(name = "TraineeTimelineEvent", description = "사건 한 건. 유형에 따라 채워지는 칸이 다르다")
	public record Event(
			UUID eventId,
			@Schema(description = """
					`ASSESSMENT`(이해도 확인) · `REPORT`(리포트 발행) · `REVIEW`(다시 보기) ·
					`REVIEW_CLOSED`(다시 보기 창 마감) · `INTERVIEW`(면담).

					유형마다 아래 블록 중 하나만 채워진다 — 나머지는 `null`이거나 빈 배열이다.
					""", example = "ASSESSMENT")
			String type,
			@Schema(description = "일어난 시각. 화면 왼쪽의 `07.12`") OffsetDateTime occurredAt,
			String sourceEntityType, UUID sourceEntityId,
			@Schema(description = "원천 상태(수행 상태·리포트 수명주기·면담 상태)", nullable = true)
			String sourceStatus,
			@Schema(description = """
					검증 세션 ID. `자세히`가 `GET /assessment-sessions/{sessionId}/problems/{problemNo}`를
					부를 때 쓴다. 세션이 없으면 null이며 그때는 `expandable`도 false다.
					""", nullable = true)
			UUID sessionId,
			@Schema(description = "`자세히`를 켤지 여부. 세션이 없는 이해도 확인은 false다")
			boolean expandable,
			@Schema(description = "`OPEN_ASSESSMENT` · `OPEN_REPORT` · `OPEN_INTERVIEW`", nullable = true)
			String detailActionCode,
			@Schema(description = "집계 상태. `COMPLETE` · `PARTIAL` · `FULL`", nullable = true)
			String aggregationStatus,

			@Schema(description = """
					**ASSESSMENT 전용.** 문항별 결과이며 **문항 번호 오름차순**이다. 화면의
					`0단 · 1단 · 3단`과 `재진술 2회`가 이 배열에서 나온다.
					문항이 만들어지지 않은 개념도 번호를 지켜 남는다 — 빼면 격자 칸이 밀린다.
					""")
			List<AssessmentProblem> problems,

			@Schema(description = """
					**REPORT 전용.** 다시 보기로 지정된 문항 수이며 화면의 `다시 보기 2건 지정`이다.
					기준은 그 회차에서 도달 **2단 미만**(0~1단)인 문항 수다 — 정책상 재시험 대상이며
					명단(MG-05)의 `2단 이하` 분자와 같은 산식이다. 2단은 게이트 밖이라 세지 않는다.
					""", example = "2", nullable = true)
			Integer reviewTargetCount,
			@Schema(description = "**REPORT 전용.** 리포트 요약 한 줄", nullable = true)
			String reportSummary,

			@Schema(description = "**REVIEW·REVIEW_CLOSED 전용.** 다시 보기 창 마감 시각", nullable = true)
			OffsetDateTime reviewDueAt,
			@Schema(description = """
					**REVIEW 전용.** 다시 보기로 답한 문항의 도달 단계 변화이며 화면의
					`HITL Trigger 0단 → 1단`이 이 배열의 한 항목이다. 답한 문항이 하나도 없으면
					`REVIEW` 사건 자체가 생기지 않는다.
					""")
			List<ReviewChange> reviewChanges,
			@Schema(description = """
					**REVIEW_CLOSED 전용.** 창이 닫힐 때까지 **답하지 않은** 문항이며 화면의
					`Graph 구성 미응시`가 이 배열의 한 항목이다. 창이 아직 열려 있으면
					`REVIEW_CLOSED` 사건이 생기지 않는다 — 마감돼야 미응시가 확정된다.
					""")
			List<MissedConcept> missedConcepts,

			@Schema(description = "**INTERVIEW 전용.** 면담 기록", nullable = true)
			Interview interview
	) {
	}

	@Schema(name = "TraineeTimelineAssessmentProblem", description = "이해도 확인의 문항 하나")
	public record AssessmentProblem(
			@Schema(description = "문항 번호(1~3)", example = "1") int problemNo,
			UUID problemId,
			@Schema(description = "검증 개념 ID. 개인 기여 문항은 null", nullable = true) UUID conceptId,
			@Schema(description = "개념 이름", example = "HITL Trigger") String conceptName,
			@Schema(description = "`GENERATED` · `NOT_GENERATED`(코드에 근거가 없어 못 물었다)",
					example = "GENERATED")
			String generationStatus,
			@Schema(description = """
					최고 성공 도달 단계 0~4단. **null은 0단이 아니다** — 문항이 없거나
					한 축도 답하지 않은 경우이며 화면은 `▨ 문항 없음`/`—`를 그린다.
					""", example = "3", nullable = true)
			Integer reachLevel,
			@Schema(description = """
					되짚어 물은 횟수 0~2회이며 화면의 `자력`(0) · `재진술 1회` · `재진술 2회`다.
					한 축에 힌트가 최대 2개라 **축별 최댓값**을 쓴다 — 축을 가로질러 더하면 3 이상이 나온다.
					도달 단계가 null이면 이 값도 null이다.
					""", example = "2", nullable = true)
			Integer hintUsedCount
	) {
	}

	@Schema(name = "TraineeTimelineReviewChange", description = "다시 보기로 바뀐 문항 하나")
	public record ReviewChange(
			@Schema(description = "문항 번호", example = "3") int problemNo,
			UUID problemId,
			@Schema(description = "검증 개념 ID", nullable = true) UUID conceptId,
			@Schema(description = "개념 이름", example = "HITL Trigger") String conceptName,
			@Schema(description = "다시 보기 **전** 도달 단계", example = "0", nullable = true)
			Integer fromReachLevel,
			@Schema(description = "다시 보기 **후** 도달 단계", example = "1", nullable = true)
			Integer toReachLevel,
			@Schema(description = "도달이 올랐는지. false면 다시 봤지만 그대로다") boolean improved
	) {
	}

	@Schema(name = "TraineeTimelineMissedConcept", description = "다시 보기 창이 닫힐 때까지 안 푼 문항")
	public record MissedConcept(
			@Schema(description = "문항 번호", example = "2") int problemNo,
			UUID problemId,
			@Schema(description = "검증 개념 ID", nullable = true) UUID conceptId,
			@Schema(description = "개념 이름", example = "Graph 구성") String conceptName
	) {
	}

	@Schema(name = "TraineeTimelineInterview", description = "면담 기록")
	public record Interview(
			@Schema(description = "면담 상태", nullable = true) String recordStatus,
			@Schema(description = "무엇 때문인지. 화면 요약의 앞부분", nullable = true)
			String identifiedCause,
			@Schema(description = "매니저 조치 메모. 화면 요약의 뒷부분(`→ 다시 보기 창 안내`)", nullable = true)
			String managerNote,
			@Schema(description = "다음에 할 것", nullable = true) String nextAction,
			@Schema(description = """
					약속이 확인된 시각이며 **null이면 화면이 `⚠ 약속 미확인`을 띄운다**.
					확인 처리 액션은 없다 — 정의서 §3이 「다음 면담이 열리면 해소된다」고 규정하므로
					같은 교육생의 다음 면담이 시작되면 채워진다.
					""", nullable = true)
			OffsetDateTime nextActionConfirmedAt,
			@Schema(description = "매니저가 브리프에서 골라 실제로 물은 질문들. 고른 것이 없으면 빈 배열")
			List<ManagerAction> managerActions
	) {
	}

	@Schema(name = "TraineeTimelineManagerAction", description = "면담 브리프에서 매니저가 고른 질문")
	public record ManagerAction(
			@Schema(description = "표시 순서", nullable = true) Integer displayOrder,
			@Schema(description = "질문") String question,
			@Schema(description = "그 질문을 고른 근거", nullable = true) String rationale
	) {
	}
}
