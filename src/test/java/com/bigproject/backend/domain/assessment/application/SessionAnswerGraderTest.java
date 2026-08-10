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
	private SessionAnswerGrader grader;

	@BeforeEach
	void setUp() {
		aiClient = mock(AiClient.class);
		grader = new SessionAnswerGrader(aiClient);
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
		grader.grade(head(), List.of(problem()), stageL2(), AnswerSlot.FIRST_HINT, "답변", "trace");

		AnswerSubmit body = capture();
		assertThat(body.cursor().problemId()).isEqualTo(PROBLEM_ID);
		assertThat(body.cursor().axisCode()).isEqualTo("L2");
		assertThat(body.cursor().hintsUsed()).isEqualTo(1);
	}

	/**
	 * 같은 자리에 대한 재전송은 같은 멱등키여야 한다. 다르면 AI가 새 요청으로 보고 LLM 비용을 다시 쓴다 —
	 * 네트워크 타임아웃 후 재시도가 정확히 이 경우다.
	 */
	@Test
	void 같은_자리_재전송은_같은_멱등키를_쓴다() {
		String first = SessionAnswerGrader.idempotencyKey(SESSION_ID, stageL2(), AnswerSlot.QUESTION).toString();
		String again = SessionAnswerGrader.idempotencyKey(SESSION_ID, stageL2(), AnswerSlot.QUESTION).toString();
		String afterHint = SessionAnswerGrader.idempotencyKey(SESSION_ID, stageL2(), AnswerSlot.FIRST_HINT)
				.toString();

		assertThat(first).isEqualTo(again);
		assertThat(first).isNotEqualTo(afterHint);
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

	private static SessionStage stageL1() {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(STAGE_L1, PROBLEM_ID, 1, "L1", 1, "L1 질문", "L1 힌트1", "L1 힌트2", "PASSED",
				new SlotState("L1 답", (short) 2, false, Instant.now()),
				new SlotState("L1 힌트 뒤 답", (short) 4, true, Instant.now()),
				empty, Instant.now(), null, 2L);
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
