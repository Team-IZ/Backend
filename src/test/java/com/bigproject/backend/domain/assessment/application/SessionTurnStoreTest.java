package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.AnswerResult;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.Cursor;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.Question;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.TranscriptTurn;
import com.bigproject.backend.domain.assessment.application.SessionTurnStore.GradingInput;
import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblemReference;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SlotState;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import com.bigproject.backend.domain.assessment.presentation.dto.AnswerSubmitResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.ProblemActivityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 채점 결과를 슬롯에 확정하는 규칙을 고정한다.
 *
 * <p>여기 걸어 두는 것들은 전부 <b>DB CHECK가 뒤늦게 터지는 자리</b>다. {@code NOT_PASSED}를 아무
 * 슬롯에서나 쓰면 {@code ck_problem_stage_status_2}가 거절하는데, 그 사고는 학생이 30분을 쓴 뒤
 * 마지막 제출에서 처음 드러난다.
 */
class SessionTurnStoreTest {

	private static final UUID SESSION_ID = UUID.randomUUID();
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID PROBLEM_ID = UUID.randomUUID();
	private static final UUID STAGE_ID = UUID.randomUUID();

	private JdbcSessionRepository repository;
	private SessionTurnStore store;

	@BeforeEach
	void setUp() {
		repository = mock(JdbcSessionRepository.class);
		store = new SessionTurnStore(repository, new SessionGuard(repository));
		when(repository.applyAnswer(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.anyBoolean(), anyString(), anyLong())).thenReturn(1);
		// AI가 준 커서(문제 + 축)를 단계 ID로 되돌릴 때 쓰인다. 비워 두면 커서 이동이 STAGE_NOT_FOUND로 죽는다.
		when(repository.findStages(SESSION_ID)).thenReturn(List.of(stage(), nextStage()));
	}

	@Test
	void 통과하면_단계를_PASSED로_닫는다() {
		GradingInput input = input(AnswerSlot.QUESTION);

		store.applyGrading(input, result(5, true, cursorAt("L2")), "답변");

		verify(repository).applyAnswer(eq(STAGE_ID), eq(AnswerSlot.QUESTION), eq("답변"), eq(5), eq(true),
				eq("PASSED"), anyLong());
	}

	/**
	 * 미달인데 힌트가 남아 있으면 아직 끝난 단계가 아니다. 여기서 NOT_PASSED로 닫으면
	 * {@code ck_problem_stage_status_2}가 "슬롯 셋이 모두 FALSE"를 요구해 CHECK 위반으로 터진다.
	 */
	@Test
	void 미달이어도_힌트가_남았으면_IN_PROGRESS로_둔다() {
		GradingInput input = input(AnswerSlot.QUESTION);

		store.applyGrading(input, result(1, false, cursorAt("L1")), "답변");

		verify(repository).applyAnswer(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
				eq(false), eq("IN_PROGRESS"), anyLong());
	}

	@Test
	void 두번째_힌트까지_쓰고_미달이면_NOT_PASSED다() {
		GradingInput input = input(AnswerSlot.SECOND_HINT);

		store.applyGrading(input, result(2, false, cursorAt("L2")), "답변");

		verify(repository).applyAnswer(any(), eq(AnswerSlot.SECOND_HINT), anyString(),
				org.mockito.ArgumentMatchers.anyInt(), eq(false), eq("NOT_PASSED"), anyLong());
	}

	/** 임계값 경계. 3점은 통과다 — DB CHECK의 {@code score >= 3}과 같은 값이어야 한다. */
	@Test
	void 삼점이면_통과다() {
		store.applyGrading(input(AnswerSlot.QUESTION), result(3, true, cursorAt("L2")), "답변");

		verify(repository).applyAnswer(any(), any(), anyString(), eq(3), eq(true), eq("PASSED"), anyLong());
	}

	@Test
	void 삼점_미만이면_실패다() {
		store.applyGrading(input(AnswerSlot.QUESTION), result(2, false, cursorAt("L1")), "답변");

		verify(repository).applyAnswer(any(), any(), anyString(), eq(2), eq(false), eq("IN_PROGRESS"),
				anyLong());
	}

	/**
	 * AI가 점수와 어긋나는 통과 판정을 보내도 <b>점수를 따른다.</b> 그대로 옮겨 적으면
	 * {@code ck_problem_stage_question_score_2}가 UPDATE를 거절해 학생이 쓴 답이 통째로 사라진다.
	 */
	@Test
	void AI의_통과_판정이_점수와_어긋나면_점수를_따른다() {
		store.applyGrading(input(AnswerSlot.QUESTION), result(2, true, cursorAt("L1")), "답변");

		verify(repository).applyAnswer(any(), any(), anyString(), eq(2), eq(false), eq("IN_PROGRESS"),
				anyLong());
	}

	/** 0~5 밖은 CHECK가 거절한다. 저장 전에 걸러야 학생이 같은 답을 다시 제출할 수 있다. */
	@Test
	void 범위를_벗어난_점수는_저장하지_않는다() {
		GradingInput input = input(AnswerSlot.QUESTION);

		assertThatThrownBy(() -> store.applyGrading(input, result(9, true, cursorAt("L2")), "답변"))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.GRADING_FAILED);
		verify(repository, never()).applyAnswer(any(), any(), anyString(),
				org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), anyString(),
				anyLong());
	}

	/**
	 * 채점 결과가 없으면 <b>0점 실패로 적지 않는다.</b> AI가 답을 읽지도 못한 답변이 미달로 기록되면
	 * 학생은 쓰지도 않은 힌트를 하나 잃는다.
	 */
	@Test
	void 채점_결과가_없으면_저장하지_않고_재제출을_요구한다() {
		GradingInput input = input(AnswerSlot.QUESTION);
		AnswerResult noTurn = new AnswerResult(SESSION_ID, "IN_PROGRESS", null, cursorAt("L1"), null, null,
				null, null, List.of());

		assertThatThrownBy(() -> store.applyGrading(input, noTurn, "답변"))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.GRADING_FAILED);
		verify(repository, never()).applyAnswer(any(), any(), anyString(),
				org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean(), anyString(),
				anyLong());
	}

	/** AI가 커서를 비우면 "더 물을 것이 없다"는 뜻이다. 백엔드가 커서 변화로 역추론하지 않는다. */
	@Test
	void 커서가_비면_세션을_닫는다() {
		GradingInput input = input(AnswerSlot.QUESTION);

		AnswerSubmitResponse response = store.applyGrading(input,
				new AnswerResult(SESSION_ID, "COMPLETED", turn(3, true), null, null, null, "COMPLETED_L4", "L4",
						List.of()),
				"답변");

		assertThat(response.outcome()).isEqualTo("SESSION_ENDED");
		verify(repository).end(SESSION_ID, "ALL_PROBLEMS_TERMINAL", "L4");
	}

	@Test
	void 다시_보기가_끝나면_종료_사유가_다르다() {
		GradingInput input = input(AnswerSlot.QUESTION, "REVIEW");

		store.applyGrading(input,
				new AnswerResult(SESSION_ID, "COMPLETED", turn(4, true), null, null, null, null, null, List.of()),
				"답변");

		verify(repository).end(SESSION_ID, "ALL_REVIEW_TARGETS_TERMINAL", null);
	}

	/**
	 * 읽은 뒤 누군가 같은 자리에 답했다. 되돌릴 수 없는 제출이라 마지막 쓰기가 이기게 두면 학생이 쓴 답이
	 * 조용히 사라진다 — 덮어쓰지 않고 거절한다.
	 */
	@Test
	void 낙관적_잠금이_어긋나면_거절한다() {
		when(repository.applyAnswer(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.anyBoolean(), anyString(), anyLong())).thenReturn(0);
		GradingInput input = input(AnswerSlot.QUESTION);

		assertThatThrownBy(() -> store.applyGrading(input, result(5, true, cursorAt("L2")), "답변"))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.ANSWER_ALREADY_SUBMITTED);
		verify(repository, never()).moveCursor(any(), any(), any());
	}

	@Test
	void 빈_답변은_AI를_부르기_전에_막는다() {
		assertThatThrownBy(() -> store.loadForGrading(USER_ID, SESSION_ID, "   "))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.ANSWER_TEXT_REQUIRED);
	}

	/** 시작하지 않은 세션에는 답을 받지 않는다 — 인트로 동의 기록이 없는 응시가 남는다. */
	@Test
	void 시작하지_않은_세션에는_답을_받지_않는다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("READY", "INITIAL", null)));

		assertThatThrownBy(() -> store.loadForGrading(USER_ID, SESSION_ID, "답변"))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.SESSION_NOT_STARTED);
	}

	/** 70분을 넘겼으면 답을 받지 않고 그 자리에서 닫는다. 답한 데까지는 그대로 남는다. */
	@Test
	void 시간_상한을_넘기면_그_자리에서_닫는다() {
		when(repository.findOwned(SESSION_ID, USER_ID))
				.thenReturn(Optional.of(head("IN_PROGRESS", "INITIAL", Instant.now().minusSeconds(60))));

		assertThatThrownBy(() -> store.loadForGrading(USER_ID, SESSION_ID, "답변"))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.SESSION_TIMEOUT);
		verify(repository).end(SESSION_ID, "POLICY_TIME_LIMIT_EXCEEDED", null);
	}

	/**
	 * 질문은 축마다 다른 줄을 가리킨다(화면 목업: 질문 1 → {@code 5–8}, 질문 2 → {@code 39–41}).
	 * 다음 질문 문구만 주고 구간을 빼면 화면은 이전 질문의 구간을 강조한 채 다음 질문을 묻는다.
	 */
	@Test
	void 다음_질문의_강조_구간이_축을_따라_옮겨간다() {
		GradingInput input = new GradingInput(head("IN_PROGRESS", "INITIAL", null), List.of(problem()),
				stage(), AnswerSlot.QUESTION);

		AnswerSubmitResponse response = store.applyGrading(input,
				new AnswerResult(SESSION_ID, "IN_PROGRESS", turn(5, true), cursorAt("L2"),
						new Question(PROBLEM_ID, "L2", 2, "질문2", null, 0), null, null, null, List.of()),
				"답변");

		assertThat(response.next().axisCode()).isEqualTo("L2");
		assertThat(response.next().highlight())
				.isEqualTo(new ProblemActivityResponse.Highlight("graph.py", 39, 41));
	}

	/** 그 축에 하이라이트가 없으면 문제의 대표 구간으로 떨어진다 — 강조가 사라지지는 않는다. */
	@Test
	void 축에_하이라이트가_없으면_대표_구간을_쓴다() {
		GradingInput input = new GradingInput(head("IN_PROGRESS", "INITIAL", null), List.of(problem()),
				stage(), AnswerSlot.QUESTION);

		AnswerSubmitResponse response = store.applyGrading(input,
				new AnswerResult(SESSION_ID, "IN_PROGRESS", turn(5, true), cursorAt("L2"),
						new Question(PROBLEM_ID, "L4", 4, "질문4", null, 0), null, null, null, List.of()),
				"답변");

		assertThat(response.next().highlight())
				.isEqualTo(new ProblemActivityResponse.Highlight("graph.py", 1, 60));
	}

	// ── 픽스처 ──

	private GradingInput input(AnswerSlot slot) {
		return input(slot, "INITIAL");
	}

	private GradingInput input(AnswerSlot slot, String attemptType) {
		return new GradingInput(head("IN_PROGRESS", attemptType, null), List.of(), stage(), slot);
	}

	/** 축별 하이라이트 두 벌을 단 문제. L4에는 일부러 없다. */
	private static SessionProblem problem() {
		return new SessionProblem(PROBLEM_ID, 1, "Graph 구성", "DESIGN_CHOICE", null, null, null,
				"snippet-1", "python", "graph.py", 1, 60, "hash", 1, "코드 전체", "content-hash",
				List.of(new SessionProblemReference("QUESTION_HIGHLIGHT", 1, "graph.py", 5, 8, "L1", null, "h1"),
						new SessionProblemReference("QUESTION_HIGHLIGHT", 2, "graph.py", 39, 41, "L2", null, "h2")),
				List.of(stage(), nextStage()));
	}

	private static SessionHead head(String status, String attemptType, Instant timeLimitAt) {
		return new SessionHead(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), USER_ID, UUID.randomUUID(),
				attemptType, status, PROBLEM_ID, STAGE_ID, Instant.now(), timeLimitAt, null, UUID.randomUUID());
	}

	private static SessionStage stage() {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(STAGE_ID, PROBLEM_ID, 1, "L1", 1, "질문", "힌트1", "힌트2", "IN_PROGRESS",
				empty, empty, empty, null, null, 3L);
	}

	/** 커서가 옮겨 갈 다음 축. 같은 문제의 L2다. */
	private static SessionStage nextStage() {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(UUID.randomUUID(), PROBLEM_ID, 1, "L2", 2, "질문2", "힌트1", "힌트2",
				"PREPARED", empty, empty, empty, null, null, 0L);
	}

	private static AnswerResult result(int score, boolean passed, Cursor cursor) {
		return new AnswerResult(SESSION_ID, "IN_PROGRESS", turn(score, passed), cursor, null, null, null, null,
				List.of());
	}

	private static TranscriptTurn turn(int score, boolean passed) {
		return new TranscriptTurn(PROBLEM_ID, "L1", "질문", "답변", Instant.now().toString(), score, passed, 0,
				null);
	}

	private static Cursor cursorAt(String axisCode) {
		return new Cursor(PROBLEM_ID, axisCode, 0, null);
	}
}
