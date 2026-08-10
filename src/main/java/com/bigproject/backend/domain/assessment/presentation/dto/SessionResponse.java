package com.bigproject.backend.domain.assessment.presentation.dto;

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
		@Schema(description = "세션 ID") UUID sessionId,

		@Schema(description = "FIRST(1차) · REVIEW(다시 보기). REVIEW는 힌트가 없고 판정에 반영되지 않는다",
				allowableValues = {"FIRST", "REVIEW"})
		String mode,

		@Schema(description = "READY(시작 전) · IN_PROGRESS(진행 중)", allowableValues = {"READY", "IN_PROGRESS"})
		String status,

		@Schema(description = "지금 서 있는 문제 번호(1~3). 시작 전이면 null") Integer currentProblemNo,

		@Schema(description = "생성된 문제 수. 화면의 `문제 n/N`의 N이다. NOT_GENERATED 문제가 있으면 3보다 작다")
		int problemTotal,

		@Schema(description = "세션 시작 시각. 경과 시간 표시의 기산점") Instant startedAt,

		@Schema(description = "정책 시간 상한. 넘기면 답한 데까지 저장하고 닫는다") Instant timeLimitAt,

		@Schema(description = "다시 보기 마감. REVIEW에서만 있다") Instant reviewDueAt
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
