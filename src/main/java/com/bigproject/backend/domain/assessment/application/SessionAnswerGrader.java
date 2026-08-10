package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.AnswerResult;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.AnswerSubmit;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.Cursor;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.Hint;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.Problem;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.ProblemReference;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.ProblemStage;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.TranscriptTurn;
import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SlotState;
import com.bigproject.backend.global.ai.AiCallException;
import com.bigproject.backend.global.ai.AiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 답변 하나를 AI에 보내 채점받는다. <b>AI 호출은 트랜잭션 밖에서 일어나야 한다</b> —
 * 채점이 4.5~7.7초 걸리는데 그동안 DB 커넥션을 붙들면 동시 응시 인원만큼 커넥션이 잠긴다.
 * 그래서 이 클래스는 DB를 만지지 않고, 호출부가 "읽기 → (여기) → 쓰기" 순으로 배치한다.
 *
 * <h2>멱등키는 서버가 만든다</h2>
 *
 * <p>AI는 같은 {@code clientRequestId}를 재전송하면 처음 응답을 그대로 돌려준다. 그런데 저장할 컬럼이
 * 없어(테이블정의서에 세션용 멱등키 자리가 없다) 클라이언트가 준 값을 보관할 수 없다. 대신
 * <b>세션·문제·축·힌트사용수로 결정론적 UUID</b>를 만든다 — 같은 자리에서 다시 제출하면 자동으로 같은
 * 키가 나오므로 네트워크 재시도가 LLM 비용을 두 번 쓰지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionAnswerGrader {

	/** 결정론적 멱등키의 이름공간. 값 자체는 의미가 없고 다른 도메인 키와 겹치지 않기만 하면 된다. */
	private static final UUID IDEMPOTENCY_NAMESPACE =
			UUID.fromString("6f0b5f4a-6c31-4a1c-9a2e-4f4e0f1f7a10");

	private final AiClient aiClient;

	/** 채점 모델. 비우면 AI 서버 기본값을 쓴다 — 다른 경로(analyses·curricula)와 같은 규칙이다. */
	@Value("${ai.session.provider-model-code:}")
	private String providerModelCode;

	/**
	 * @param answerText 학생이 쓴 답. 힌트만 열고 답하지 않는 흐름은 여기 오지 않는다(별도 엔드포인트)
	 * @throws SessionException AI가 실패했을 때. 재전송 가능 여부는 {@code GRADING_FAILED} 하나로 접는다 —
	 *                          학생 화면에서 할 수 있는 일은 어느 쪽이든 "다시 제출"뿐이다
	 */
	public AnswerResult grade(SessionHead head, List<SessionProblem> problems, SessionStage currentStage,
			AnswerSlot slot, String answerText, String traceId) {
		AnswerSubmit body = new AnswerSubmit(
				idempotencyKey(head.sessionId(), currentStage, slot).toString(),
				answerText,
				problems.stream().map(SessionAnswerGrader::toProblem).toList(),
				transcript(problems),
				new Cursor(currentStage.problemId(), currentStage.axisCode(), slot.hintsUsed(), null),
				providerModelCode == null || providerModelCode.isBlank() ? null : providerModelCode);

		try {
			return aiClient.post("/sessions/" + head.sessionId() + "/answers", body, AnswerResult.class,
					body.clientRequestId(), traceId);
		} catch (AiCallException exception) {
			log.warn("채점 실패: sessionId={}, stageId={}, slot={}, retryable={}",
					head.sessionId(), currentStage.problemStageId(), slot, exception.retryable(), exception);
			throw new SessionException(SessionErrorCode.GRADING_FAILED, exception);
		}
	}

	/**
	 * 같은 자리에 대한 재전송이 같은 키가 되도록 만든다.
	 *
	 * <p>UUID v5(SHA-1 이름 기반)와 같은 방식이되 JDK에 v5 생성기가 없어 v3(MD5)를 쓴다. 여기서 MD5는
	 * 보안 용도가 아니라 이름 → 고정 UUID 사상일 뿐이고, 값을 맞혀도 얻을 것이 없다(자기 세션의 자기 답변).
	 */
	static UUID idempotencyKey(UUID sessionId, SessionStage stage, AnswerSlot slot) {
		String name = IDEMPOTENCY_NAMESPACE + ":" + sessionId + ":" + stage.problemStageId()
				+ ":" + stage.axisCode() + ":" + slot.hintsUsed();
		return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * 지금까지 확정된 턴 전부. {@code problem_stage}의 슬롯 셋을 펼치면 그대로 복원된다 —
	 * 힌트를 열기만 하고 답하지 않은 슬롯은 답이 없으므로 나오지 않는다.
	 */
	private static List<TranscriptTurn> transcript(List<SessionProblem> problems) {
		List<TranscriptTurn> turns = new ArrayList<>();
		for (SessionProblem problem : problems) {
			for (SessionStage stage : problem.stages()) {
				addTurn(turns, problem, stage, AnswerSlot.QUESTION, null);
				addTurn(turns, problem, stage, AnswerSlot.FIRST_HINT, stage.firstHintText());
				addTurn(turns, problem, stage, AnswerSlot.SECOND_HINT, stage.secondHintText());
			}
		}
		return turns;
	}

	private static void addTurn(List<TranscriptTurn> turns, SessionProblem problem, SessionStage stage,
			AnswerSlot slot, String hintText) {
		SlotState state = stage.slot(slot);
		if (!state.isAnswered()) {
			return;
		}
		turns.add(new TranscriptTurn(
				problem.problemId(),
				stage.axisCode(),
				stage.questionText(),
				state.answerText(),
				state.answeredAt() == null ? null : state.answeredAt().toString(),
				state.score() == null ? 0 : state.score(),
				Boolean.TRUE.equals(state.passed()),
				slot.hintsUsed(),
				hintText));
	}

	private static Problem toProblem(SessionProblem problem) {
		return new Problem(
				problem.problemId(),
				problem.problemNo(),
				problemStatus(problem),
				problem.problemType(),
				problem.priority(),
				problem.questionFocusItemId(),
				problem.title(),
				problem.snippetKey(),
				problem.codeLanguage(),
				problem.sourcePath(),
				problem.lineStart(),
				problem.lineEnd(),
				problem.codeSnippet(),
				problem.contentHash(),
				problem.evidenceHash(),
				problem.extractorVersion(),
				problem.teachId(),
				problem.references().stream()
						.map(reference -> new ProblemReference(
								reference.referenceType(), reference.displayOrder(), reference.sourcePath(),
								reference.lineStart(), reference.lineEnd(), reference.axisCode(),
								reference.teachId(), reference.evidenceHash()))
						.toList(),
				stages(problem));
	}

	/**
	 * 단계는 <b>4개 고정</b>이라 세션에 깔린 것을 그대로 보낸다.
	 *
	 * <p>다시 보기(REVIEW)는 막힌 축 한 행만 저장되므로 그대로 보내면 계약 위반이다. 그 경우는 호출부가
	 * 원본 세션의 4단계를 실어 보내며, 여기서는 받은 것을 옮기기만 한다.
	 */
	private static List<ProblemStage> stages(SessionProblem problem) {
		return problem.stages().stream()
				.map(stage -> new ProblemStage(
						stage.axisCode(),
						stage.questionText(),
						null,
						List.of(new Hint(1, stage.firstHintText()), new Hint(2, stage.secondHintText()))))
				.toList();
	}

	/** AI는 상태를 안 들고 있으므로 문제 진행 상태도 우리가 알려준다. 단계 상태에서 파생한다. */
	private static String problemStatus(SessionProblem problem) {
		boolean anyAnswered = problem.stages().stream()
				.anyMatch(stage -> stage.question().isAnswered());
		boolean allTerminal = problem.stages().stream().allMatch(SessionStage::isTerminal);
		if (allTerminal) {
			return "COMPLETED";
		}
		return anyAnswered ? "IN_PROGRESS" : "READY";
	}
}
