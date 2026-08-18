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
import com.bigproject.backend.global.ai.AiClientConfig;
import com.bigproject.backend.global.ai.AsyncAiProxyWarmUp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class SessionAnswerGrader {

	/** 결정론적 멱등키의 이름공간. 값 자체는 의미가 없고 다른 도메인 키와 겹치지 않기만 하면 된다. */
	private static final UUID IDEMPOTENCY_NAMESPACE =
			UUID.fromString("6f0b5f4a-6c31-4a1c-9a2e-4f4e0f1f7a10");

	/** 세션 채점은 프록시로 나간다. base-url에 프리픽스가 없어 경로에서 붙인다. */
	private static final String ANSWERS_PATH_PREFIX = AiClient.API_V0 + "/sessions/";

	private final AiClient aiClient;
	private final AsyncAiProxyWarmUp asyncWarmUp;

	public SessionAnswerGrader(@Qualifier(AiClientConfig.AI_SESSION_CLIENT) AiClient aiClient,
			AsyncAiProxyWarmUp asyncWarmUp) {
		this.aiClient = aiClient;
		this.asyncWarmUp = asyncWarmUp;
	}

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
				// hintsUsed는 <b>이 답을 쓰기 전에 질문을 몇 번 다시 들었는가</b>다.
				// 표시 시각에서 복원한 값을 써야 새로고침 뒤에도 AI가 같은 도움 수준으로 채점한다.
				new Cursor(currentStage.problemId(), currentStage.axisCode(), currentStage.hintsUsed(), null),
				providerModelCode == null || providerModelCode.isBlank() ? null : providerModelCode);

		String path = ANSWERS_PATH_PREFIX + head.sessionId() + "/answers";
		try {
			return post(path, body, traceId, head, currentStage, slot);
		} catch (AiCallException failure) {
			if (isGatewayFailure(failure)) {
				// 종전에는 여기서 프록시를 깨우고(최대 150초) 같은 요청 안에서 한 번 더 보냈다(다시 150초).
				// 읽기 타임아웃도 status가 비어 이 분기를 타므로 한 번의 제출이 최악 450초까지 늘어났는데,
				// 그 전에 학생 연결이 끊겨 있어 되살린 응답이 닿을 곳이 없었다(37차 R1).
				//
				// 그래서 요청 안에서 기다리지 않는다. 깨우기는 뒤로 넘기고 지금은 곧바로 실패시킨다 —
				// 학생은 같은 답을 다시 내면 되고, 그때는 깨어 있는 서버가 받는다. 멱등키가 자리마다
				// 고정이라 재전송해도 LLM 비용이 늘지 않는다.
				asyncWarmUp.wakeInBackground("session-grading-failure");
			}
			log.warn("채점 실패: sessionId={}, stageId={}, slot={}, retryable={}",
					head.sessionId(), currentStage.problemStageId(), slot, failure.retryable(), failure);
			throw new SessionException(SessionErrorCode.GRADING_FAILED, failure);
		}
	}

	/**
	 * AI 왕복 한 번. <b>성공·실패 양쪽에 소요 시간을 남긴다.</b>
	 *
	 * <p>이 자리에 계측이 없으면 "답변이 접수되지 않는다"는 신고를 받았을 때 세 가지를 가를 수 없다 —
	 * ① AI가 끝내 응답하지 않았다(읽기 타임아웃까지 갔다) ② AI는 곧바로 응답했는데 중간 구간이 학생
	 * 연결을 끊었다 ③ 잠든 원본을 깨우느라 오래 걸렸다. 셋의 조치가 각각 AI팀·인프라·웜업으로 달라서,
	 * 구분하지 못하면 고칠 곳을 고를 수 없다.
	 *
	 * <p>성공을 {@code info}로 남기는 것은 의도적이다. 답변 하나에 한 줄이라 양이 문제 되지 않고,
	 * <b>정상일 때의 소요 시간</b>을 모르면 비정상을 판정할 기준선이 없다.
	 */
	private AnswerResult post(String path, AnswerSubmit body, String traceId, SessionHead head,
			SessionStage stage, AnswerSlot slot) {
		long startedNanos = System.nanoTime();
		try {
			AnswerResult result = aiClient.post(path, body, AnswerResult.class, body.clientRequestId(), traceId);
			log.info("채점 AI 응답: sessionId={}, stageId={}, slot={}, 소요={}ms, traceId={}, clientRequestId={}",
					head.sessionId(), stage.problemStageId(), slot, elapsedMillis(startedNanos),
					traceId, body.clientRequestId());
			return result;
		} catch (AiCallException exception) {
			// status가 비어 있으면 응답 자체가 오지 않은 것이다(연결 실패·읽기 타임아웃).
			// 그때의 소요 시간이 곧 우리가 기다린 상한이라, 이 값이 원인 판정의 핵심이다.
			log.warn("채점 AI 실패: sessionId={}, stageId={}, slot={}, 소요={}ms, status={}, retryable={}, traceId={}",
					head.sessionId(), stage.problemStageId(), slot, elapsedMillis(startedNanos),
					exception.status(), exception.retryable(), traceId);
			throw exception;
		}
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	/**
	 * 프록시·원본 사이의 일시 장애인가. 이때만 뒤에서 깨워 둔다 — 다음 제출이 빨리 성공하도록.
	 * 요청 오류인 4xx는 깨워도 달라지지 않으므로 그대로 실패시킨다.
	 */
	private static boolean isGatewayFailure(AiCallException exception) {
		if (!exception.retryable()) {
			return false;
		}
		if (exception.status() == null) {
			return true;
		}
		int status = exception.status().value();
		return status == 502 || status == 503 || status == 504;
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
	 *
	 * <p>한 축에서 턴이 최대 셋 나온다. 미달이면 힌트가 열리고 <b>같은 질문에 다시 답하기</b> 때문이며,
	 * {@code hintsUsed}가 몇 번째 시도인지를 말한다.
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
