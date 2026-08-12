package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.AnswerResult;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.AnswerSubmit;
import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SlotState;
import com.bigproject.backend.global.ai.AiCallException;
import com.bigproject.backend.global.ai.AiClient;
import com.bigproject.backend.global.ai.AiProxyWarmUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 요청 조립을 고정한다.
 *
 * <p><b>AI는 세션 상태를 들고 있지 않다.</b> 그래서 여기서 빠뜨린 것은 전부 채점 품질의 저하로 조용히
 * 나타난다 — 계약 위반이 아니라 "학생이 앞에서 뭐라고 답했는지 모르는 채점"이 된다. 단위 시험으로
 * 잡지 않으면 드러나지 않는 종류의 결함이라 요청 본문을 직접 들여다본다.
 */
class SessionAnswerGraderTest {

	private static final UUID SESSION_ID = UUID.randomUUID();
	private static final UUID PROBLEM_ID = UUID.randomUUID();
	private static final UUID STAGE_L1 = UUID.randomUUID();
	private static final UUID STAGE_L2 = UUID.randomUUID();

	private AiClient aiClient;
	private AiProxyWarmUp proxyWarmUp;
	private SessionAnswerGrader grader;

	@BeforeEach
	void setUp() {
		aiClient = mock(AiClient.class);
		proxyWarmUp = mock(AiProxyWarmUp.class);
		grader = new SessionAnswerGrader(aiClient, proxyWarmUp);
		when(aiClient.post(anyString(), any(), eq(AnswerResult.class), anyString(), any()))
				.thenReturn(new AnswerResult(SESSION_ID, "IN_PROGRESS", null, null, null, null, null, null,
						List.of()));
	}

	@Test
	void 문제마다_네_단계와_힌트_두_개를_모두_싣는다() {
		grader.grade(head(), List.of(problem()), stageL2(), AnswerSlot.QUESTION, "답변", "trace");

		AnswerSubmit body = capture();
		assertThat(body.problems()).hasSize(1);
		// AI 스키마가 stages를 4개 고정(minItems=maxItems=4)으로 요구한다.
		assertThat(body.problems().get(0).stages()).hasSize(4);
		assertThat(body.problems().get(0).stages())
				.allSatisfy(stage -> assertThat(stage.hints()).hasSize(2));
	}

	/**
	 * 확정된 답변만 transcript에 들어간다. 힌트를 열어 두기만 하고 답하지 않은 슬롯은 답이 없으므로
	 * 나오면 안 된다 — {@code answerText}가 필수 필드라 빈 턴을 보내면 계약 위반이다.
	 *
	 * <p>한 축에서 턴이 둘 나오는 것은 미달 후 힌트를 보고 <b>같은 질문에 다시 답했기</b> 때문이고,
	 * {@code hintsUsed}가 몇 번째 시도인지를 말한다.
	 */
	@Test
	void 확정된_답변만_transcript에_넣는다() {
		grader.grade(head(), List.of(problem()), stageL2(), AnswerSlot.QUESTION, "답변", "trace");

		AnswerSubmit body = capture();
		assertThat(body.transcript()).hasSize(2);
		assertThat(body.transcript()).extracting(AnswerGradingContract.TranscriptTurn::hintsUsed)
				.containsExactly(0, 1);
		assertThat(body.transcript()).extracting(AnswerGradingContract.TranscriptTurn::answerText)
				.containsExactly("L1 답", "L1 힌트 뒤 답");
	}

	/** 커서는 지금 답하는 자리를 가리킨다. 이게 어긋나면 AI가 다른 축의 기준으로 채점한다. */
	@Test
	void 커서는_지금_답하는_자리를_가리킨다() {
		grader.grade(head(), List.of(problem()), stageL2(), AnswerSlot.QUESTION, "답변", "trace");

		AnswerSubmit body = capture();
		assertThat(body.cursor().problemId()).isEqualTo(PROBLEM_ID);
		assertThat(body.cursor().axisCode()).isEqualTo("L2");
		assertThat(body.cursor().hintsUsed()).isZero();
	}

	/**
	 * 커서의 {@code hintsUsed}는 <b>답변 슬롯이 아니라 재진술 횟수</b>다. 슬롯에서 뽑으면 답변이
	 * 언제나 질문 슬롯인 지금 모델에서 항상 0이 나가고, 재진술을 본 사실이 AI에 전달되지 않는다.
	 */
	@Test
	void 커서의_힌트_수는_연_재진술_횟수다() {
		grader.grade(head(), List.of(problem()), stageL2AfterOneHint(), AnswerSlot.QUESTION, "답변", "trace");

		assertThat(capture().cursor().hintsUsed()).isEqualTo(1);
	}

	/**
	 * 같은 자리에 대한 재전송은 같은 멱등키여야 한다. 다르면 AI가 새 요청으로 보고 LLM 비용을 다시 쓴다 —
	 * 네트워크 타임아웃 후 재시도가 정확히 이 경우다.
	 *
	 * <p>재진술을 몇 번 열었는지는 키에 넣지 않는다. 넣으면 재전송 사이에 학생이 `다시 설명해 주세요`를
	 * 한 번 더 누른 것만으로 키가 바뀌어 같은 답이 두 번 과금된다.
	 */
	@Test
	void 같은_자리_재전송은_같은_멱등키를_쓴다() {
		String first = SessionAnswerGrader.idempotencyKey(SESSION_ID, stageL2(), AnswerSlot.QUESTION).toString();
		String again = SessionAnswerGrader.idempotencyKey(SESSION_ID, stageL2AfterOneHint(), AnswerSlot.QUESTION)
				.toString();
		String otherAxis = SessionAnswerGrader.idempotencyKey(SESSION_ID, stageL1(), AnswerSlot.QUESTION)
				.toString();

		assertThat(first).isEqualTo(again);
		assertThat(first).isNotEqualTo(otherAxis);
	}

	/** AI 실패는 학생 화면에서 할 수 있는 일이 "다시 제출"뿐이라 코드 하나로 접는다. */
	@Test
	void AI_실패는_GRADING_FAILED로_접는다() {
		when(aiClient.post(anyString(), any(), eq(AnswerResult.class), anyString(), any()))
				.thenThrow(new AiCallException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_ERROR", true, "실패"));

		assertThatThrownBy(() -> grader.grade(head(), List.of(problem()), stageL2(), AnswerSlot.QUESTION,
				"답변", "trace"))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.GRADING_FAILED);
	}

	@Test
	void 게이트웨이_실패는_웜업_후_같은_요청을_한_번_재시도한다() {
		AnswerResult recovered = new AnswerResult(SESSION_ID, "IN_PROGRESS", null, null, null, null,
				null, null, List.of());
		when(aiClient.post(anyString(), any(), eq(AnswerResult.class), anyString(), any()))
				.thenThrow(new AiCallException(HttpStatus.BAD_GATEWAY, null, true, "게이트웨이 실패"))
				.thenReturn(recovered);
		when(proxyWarmUp.warmUp()).thenReturn(true);

		AnswerResult result = grader.grade(head(), List.of(problem()), stageL2(), AnswerSlot.SECOND_HINT,
				"답변", "trace");

		assertThat(result).isSameAs(recovered);
		verify(proxyWarmUp).warmUp();
		verify(aiClient, times(2)).post(anyString(), any(), eq(AnswerResult.class), anyString(), any());
	}

	private AnswerSubmit capture() {
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(aiClient).post(anyString(), captor.capture(), eq(AnswerResult.class), anyString(), any());
		return (AnswerSubmit) captor.getValue();
	}

	// ── 픽스처: L1은 힌트 하나 쓰고 통과, L2는 아직 답 전 ──

	private static SessionHead head() {
		return new SessionHead(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "INITIAL", "IN_PROGRESS", PROBLEM_ID, STAGE_L2, Instant.now(), null, null,
				UUID.randomUUID());
	}

	private static SessionProblem problem() {
		return new SessionProblem(PROBLEM_ID, 1, "제목", "DESIGN_CHOICE", null, null, null, "key", "PYTHON",
				"nodes.py", 12, 20, "evidenceHash", 1, "code", "contentHash", List.of(),
				List.of(stageL1(), stageL2(), stage("L3", 3), stage("L4", 4)));
	}

	/** 첫 답이 미달이라 힌트가 열렸고, 힌트를 보고 쓴 두 번째 답으로 통과한 축. 턴이 둘 나온다. */
	private static SessionStage stageL1() {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(STAGE_L1, PROBLEM_ID, 1, "L1", 1, "L1 질문", "L1 힌트1", "L1 힌트2", "PASSED",
				new SlotState("L1 답", (short) 2, false, Instant.now()),
				new SlotState("L1 힌트 뒤 답", (short) 4, true, Instant.now()),
				empty, Instant.now(), null, 2L);
	}

	/** 재진술을 한 번 연 채 아직 답하지 않은 L2. 커서의 {@code hintsUsed}가 1이어야 한다. */
	private static SessionStage stageL2AfterOneHint() {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(STAGE_L2, PROBLEM_ID, 1, "L2", 2, "L2 질문", "L2 힌트1", "L2 힌트2",
				"IN_PROGRESS", empty, empty, empty, Instant.now(), null, 0L);
	}

	private static SessionStage stageL2() {
		return stage("L2", 2);
	}

	private static SessionStage stage(String axisCode, int sequenceNo) {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(axisCode.equals("L2") ? STAGE_L2 : UUID.randomUUID(), PROBLEM_ID, 1, axisCode,
				sequenceNo, axisCode + " 질문", axisCode + " 힌트1", axisCode + " 힌트2", "PREPARED",
				empty, empty, empty, null, null, 0L);
	}
}
