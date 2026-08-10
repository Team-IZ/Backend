package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SlotState;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import com.bigproject.backend.domain.assessment.presentation.dto.HintResponse;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 힌트·문제 열람 규칙을 고정한다.
 *
 * <p>둘 다 <b>화면 정의서가 명시적으로 금지한 것</b>을 서버가 실제로 막는지 보는 시험이다 —
 * 끝난 문제 다시 열기(§3)와 다시 보기에서의 힌트(§6+)는 화면만 감추면 URL로 뚫린다.
 */
class AssessmentSessionServiceTest {

	private static final UUID SESSION_ID = UUID.randomUUID();
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID PROBLEM_ID = UUID.randomUUID();
	private static final UUID OTHER_PROBLEM_ID = UUID.randomUUID();
	private static final UUID STAGE_ID = UUID.randomUUID();

	private JdbcSessionRepository repository;
	private SessionAnswerGrader grader;
	private AssessmentSessionService service;

	@BeforeEach
	void setUp() {
		repository = mock(JdbcSessionRepository.class);
		grader = mock(SessionAnswerGrader.class);
		SessionGuard guard = new SessionGuard(repository);
		service = new AssessmentSessionService(repository, guard, new SessionTurnStore(repository, guard), grader);
	}

	/** 힌트는 동결된 문구를 꺼내는 것뿐이다 — AI를 부르면 비용이 나가고 응답이 매번 달라진다. */
	@Test
	void 힌트는_AI를_부르지_않고_동결된_문구를_준다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(0)));
		when(repository.openHint(any(), any(), anyLong())).thenReturn(1);

		HintResponse response = service.openHint(USER_ID, SESSION_ID);

		assertThat(response.hintText()).isEqualTo("힌트1");
		assertThat(response.hintsUsed()).isEqualTo(1);
		assertThat(response.hintsLeft()).isEqualTo(1);
		verify(repository).openHint(STAGE_ID, AnswerSlot.FIRST_HINT, 3L);
		verifyNoInteractions(grader);
	}

	/**
	 * 힌트는 "3점 미만이면 실패이고 그때 보여준다"는 순서를 지킨다. 답하기 전에 열리면 질문 슬롯이
	 * 비어 있는 채로 힌트만 소모되고, 마지막에 {@code NOT_PASSED}를 쓸 때
	 * {@code ck_problem_stage_status_2}가 "슬롯 셋이 모두 FALSE"를 요구해 CHECK 위반으로 터진다.
	 */
	@Test
	void 답하기_전에는_힌트를_열_수_없다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStage(STAGE_ID))
				.thenReturn(Optional.of(stage(0, new SlotState(null, null, null, null))));

		assertThatThrownBy(() -> service.openHint(USER_ID, SESSION_ID))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.HINT_NOT_AVAILABLE);
		verify(repository, never()).openHint(any(), any(), anyLong());
	}

	/** 3점 이상으로 통과한 단계에는 더 설명할 것이 없다. */
	@Test
	void 통과한_단계에는_힌트를_열_수_없다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(
				stage(0, new SlotState("답", (short) 4, true, Instant.now()))));

		assertThatThrownBy(() -> service.openHint(USER_ID, SESSION_ID))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.HINT_NOT_AVAILABLE);
	}

	/** 첫 힌트 재답변도 미달이면 마지막 힌트가 열린다. */
	@Test
	void 첫_힌트_재답변도_미달이면_마지막_힌트를_준다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(1)));
		when(repository.openHint(any(), any(), anyLong())).thenReturn(1);

		HintResponse response = service.openHint(USER_ID, SESSION_ID);

		assertThat(response.hintText()).isEqualTo("힌트2");
		assertThat(response.hintsLeft()).isZero();
		verify(repository).openHint(STAGE_ID, AnswerSlot.SECOND_HINT, 3L);
	}

	@Test
	void 두번_다_쓰면_더_열어주지_않는다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(2)));

		assertThatThrownBy(() -> service.openHint(USER_ID, SESSION_ID))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.HINT_EXHAUSTED);
	}

	/** 정의서 §6+ — "지난번과 같은 질문이라 이미 한 번 들었어요". 화면 문구만으로는 URL을 막지 못한다. */
	@Test
	void 다시_보기에서는_힌트를_열_수_없다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("REVIEW")));

		assertThatThrownBy(() -> service.openHint(USER_ID, SESSION_ID))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.HINT_NOT_AVAILABLE);
	}

	/** 정의서 §3 — "끝난 문제는 다시 열 수 없다". 진행 중인 세션에서는 커서가 선 문제만 열린다. */
	@Test
	void 지금_문제가_아니면_열리지_않는다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findProblems(any(), any())).thenReturn(List.of(problem(1, OTHER_PROBLEM_ID)));

		assertThatThrownBy(() -> service.findProblem(USER_ID, SESSION_ID, 1))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.PROBLEM_ALREADY_CLOSED);
	}

	/** 세션이 끝난 뒤에는 전부 열린다 — 리포트 전에 자기가 뭘 썼는지 보는 것을 막을 이유가 없다. */
	@Test
	void 끝난_세션에서는_지난_문제도_열린다() {
		SessionHead ended = new SessionHead(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), USER_ID,
				UUID.randomUUID(), "INITIAL", "COMPLETED", PROBLEM_ID, STAGE_ID, Instant.now(), null, null,
				UUID.randomUUID());
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(ended));
		when(repository.findProblems(any(), any())).thenReturn(List.of(problem(1, OTHER_PROBLEM_ID)));

		ProblemActivityResponse response = service.findProblem(USER_ID, SESSION_ID, 1);

		assertThat(response.problemNo()).isEqualTo(1);
		assertThat(response.current()).isNull();
	}

	/**
	 * 문제가 전부 NOT_GENERATED면 세션은 READY인데 물을 것이 없다. start가 커서를 세우지 못하고
	 * 갱신 0건이 되는데, 그대로 200을 주면 <b>화면은 시작된 줄 알고 전체화면으로 넘어가고 서버는
	 * 시작되지 않은 상태로 남는다.</b> 조용히 어긋나는 대신 실패시킨다.
	 */
	@Test
	void 단계가_없으면_시작되지_않고_실패한다() {
		SessionHead ready = new SessionHead(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), USER_ID,
				UUID.randomUUID(), "INITIAL", "READY", null, null, null, null, null, UUID.randomUUID());
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(ready));
		when(repository.start(any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(0);

		assertThatThrownBy(() -> service.start(USER_ID, SESSION_ID))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.STAGE_NOT_FOUND);
	}

	/** 동시 요청으로 다른 쪽이 먼저 시작시켰으면 갱신은 0건이지만 정상이다 — 상태로 판정한다. */
	@Test
	void 이미_시작된_세션은_그대로_돌려준다() {
		SessionHead running = head("INITIAL");
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(running));
		when(repository.findStages(SESSION_ID)).thenReturn(List.of());

		assertThat(service.start(USER_ID, SESSION_ID).status()).isEqualTo("IN_PROGRESS");
		verify(repository, org.mockito.Mockito.never())
				.start(any(), org.mockito.ArgumentMatchers.anyInt(), any());
	}

	/** 남의 세션은 403이 아니라 404다 — 존재 여부를 알려줄 이유가 없다. */
	@Test
	void 남의_세션은_없는_것으로_본다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findProblem(USER_ID, SESSION_ID, 1))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.SESSION_NOT_ACCESSIBLE);
	}

	// ── 픽스처 ──

	private static SessionHead head(String attemptType) {
		return new SessionHead(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), USER_ID, UUID.randomUUID(),
				attemptType, "IN_PROGRESS", PROBLEM_ID, STAGE_ID, Instant.now(), null, null, UUID.randomUUID());
	}

	/**
	 * 힌트를 {@code hintsUsed}개 연 단계. 힌트는 <b>직전 답변이 미달일 때만</b> 열리므로 질문 슬롯은
	 * 언제나 답해서 미달인 상태이고, 힌트를 하나 더 열었다면 첫 힌트 슬롯도 그렇다.
	 */
	private static SessionStage stage(int hintsUsed) {
		return stage(hintsUsed, failed());
	}

	private static SessionStage stage(int hintsUsed, SlotState question) {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(STAGE_ID, PROBLEM_ID, 1, "L1", 1, "질문", "힌트1", "힌트2", "IN_PROGRESS",
				question,
				hintsUsed >= 1 ? failed() : empty,
				empty,
				hintsUsed >= 1 ? Instant.now() : null,
				hintsUsed >= 2 ? Instant.now() : null,
				3L);
	}

	private static SlotState failed() {
		return new SlotState("답", (short) 1, false, Instant.now());
	}

	private static SessionProblem problem(int problemNo, UUID problemId) {
		SlotState empty = new SlotState(null, null, null, null);
		SessionStage stage = new SessionStage(UUID.randomUUID(), problemId, problemNo, "L1", 1, "질문",
				"힌트1", "힌트2", "PREPARED", empty, empty, empty, null, null, 0L);
		return new SessionProblem(problemId, problemNo, "제목", "DESIGN_CHOICE", null, null, null,
				"key", "PYTHON", "nodes.py", 12, 20, "hash", 1, "code", "contentHash",
				List.of(), List.of(stage));
	}
}
