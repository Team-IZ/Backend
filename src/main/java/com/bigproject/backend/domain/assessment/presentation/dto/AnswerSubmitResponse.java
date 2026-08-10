package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.AnswerResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 답변 제출 결과 — <b>다음에 무엇을 그릴지</b>만 담는다.
 *
 * <h2>점수·통과 여부를 내려보내지 않는다</h2>
 *
 * <p>정의서 §7 "세션 중에는 아무 판정도 안 보여준다". 화면이 안 그려도 응답에 있으면 개발자 도구로
 * 보이고, 그 순간 학생은 다음 답을 점수에 맞춰 쓴다. 판정은 리포트에서 나온다.
 *
 * <p>대신 {@code outcome}으로 <b>다음 화면</b>만 알려준다. 계단이 어디서 끊겼는지는 이 값에 드러나지만
 * 그것은 학생이 화면에서 이미 보는 사실이다(다음 질문이 오는가 / 문제가 닫히는가).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "답변 제출 결과")
public record AnswerSubmitResponse(
		@Schema(description = """
				NEXT_TURN(같은 문제의 다음 질문) · NEXT_PROBLEM(다음 문제로) ·
				PROBLEM_CLOSED(이 문제는 여기까지) · SESSION_ENDED(세션 종료)""",
				allowableValues = {"NEXT_TURN", "NEXT_PROBLEM", "PROBLEM_CLOSED", "SESSION_ENDED"})
		String outcome,

		@Schema(description = "다음에 설 문제 번호. 세션이 끝났으면 null") Integer nextProblemNo,

		@Schema(description = "다음 질문. 세션이 끝났으면 null") NextQuestion next
) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record NextQuestion(
			UUID problemId,
			int sequenceNo,
			String questionText,
			@Schema(description = "지금까지 쓴 힌트 수") int hintsUsed
	) {
	}

	/**
	 * @param score  AI가 준 점수. <b>응답에 싣지 않는다</b> — 저장은 이미 끝났고 여기서는 흐름만 정한다
	 * @param passed 같은 이유로 싣지 않는다
	 */
	public static AnswerSubmitResponse of(AnswerResult result, int score, boolean passed) {
		if (result.cursor() == null || result.cursor().problemId() == null) {
			return new AnswerSubmitResponse("SESSION_ENDED", null, null);
		}

		boolean problemChanged = result.turn() != null
				&& !result.turn().problemId().equals(result.cursor().problemId());
		String outcome;
		if (problemChanged) {
			// 문제가 바뀌었다. 통과해서 넘어갔는지, 힌트를 다 쓰고 접혔는지를 terminationReason이 가른다 —
			// 화면 문구가 "다음 문제로"와 "이 문제는 여기까지"로 달라진다.
			outcome = result.terminationReason() != null && !result.terminationReason().startsWith("COMPLETED")
					? "PROBLEM_CLOSED"
					: "NEXT_PROBLEM";
		} else {
			outcome = "NEXT_TURN";
		}

		Integer nextProblemNo = result.progress() == null ? null : result.progress().problemIndex() + 1;
		NextQuestion next = result.current() == null ? null
				: new NextQuestion(result.current().problemId(),
						result.current().sequenceNo() == null ? 0 : result.current().sequenceNo(),
						result.current().questionText(),
						result.current().hintsUsed() == null ? 0 : result.current().hintsUsed());
		return new AnswerSubmitResponse(outcome, nextProblemNo, next);
	}
}
