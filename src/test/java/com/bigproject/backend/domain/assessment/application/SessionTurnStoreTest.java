package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.AnswerResult;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.Cursor;
import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.Progress;
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
import org.springframework.transaction.annotation.Transactional;

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
	private static final UUID OTHER_PROBLEM_ID = UUID.randomUUID();
	private static final UUID STAGE_ID = UUID.randomUUID();

	private JdbcSessionRepository repository;
	private SessionTurnStore store;

	@BeforeEach
	void setUp() {
		repository = mock(JdbcSessionRepository.class);
		store = new SessionTurnStore(repository, new SessionGuard(repository, new SessionExpirer(repository)));
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
	 * 미달인데 힌트가 남아 있으면 아직 끝난 질문이 아니다. 여기서 NOT_PASSED로 닫으면 학생이 힌트를
	 * 보고 다시 답할 자리가 사라진다.
	 */
	@Test
	void 미달이어도_힌트가_남았으면_IN_PROGRESS로_둔다() {
		GradingInput input = input(AnswerSlot.QUESTION);

		store.applyGrading(input, result(1, false, cursorAt("L1")), "답변");

		verify(repository).applyAnswer(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
				eq(false), eq("IN_PROGRESS"), anyLong());
	}

	/**
	 * 힌트 2개를 다 쓰고도 미달이면 <b>축과 무관하게</b> 그 문제를 접고 다음 문제로 간다.
	 * L1이든 L4든 같다 — 두 번 설명하고도 닿지 않았으면 같은 코드에 더 물을 것이 없다.
	 *
	 * <p>AI 커서를 덮어쓴다. AI가 같은 문제의 L2를 가리켜도 우리는 다음 문제로 옮긴다 —
	 * 이 규칙이 모델 응답에 따라 흔들리면 리포트의 "도달 축"이 학생마다 다른 뜻이 된다.
	 */
	@Test
	void 힌트를_다_쓰고_미달이면_다음_문제로_넘어간다() {
		when(repository.findStages(SESSION_ID)).thenReturn(List.of(stage(), nextProblemStage()));
		GradingInput input = new GradingInput(head("IN_PROGRESS", "INITIAL", null), List.of(problem()),
				stageWithHints(2), AnswerSlot.SECOND_HINT);

		AnswerSubmitResponse response = store.applyGrading(input, result(1, false, cursorAt("L2")), "답변");

		assertThat(response.outcome()).isEqualTo("PROBLEM_CLOSED");
		assertThat(response.nextProblemNo()).isEqualTo(2);
		assertThat(response.next().questionText()).isEqualTo("2번 문제 질문");
		assertThat(response.hint()).isNull();
		verify(repository).moveCursor(eq(SESSION_ID), eq(OTHER_PROBLEM_ID), any());
	}

	/**
	 * 접힌 문제는 남은 축을 닫고 <b>종료 표식까지</b> 찍는다. 남은 축을 두면 리포트가 미도달을
	 * 못 그리고, 표식이 없으면 리포트 자체가 만들어지지 않는다.
	 */
	@Test
	void 문제를_접으면_종료로_확정한다() {
		when(repository.findStages(SESSION_ID)).thenReturn(List.of(stage(), nextProblemStage()));
		GradingInput input = new GradingInput(head("IN_PROGRESS", "INITIAL", null), List.of(problem()),
				stageWithHints(2), AnswerSlot.SECOND_HINT);

		store.applyGrading(input, result(1, false, cursorAt("L2")), "답변");

		verify(repository).closeProblem(SESSION_ID, PROBLEM_ID, JdbcSessionRepository.CLOSE_HINTS_EXHAUSTED);
	}

	/**
	 * <b>AI 커서가 다른 문제로 옮겨 가는 것도 문제 종료다.</b> 이 경로를 빠뜨리면 질문에만 답하고
	 * 미달인 축이 {@code IN_PROGRESS}로 남아 그 문제의 리포트가 영영 만들어지지 않는다 —
	 * 2026-08-17 전환 이전에 실제로 그랬다.
	 */
	@Test
	void 커서가_다른_문제로_가면_이전_문제를_종료로_확정한다() {
		when(repository.findStages(SESSION_ID)).thenReturn(List.of(stage(), nextProblemStage()));
		GradingInput input = new GradingInput(head("IN_PROGRESS", "INITIAL", null), List.of(problem()),
				stage(), AnswerSlot.QUESTION);

		store.applyGrading(input, result(1, false, new Cursor(OTHER_PROBLEM_ID, "L1", 0, null)), "답변");

		verify(repository).closeProblem(SESSION_ID, PROBLEM_ID, JdbcSessionRepository.CLOSE_CURSOR_MOVED);
	}

	/**
	 * 같은 문제의 다른 축으로 옮기는 것은 종료가 아니다. 여기서 닫으면 <b>진행 중인 문제가 접혀</b>
	 * 학생이 남은 축을 받지 못하고, 리포트도 도달하지 않은 축을 도달로 적는다.
	 */
	@Test
	void 같은_문제의_다른_축으로_옮기면_종료하지_않는다() {
		GradingInput input = new GradingInput(head("IN_PROGRESS", "INITIAL", null), List.of(problem()),
				stage(), AnswerSlot.QUESTION);

		store.applyGrading(input, result(1, false, cursorAt("L2")), "답변");

		verify(repository, never()).closeProblem(any(), any(), anyString());
	}

	/** 마지막 문제에서 접히면 갈 곳이 없다 — 세션을 닫는다. */
	@Test
	void 마지막_문제에서_접히면_세션을_닫는다() {
		when(repository.findStages(SESSION_ID)).thenReturn(List.of(stage()));
		GradingInput input = new GradingInput(head("IN_PROGRESS", "INITIAL", null), List.of(problem()),
				stageWithHints(2), AnswerSlot.SECOND_HINT);

		AnswerSubmitResponse response = store.applyGrading(input, result(1, false, cursorAt("L2")), "답변");

		assertThat(response.outcome()).isEqualTo("SESSION_ENDED");
		verify(repository).end(eq(SESSION_ID), eq("ALL_PROBLEMS_TERMINAL"), any());
	}

	@Test
	void 두번째_힌트까지_쓰고_미달이면_NOT_PASSED다() {
		GradingInput input = input(AnswerSlot.SECOND_HINT);

		store.applyGrading(input, result(2, false, cursorAt("L2")), "답변");

		verify(repository).applyAnswer(any(), eq(AnswerSlot.SECOND_HINT), anyString(),
				org.mockito.ArgumentMatchers.anyInt(), eq(false), eq("NOT_PASSED"), anyLong());
	}

	/**
	 * 3점 미만이면 힌트를 <b>자동으로</b> 연다. 학생이 `다시 설명해 주세요`를 누르기를 기다리지 않는다.
	 *
	 * <p>표시 시각을 함께 남기는 것이 핵심이다 — 문구만 응답에 실으면 새로고침 복귀 때 hintsUsed가
	 * 0으로 되돌아가 학생이 힌트를 세 번, 네 번 쓴다. row_version은 방금의 applyAnswer가 1 올렸다.
	 */
	@Test
	void 미달이면_다음_힌트를_자동으로_연다() {
		when(repository.openHint(any(), any(), anyLong())).thenReturn(1);
		GradingInput input = input(AnswerSlot.QUESTION);

		AnswerSubmitResponse response = store.applyGrading(input, result(1, false, cursorAt("L1")), "답변");

		verify(repository).openHint(STAGE_ID, AnswerSlot.FIRST_HINT, 4L);
		assertThat(response.outcome()).isEqualTo("RETRY_WITH_HINT");
		assertThat(response.hint().hintText()).isEqualTo("힌트1");
		assertThat(response.hint().hintsUsed()).isEqualTo(1);
		assertThat(response.hint().hintsLeft()).isEqualTo(1);
	}

	/** 통과했으면 더 설명할 것이 없다. 힌트를 열면 학생이 쓰지도 않은 횟수를 잃는다. */
	@Test
	void 통과하면_힌트를_열지_않는다() {
		AnswerSubmitResponse response = store.applyGrading(input(AnswerSlot.QUESTION),
				result(5, true, cursorAt("L2")), "답변");

		verify(repository, never()).openHint(any(), any(), anyLong());
		assertThat(response.hint()).isNull();
		assertThat(response.outcome()).isNotEqualTo("RETRY_WITH_HINT");
	}

	/** 힌트를 다 썼으면 미달이어도 열 것이 없다 — 그 질문은 NOT_PASSED로 닫힌다. */
	@Test
	void 힌트를_다_썼으면_미달이어도_열지_않는다() {
		GradingInput input = input(AnswerSlot.SECOND_HINT);

		AnswerSubmitResponse response = store.applyGrading(input, result(1, false, cursorAt("L1")), "답변");

		verify(repository, never()).openHint(any(), any(), anyLong());
		assertThat(response.hint()).isNull();
	}

	/**
	 * 커서가 다른 자리로 옮겨 갔으면 AI가 "이 질문은 여기까지"라고 판정한 것이다. 닫힌 질문에
	 * 힌트를 붙이면 화면이 다음 질문 옆에 이전 질문의 힌트를 그린다.
	 */
	@Test
	void 커서가_옮겨_갔으면_힌트를_열지_않는다() {
		GradingInput input = input(AnswerSlot.QUESTION);

		AnswerSubmitResponse response = store.applyGrading(input, result(1, false, cursorAt("L2")), "답변");

		verify(repository, never()).openHint(any(), any(), anyLong());
		assertThat(response.hint()).isNull();
		assertThat(response.outcome()).isEqualTo("NEXT_TURN");
	}

	/** 다시 보기는 힌트가 없다(정의서 §6+). 미달이어도 자동으로 열리지 않는다. */
	@Test
	void 다시_보기에서는_자동_힌트도_열리지_않는다() {
		GradingInput input = input(AnswerSlot.QUESTION, "REVIEW");

		AnswerSubmitResponse response = store.applyGrading(input, result(1, false, cursorAt("L1")), "답변");

		verify(repository, never()).openHint(any(), any(), anyLong());
		assertThat(response.hint()).isNull();
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
	 * 바로 위 두 테스트는 저장소가 목이라 <b>트랜잭션 속성을 검사하지 못한다.</b> 읽기만 하는 것처럼
	 * 보인다고 {@code readOnly=true}를 붙이면, Hibernate가 커넥션에 {@code setReadOnly(true)}를 걸어
	 * PostgreSQL이 상한 초과 마감 UPDATE를 {@code 25006}으로 거절한다 — 목 테스트는 전부 통과한 채
	 * 운영에서만 500이 나고 세션이 열린 채 남는다. 그래서 애너테이션 자체를 고정한다.
	 */
	@Test
	void 채점_직전_읽기는_읽기_전용_트랜잭션이_아니다() throws Exception {
		Transactional transactional = SessionTurnStore.class
				.getMethod("loadForGrading", UUID.class, UUID.class, String.class)
				.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.readOnly()).isFalse();
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

	/**
	 * 다음 문제 번호는 <b>우리 문제 목록</b>에서 나온다. AI의 {@code progress.problemIndex}는 우리가 보낸
	 * 목록 안의 순서일 뿐이라 화면이 그 값으로 문제를 열면 없는 번호를 부를 수 있다.
	 *
	 * <p>근거를 못 찾은 개념({@code NOT_GENERATED})이 있으면 원본 번호에 빈틈이 생기고, 세션 API는
	 * 생성된 문제만 1부터 다시 센 번호를 쓴다. 여기서는 AI가 엉뚱한 인덱스를 줘도 우리 번호가 나가는지를
	 * 본다 — 이 값이 틀리면 화면이 곧바로 {@code PROBLEM_NOT_FOUND}를 받는다.
	 */
	@Test
	void 다음_문제_번호는_AI_인덱스가_아니라_우리_목록에서_나온다() {
		when(repository.findStages(SESSION_ID)).thenReturn(List.of(stage(), nextProblemStage()));
		GradingInput input = new GradingInput(head("IN_PROGRESS", "INITIAL", null),
				List.of(problem(), secondProblem()), stage(), AnswerSlot.QUESTION);

		AnswerSubmitResponse response = store.applyGrading(input,
				new AnswerResult(SESSION_ID, "IN_PROGRESS", turn(5, true),
						new Cursor(OTHER_PROBLEM_ID, "L1", 0, null),
						new Question(OTHER_PROBLEM_ID, "L1", 1, "2번 문제 질문", null, 0),
						// AI가 준 인덱스는 일부러 어긋나게 둔다.
						new Progress(7, 2), null, null, List.of()),
				"답변");

		assertThat(response.nextProblemNo()).isEqualTo(2);
	}

	/**
	 * AI가 우리 행과 대조되지 않는 {@code problemId}를 주면 번호를 만들어 내지 않는다 — 화면은
	 * {@code GET /current}로 커서를 다시 읽는다. 추측한 번호를 보내면 다른 문제를 열게 된다.
	 */
	@Test
	void 대조되지_않는_문제에는_다음_번호를_만들지_않는다() {
		GradingInput input = new GradingInput(head("IN_PROGRESS", "INITIAL", null), List.of(problem()),
				stage(), AnswerSlot.QUESTION);

		AnswerSubmitResponse response = store.applyGrading(input,
				new AnswerResult(SESSION_ID, "IN_PROGRESS", turn(5, true), cursorAt("L2"),
						new Question(UUID.randomUUID(), "L1", 1, "모르는 문제", null, 0),
						new Progress(0, 2), null, null, List.of()),
				"답변");

		assertThat(response.nextProblemNo()).isNull();
	}

	// ── 픽스처 ──

	private GradingInput input(AnswerSlot slot) {
		return input(slot, "INITIAL");
	}

	/**
	 * 슬롯과 단계를 <b>짝이 맞게</b> 만든다. 운영에서는 답변 슬롯이 {@code ofHintsUsed(hintsUsed)}로
	 * 계산되므로 SECOND_HINT 슬롯이면 표시 시각 두 개가 반드시 차 있다 — 픽스처가 이 관계를 깨면
	 * 자동 힌트 판정이 실제와 다른 조건으로 시험된다.
	 */
	private GradingInput input(AnswerSlot slot, String attemptType) {
		return new GradingInput(head("IN_PROGRESS", attemptType, null), List.of(),
				stageWithHints(slot.hintsUsed()), slot);
	}

	/** 다음 문제(problemNo=2)의 첫 축. 문제가 접혔을 때 커서가 옮겨 갈 자리다. */
	private static SessionStage nextProblemStage() {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(UUID.randomUUID(), OTHER_PROBLEM_ID, 2, "L1", 1, "2번 문제 질문",
				"힌트1", "힌트2", "PREPARED", empty, empty, empty, null, null, 0L);
	}

	private static SessionStage stageWithHints(int hintsUsed) {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(STAGE_ID, PROBLEM_ID, 1, "L1", 1, "질문", "힌트1", "힌트2", "IN_PROGRESS",
				empty, empty, empty,
				hintsUsed >= 1 ? Instant.now() : null,
				hintsUsed >= 2 ? Instant.now() : null,
				3L);
	}

	/** 축별 하이라이트 두 벌을 단 문제. L4에는 일부러 없다. */
	private static SessionProblem problem() {
		return new SessionProblem(PROBLEM_ID, 1, "Graph 구성", "DESIGN_CHOICE", null, null, null,
				"snippet-1", "python", "graph.py", 1, 60, "hash", 1, "코드 전체", "content-hash",
				List.of(new SessionProblemReference("QUESTION_HIGHLIGHT", 1, "graph.py", 5, 8, "L1", null, "h1"),
						new SessionProblemReference("QUESTION_HIGHLIGHT", 2, "graph.py", 39, 41, "L2", null, "h2")),
				List.of(stage(), nextStage()));
	}

	/**
	 * 두 번째 문제. <b>표시 번호가 2</b>다 — 원본 {@code problem_no}가 3이어도(1번이 NOT_GENERATED)
	 * 세션 API는 생성된 문제만 1부터 세므로 목록에는 이 값이 담긴다.
	 */
	private static SessionProblem secondProblem() {
		return new SessionProblem(OTHER_PROBLEM_ID, 2, "Loader 구성", "DESIGN_CHOICE", null, null, null,
				"snippet-2", "python", "loader.py", 1, 40, "hash2", 1, "코드 전체", "content-hash-2",
				List.of(), List.of(nextProblemStage()));
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
