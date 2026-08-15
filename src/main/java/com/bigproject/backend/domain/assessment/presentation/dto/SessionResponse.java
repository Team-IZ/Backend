package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.domain.AssessmentSessionStatus;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 세션 머리 정보. 화면이 전체화면을 열기 전에 알아야 하는 것만 담는다 — 문제 본문은
 * {@code GET .../problems/{problemNo}}가 준다.
 *
 * <p>시각은 전부 ISO-8601 절대 시각({@code ...Z})이다. <b>KST 변환은 화면이 한다</b> — 표시용 문자열을
 * 서버가 병기하면 같은 값이 두 벌이 되어 언젠가 어긋난다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "검증 세션 진행 상태")
public record SessionResponse(
		@Schema(description = "세션 ID", requiredMode = Schema.RequiredMode.REQUIRED) UUID sessionId,

		@Schema(description = "FIRST(1차) · REVIEW(다시 보기). REVIEW는 힌트가 없고 판정에 반영되지 않는다",
				allowableValues = {"FIRST", "REVIEW"}, requiredMode = Schema.RequiredMode.REQUIRED)
		String mode,

		// 이 조회는 READY·IN_PROGRESS만 돌려주지만(종료된 세션은 findCurrent가 고르지 않는다)
		// 값 집합 자체는 세션 상태 8종이다. 좁혀서 내보내면 같은 컬럼이 API마다 다른 타입이 된다.
		@Schema(description = "이 조회는 사실상 READY(시작 전) · IN_PROGRESS(진행 중)만 돌려준다",
				implementation = AssessmentSessionStatus.class,
				requiredMode = Schema.RequiredMode.REQUIRED)
		String status,

		// 시작 전에는 서 있는 문제가 없다. NON_NULL이라 그때는 키가 빠진다.
		@Schema(description = """
				지금 서 있는 문제 번호. 시작 전이면 이 키가 없다. 생성된 문제만 1부터 세므로 항상
				1~problemTotal 범위이며, 그대로 `GET .../problems/{problemNo}`에 넣으면 된다""")
		Integer currentProblemNo,

		@Schema(description = """
				생성된 문제 수. 화면의 `문제 n/N`의 N이다. 코드 근거를 못 찾아 문항이 만들어지지 않은
				개념(NOT_GENERATED)이 있으면 3보다 작다 — 그 문제는 세션에 아예 나오지 않는다""",
				requiredMode = Schema.RequiredMode.REQUIRED)
		int problemTotal,

		@Schema(description = "세션 시작 시각. 경과 시간 표시의 기산점",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Instant startedAt,

		@Schema(description = "정책 시간 상한. 넘기면 답한 데까지 저장하고 닫는다",
				requiredMode = Schema.RequiredMode.REQUIRED)
		Instant timeLimitAt,

		// FIRST 모드에는 다시 보기 마감이 없다. NON_NULL이라 그때는 키가 빠진다.
		@Schema(description = "다시 보기 마감. REVIEW에서만 있다(FIRST면 이 키가 없다)") Instant reviewDueAt
) {

	public static SessionResponse of(SessionHead head, List<SessionStage> stages) {
		Integer currentProblemNo = stages.stream()
				.filter(stage -> stage.problemStageId().equals(head.currentProblemStageId()))
				.map(SessionStage::problemNo)
				.findFirst()
				.orElse(null);
		int problemTotal = (int) stages.stream().map(SessionStage::problemId).distinct().count();
		return new SessionResponse(
				head.sessionId(),
				head.isReview() ? "REVIEW" : "FIRST",
				head.status(),
				currentProblemNo,
				problemTotal,
				head.startedAt(),
				head.timeLimitAt(),
				head.isReview() ? head.reviewDueAt() : null);
	}
}
