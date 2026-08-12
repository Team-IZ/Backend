package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SlotState;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 문제 하나를 화면에 그리는 데 필요한 전부 — 왼쪽 코드 패널과 오른쪽 대화가 이 한 번의 조회로 채워진다.
 *
 * <h2>점수를 내려보내지 않는다</h2>
 *
 * <p>정의서 §7 — "세션 중에는 아무 판정도 안 보여준다". 통과 여부·점수는 응답에 넣지 않는다. 넣어 두고
 * 화면이 안 그리는 방식은 개발자 도구로 바로 보이고, 그 순간 학생은 다음 답을 점수에 맞춰 쓴다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "문제 하나의 코드·질문·지금까지의 문답")
public record ProblemActivityResponse(
		@Schema(description = "문제 번호(1~3)") int problemNo,
		@Schema(description = "생성된 문제 수. 화면의 `문제 n/N`") int problemTotal,
		@Schema(description = "문제 제목. 검증하는 교안 개념 이름이다") String title,
		@Schema(description = "코드 패널") Code code,
		@Schema(description = "이 문제에서 지금까지 확정된 문답. 화면은 위에서 아래로 쌓는다")
		List<Turn> turns,
		@Schema(description = "지금 물어보는 질문. 문제가 끝났으면 null", nullable = true)
		CurrentQuestion current
) {

	@Schema(description = "코드 패널. snippet은 파일 전체이며 자를 위치는 화면이 정한다")
	public record Code(
			String path,
			String language,
			@Schema(description = "문제를 낸 파일 전체") String snippet,
			@Schema(description = "강조할 구간 시작(파일 기준 절대 줄 번호)") int lineStart,
			@Schema(description = "강조할 구간 끝") int lineEnd,
			@Schema(description = "호출부·관련 문맥. 화면은 접어 두고 필요할 때 편다") List<Reference> references
	) {
	}

	@Schema(description = "코드 근거 하나")
	public record Reference(
			@Schema(description = "PRIMARY_BLOCK · QUESTION_HIGHLIGHT · CALLER · RELATED_CONTEXT · CURRICULUM_EVIDENCE")
			String type,
			String path,
			Integer lineStart,
			Integer lineEnd,
			@Schema(description = "이 근거가 붙는 축. QUESTION_HIGHLIGHT에서만 채워진다") String axisCode
	) {
	}

	/** 확정된 문답 하나. 힌트 후 재질의도 한 턴이다. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Turn(
			@Schema(description = "질문 순번. 화면의 `◆ 질문 2`") int sequenceNo,
			String questionText,
			@Schema(description = "이 턴 직전에 보여준 힌트. 첫 시도면 null", nullable = true) String hintText,
			String answerText,
			Instant answeredAt,
			@Schema(description = "강조할 구간. 질문마다 옮겨간다") Highlight highlight
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CurrentQuestion(
			int sequenceNo,
			String questionText,
			@Schema(description = "이미 연 힌트 문구. 없으면 비어 있다") List<String> shownHints,
			@Schema(description = "지금까지 쓴 힌트 수(0~2)") int hintsUsed,
			@Schema(description = "남은 힌트 수. 다시 보기는 항상 0이다") int hintsLeft,
			@Schema(description = "강조할 구간") Highlight highlight,
			@Schema(description = "이 답변이 세션의 마지막인지. 버튼 문구가 `답변 제출하고 마치기`로 바뀐다")
			boolean lastTurnOfSession
	) {
	}

	public record Highlight(String path, Integer lineStart, Integer lineEnd) {
	}

	public static ProblemActivityResponse of(SessionHead head, SessionProblem problem, int problemTotal) {
		List<Turn> turns = new ArrayList<>();
		CurrentQuestion current = null;

		for (SessionStage stage : problem.stages()) {
			addTurn(turns, stage, AnswerSlot.QUESTION, null, problem);
			addTurn(turns, stage, AnswerSlot.FIRST_HINT, stage.firstHintText(), problem);
			addTurn(turns, stage, AnswerSlot.SECOND_HINT, stage.secondHintText(), problem);

			if (current == null && stage.problemStageId().equals(head.currentProblemStageId())) {
				current = toCurrent(head, problem, stage);
			}
		}
		return new ProblemActivityResponse(problem.problemNo(), problemTotal, problem.title(),
				toCode(problem), turns, current);
	}

	/**
	 * 확정된 턴 하나. 한 축에서 최대 셋 나온다 — 미달이면 힌트가 열리고 <b>같은 질문에 다시 답하기</b>
	 * 때문이다. {@code hintText}는 그 답 직전에 보여 준 힌트이며, 첫 시도면 {@code null}이다.
	 */
	private static void addTurn(List<Turn> turns, SessionStage stage, AnswerSlot slot, String hintText,
			SessionProblem problem) {
		SlotState state = stage.slot(slot);
		if (!state.isAnswered()) {
			return;
		}
		turns.add(new Turn(stage.questionSequenceNo(), stage.questionText(), hintText,
				state.answerText(), state.answeredAt(), highlight(problem, stage)));
	}

	private static CurrentQuestion toCurrent(SessionHead head, SessionProblem problem, SessionStage stage) {
		int hintsUsed = stage.hintsUsed();
		List<String> shown = new ArrayList<>();
		if (hintsUsed >= 1) {
			shown.add(stage.firstHintText());
		}
		if (hintsUsed >= 2) {
			shown.add(stage.secondHintText());
		}
		// 다시 보기는 힌트가 없다. 화면은 버튼 자리에 이유 문구를 그리므로 남은 수를 0으로 내려보낸다.
		int hintsLeft = head.isReview() ? 0 : 2 - hintsUsed;
		boolean lastTurn = isLastStageOfSession(problem, stage);
		return new CurrentQuestion(stage.questionSequenceNo(), stage.questionText(), shown, hintsUsed,
				hintsLeft, highlight(problem, stage), lastTurn);
	}

	/**
	 * 마지막 턴 판정은 <b>이 문제 안에서만</b> 본다. 다음 문제가 남았는지는 세션이 알지만, 계단이 어디서
	 * 끊길지는 채점 결과에 달려 있어 미리 알 수 없다 — 마지막 문제의 마지막 축이면 참으로 본다.
	 */
	private static boolean isLastStageOfSession(SessionProblem problem, SessionStage stage) {
		SessionStage last = problem.stages().get(problem.stages().size() - 1);
		return last.problemStageId().equals(stage.problemStageId());
	}

	private static Highlight highlight(SessionProblem problem, SessionStage stage) {
		return highlightOf(problem, stage.axisCode());
	}

	/**
	 * 축 하나가 가리키는 줄. 축별 하이라이트가 있으면 그것을 쓰고 없으면 문제의 대표 구간을 쓴다.
	 *
	 * <p>질문은 축마다 다른 줄을 가리킨다 — 화면 목업의 `질문 1 → 5–8`, `질문 2 → 39–41`이 그것이다.
	 * 연결 고리는 {@code axis_code} 하나뿐이라({@code assessment_problem_reference}에 단계 FK가 없다)
	 * 축을 받아 푸는 이 자리가 유일한 해석 지점이다.
	 *
	 * <p>{@link AnswerSubmitResponse}도 같은 규칙으로 <b>다음</b> 질문의 구간을 풀어야 해서 공개한다 —
	 * 두 벌로 두면 한쪽만 고쳐져 같은 질문이 화면마다 다른 줄을 가리키게 된다.
	 */
	public static Highlight highlightOf(SessionProblem problem, String axisCode) {
		return problem.references().stream()
				.filter(reference -> "QUESTION_HIGHLIGHT".equals(reference.referenceType())
						&& axisCode != null && axisCode.equals(reference.axisCode()))
				.findFirst()
				.map(reference -> new Highlight(reference.sourcePath(), reference.lineStart(), reference.lineEnd()))
				.orElseGet(() -> new Highlight(problem.sourcePath(), problem.lineStart(), problem.lineEnd()));
	}

	private static Code toCode(SessionProblem problem) {
		List<Reference> references = problem.references().stream()
				// 대표 블록과 하이라이트는 code·highlight로 이미 나가므로 패널 목록에서는 뺀다.
				.filter(reference -> !"PRIMARY_BLOCK".equals(reference.referenceType())
						&& !"QUESTION_HIGHLIGHT".equals(reference.referenceType()))
				.map(reference -> new Reference(reference.referenceType(), reference.sourcePath(),
						reference.lineStart(), reference.lineEnd(), reference.axisCode()))
				.toList();
		return new Code(problem.sourcePath(), problem.codeLanguage(), problem.codeSnippet(),
				problem.lineStart(), problem.lineEnd(), references);
	}

}
