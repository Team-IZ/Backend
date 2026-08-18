package com.bigproject.backend.domain.assessment.presentation.dto;

import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SlotState;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
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
		@Schema(description = "문제 번호. 생성된 문제만 1부터 세므로 항상 1~problemTotal 범위다",
				requiredMode = Schema.RequiredMode.REQUIRED)
		int problemNo,
		@Schema(description = "생성된 문제 수. 화면의 `문제 n/N`", requiredMode = Schema.RequiredMode.REQUIRED)
		int problemTotal,
		@Schema(description = "문제 제목. 검증하는 교안 개념 이름이다",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String title,
		@Schema(description = "코드 패널", requiredMode = Schema.RequiredMode.REQUIRED) Code code,
		@Schema(description = "이 문제에서 지금까지 확정된 문답. 화면은 위에서 아래로 쌓는다. "
				+ "아직 답한 것이 없으면 빈 배열이다", requiredMode = Schema.RequiredMode.REQUIRED)
		List<Turn> turns,
		// 문제가 끝나면 물어볼 것이 없다. NON_NULL이라 그때는 키가 빠지므로 required가 아니다.
		@Schema(description = "지금 물어보는 질문. 문제가 끝났으면 이 키가 없다")
		CurrentQuestion current,
		@Schema(description = "이 문제를 시작한 시각(문제별 상한의 기산점). 시작 전이거나 지금 문제가 "
				+ "아니면 이 키가 없다")
		Instant problemStartedAt,
		@Schema(description = "이 문제의 제한 시각. 화면의 문제별 카운트다운은 이 값에서 잰다. "
				+ "서버가 문제를 접는 판정도 같은 값을 쓴다")
		Instant problemTimeLimitAt
) {

	@Schema(description = "코드 패널. snippet은 파일 전체이며 자를 위치는 화면이 정한다")
	public record Code(
			String path,
			String language,
			@Schema(description = "문제를 낸 파일 전체") String snippet,
			@Schema(description = "snippet 첫 줄의 파일 기준 절대 줄 번호. 화면은 여기서부터 번호를 매긴다")
			int lineStart,
			@Schema(description = "snippet 마지막 줄의 절대 줄 번호. snippet에서 도출하므로 "
					+ "lineEnd - lineStart + 1 이 곧 snippet 줄 수다")
			int lineEnd,
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
			@Schema(description = "질문 순번. 화면의 `◆ 질문 2`", requiredMode = Schema.RequiredMode.REQUIRED)
			int sequenceNo,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String questionText,
			// 첫 시도에는 앞선 힌트가 없다. NON_NULL이라 그때는 키가 빠진다.
			@Schema(description = "이 턴 직전에 보여준 힌트. 첫 시도면 이 키가 없다") String hintText,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String answerText,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant answeredAt,
			@Schema(description = "강조할 구간. 질문마다 옮겨간다. 축별 구간이 없으면 문제의 대표 구간이다",
					requiredMode = Schema.RequiredMode.REQUIRED)
			Highlight highlight
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CurrentQuestion(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int sequenceNo,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String questionText,
			@Schema(description = "이미 연 힌트 문구. 없으면 빈 배열이다",
					requiredMode = Schema.RequiredMode.REQUIRED)
			List<String> shownHints,
			@Schema(description = "지금까지 쓴 힌트 수(0~2)", requiredMode = Schema.RequiredMode.REQUIRED)
			int hintsUsed,
			@Schema(description = "남은 힌트 수. 다시 보기도 1차와 같다(2회)",
					requiredMode = Schema.RequiredMode.REQUIRED)
			int hintsLeft,
			@Schema(description = "강조할 구간", requiredMode = Schema.RequiredMode.REQUIRED)
			Highlight highlight,
			@Schema(description = "이 답변이 세션의 마지막인지. 버튼 문구가 `답변 제출하고 마치기`로 바뀐다",
					requiredMode = Schema.RequiredMode.REQUIRED)
			boolean lastTurnOfSession
	) {
	}

	public record Highlight(String path, Integer lineStart, Integer lineEnd) {
	}

	public static ProblemActivityResponse of(SessionHead head, SessionProblem problem, int problemTotal,
			int problemTimeLimitMinutes) {
		List<Turn> turns = new ArrayList<>();
		CurrentQuestion current = null;

		for (SessionStage stage : problem.stages()) {
			addTurn(turns, stage, AnswerSlot.QUESTION, null, problem);
			addTurn(turns, stage, AnswerSlot.FIRST_HINT, stage.firstHintText(), problem);
			addTurn(turns, stage, AnswerSlot.SECOND_HINT, stage.secondHintText(), problem);

			if (current == null && stage.problemStageId().equals(head.currentProblemStageId())) {
				current = toCurrent(problem, stage);
			}
		}
		Instant startedAt = problemStartedAt(head, problem);
		return new ProblemActivityResponse(problem.problemNo(), problemTotal, problem.title(),
				toCode(problem), turns, current, startedAt,
				startedAt == null ? null : startedAt.plus(Duration.ofMinutes(problemTimeLimitMinutes)));
	}

	/**
	 * 이 문제의 기산점. <b>지금 문제일 때만 값이 있다.</b>
	 *
	 * <p>{@code assessment_session.current_problem_started_at}은 이름 그대로 <b>지금</b> 문제의 것이라,
	 * 이미 닫힌 문제를 열어 볼 때(세션 종료 후) 그 값을 그대로 주면 남의 시각을 이 문제의 것처럼 말하게
	 * 된다. 커서가 이 문제를 가리킬 때만 내려보낸다.
	 *
	 * <p>이 값을 내려보내는 이유는 <b>화면이 셀 근거가 없었기</b> 때문이다(37차 R3). 세션 시작 시각에서
	 * 재면 두 번째 문제부터 전부 틀리고, 틀린 카운트다운은 없는 것만 못하다. 서버가 문제를 접을 때 쓰는
	 * 기준({@code SessionGuard})과 같은 값을 주어야 화면과 판정이 갈리지 않는다.
	 */
	private static Instant problemStartedAt(SessionHead head, SessionProblem problem) {
		boolean isCurrent = head.currentProblemId() != null
				&& head.currentProblemId().equals(problem.problemId());
		return isCurrent ? head.currentProblemStartedAt() : null;
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

	private static CurrentQuestion toCurrent(SessionProblem problem, SessionStage stage) {
		int hintsUsed = stage.hintsUsed();
		List<String> shown = new ArrayList<>();
		if (hintsUsed >= 1) {
			shown.add(stage.firstHintText());
		}
		if (hintsUsed >= 2) {
			shown.add(stage.secondHintText());
		}
		// 다시 보기도 1차와 같은 수를 준다(37차 R2). 종전에는 여기서 0으로 눌러 화면이 버튼 대신
		// 이유 문구를 그리게 했는데, 힌트 자체를 열게 되면서 그 특례가 사라졌다.
		int hintsLeft = 2 - hintsUsed;
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
		Highlight highlight = problem.references().stream()
				.filter(reference -> "QUESTION_HIGHLIGHT".equals(reference.referenceType())
						&& axisCode != null && axisCode.equals(reference.axisCode()))
				.findFirst()
				.map(reference -> new Highlight(reference.sourcePath(), reference.lineStart(), reference.lineEnd()))
				.orElseGet(() -> new Highlight(problem.sourcePath(), problem.lineStart(), problem.lineEnd()));
		return clampToSnippet(highlight, problem);
	}

	/**
	 * 하이라이트를 <b>실제로 보낸 스니펫 안</b>으로 자른다.
	 *
	 * <h2>왜 필요한가 (37차 R4)</h2>
	 *
	 * <p>두 값이 서로 다른 곳에서 온다 — 스니펫 원문은 {@code submission.code_snippets}, 좌표는
	 * {@code assessment_problem}·{@code assessment_problem_reference}이고, 둘의 일치를 강제하는 제약이
	 * 없다. AI 응답이 어긋나면 실측처럼 <b>4줄짜리 스니펫에 21~24줄을 강조하라</b>는 값이 그대로 나간다.
	 *
	 * <p>화면은 스니펫이 만들어 낸 줄에만 색을 칠하므로 깨지지는 않는다. 대신 <b>강조가 소리 없이
	 * 잘린다</b> — 학생에게는 "이 범위를 보라"는 신호가 절반만 전달되고, 화면은 자기가 무엇을 못 그렸는지
	 * 모른다. 응답이 스스로 앞뒤가 맞으면 그 조용한 절단이 사라진다.
	 *
	 * <p>DB 값은 고치지 않는다. 여기서 하는 일은 <b>API가 거짓말을 하지 않게</b> 하는 것뿐이고, 어긋난
	 * 원본을 바로잡는 것은 AI 계약 쪽 일이다.
	 */
	private static Highlight clampToSnippet(Highlight highlight, SessionProblem problem) {
		Integer lastLine = snippetLastLine(problem);
		if (lastLine == null || highlight.lineStart() == null || highlight.lineEnd() == null) {
			return highlight;
		}
		// 시작부터 스니펫 밖이면 자를 것이 없다. 억지로 당기면 AI가 가리킨 곳과 다른 줄을 강조하게 되는데,
		// 엉뚱한 줄을 확신에 차서 칠하는 것보다 원본을 그대로 두고 화면이 못 그리는 편이 정직하다.
		if (highlight.lineStart() > lastLine || highlight.lineEnd() <= lastLine) {
			return highlight;
		}
		return new Highlight(highlight.path(), highlight.lineStart(), lastLine);
	}

	/** 스니펫이 실제로 덮는 마지막 줄 번호. 스니펫이 비었으면 {@code null}. */
	private static Integer snippetLastLine(SessionProblem problem) {
		String snippet = problem.codeSnippet();
		if (snippet == null || snippet.isEmpty()) {
			return null;
		}
		// -1: split이 끝의 빈 조각을 버리지 않게 한다. 마지막 줄이 개행으로 끝나면 그 뒤의 빈 줄까지
		// 세어야 화면이 그리는 줄 수와 같아진다(화면도 같은 방식으로 쪼갠다).
		return problem.lineStart() + snippet.split("\n", -1).length - 1;
	}

	/**
	 * 코드 패널.
	 *
	 * <p>{@code lineEnd}를 {@code assessment_problem.source_line_end}가 아니라 <b>스니펫에서 도출한다.</b>
	 * 저장된 값이 스니펫보다 길게 적혀 있는 경우가 실제로 있었고(37차 R4 — 20~32라고 적혀 있는데 본문은
	 * 4줄), 그 값을 믿고 계산하는 쪽은 전부 틀린다. 화면이 줄 번호를 붙이는 근거는 {@code lineStart}와
	 * 스니펫뿐이므로, 끝 번호도 같은 근거에서 나와야 응답이 스스로 앞뒤가 맞는다.
	 */
	private static Code toCode(SessionProblem problem) {
		List<Reference> references = problem.references().stream()
				// 대표 블록과 하이라이트는 code·highlight로 이미 나가므로 패널 목록에서는 뺀다.
				.filter(reference -> !"PRIMARY_BLOCK".equals(reference.referenceType())
						&& !"QUESTION_HIGHLIGHT".equals(reference.referenceType()))
				.map(reference -> new Reference(reference.referenceType(), reference.sourcePath(),
						reference.lineStart(), reference.lineEnd(), reference.axisCode()))
				.toList();
		Integer lastLine = snippetLastLine(problem);
		return new Code(problem.sourcePath(), problem.codeLanguage(), problem.codeSnippet(),
				problem.lineStart(), lastLine == null ? problem.lineEnd() : lastLine, references);
	}

}
