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
import com.bigproject.backend.domain.assessment.presentation.dto.SessionActivityRequest;
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
	 * 재진술은 <b>답하기 전에</b> 여는 것이다. 화면의 `다시 설명해 주세요`는 답변란이 비어 있어도
	 * `2번 남음`과 함께 활성이고, 질문을 이해하지 못했을 때 누른다 — 채점 미달의 결과가 아니다.
	 */
	@Test
	void 답하기_전에도_재진술을_열_수_있다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(0)));
		when(repository.openHint(any(), any(), anyLong())).thenReturn(1);

		HintResponse response = service.openHint(USER_ID, SESSION_ID);

		assertThat(response.hintText()).isEqualTo("힌트1");
		verify(repository).openHint(STAGE_ID, AnswerSlot.FIRST_HINT, 3L);
	}

	/**
	 * 미달로 답한 뒤에도 열린다 — 그 자리가 바로 힌트를 보고 다시 답하는 자리다. 자동으로 이미
	 * 열렸다면 {@code hintsUsed}가 올라가 있어 이 경로는 두 번째 힌트를 준다.
	 */
	@Test
	void 미달로_답한_뒤에도_재진술을_열_수_있다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(0, failed())));
		when(repository.openHint(any(), any(), anyLong())).thenReturn(1);

		HintResponse response = service.openHint(USER_ID, SESSION_ID);

		assertThat(response.hintText()).isEqualTo("힌트1");
		verify(repository).openHint(STAGE_ID, AnswerSlot.FIRST_HINT, 3L);
	}

	/** 통과한 질문에는 더 설명할 것이 없다. 힌트가 남아 있어도 열지 않는다. */
	@Test
	void 끝난_질문에는_재진술을_열_수_없다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(passedStage()));

		assertThatThrownBy(() -> service.openHint(USER_ID, SESSION_ID))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.HINT_NOT_AVAILABLE);
		verify(repository, never()).openHint(any(), any(), anyLong());
	}

	/** 한 번 열었으면 두 번째가 남아 있다. 답변 여부와 무관하게 축당 2회다. */
	@Test
	void 재진술을_한_번_썼으면_두_번째를_준다() {
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

	/**
	 * 이탈은 <b>지금 답을 쓰고 있는 슬롯</b>에 붙는다(TR-03 §4 "이탈은 세션이 아니라 답변에 붙인다").
	 * 슬롯을 클라이언트가 지목하게 두면 이미 닫힌 단계에 기록이 붙는다.
	 */
	@Test
	void 창_이탈은_지금_답을_쓰는_슬롯에_붙는다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		// 힌트를 하나 연 상태 — 다음 답변이 들어갈 자리는 첫 힌트 슬롯이다.
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(1)));

		service.recordActivity(USER_ID, SESSION_ID, new SessionActivityRequest(42, null, 3500));

		verify(repository).recordAway(SESSION_ID, STAGE_ID, AnswerSlot.FIRST_HINT, 42);
		verify(repository).recordFirstKeystroke(STAGE_ID, AnswerSlot.FIRST_HINT, 3500);
	}

	/** 네트워크 장애는 특정 답변에 귀속시킬 성질이 아니다(DDL v08 주석). 세션 합계에만 쌓는다. */
	@Test
	void 연결_끊김은_슬롯에_나누지_않고_세션에만_쌓는다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(0)));

		service.recordActivity(USER_ID, SESSION_ID, new SessionActivityRequest(null, 8, null));

		verify(repository).recordConnectionLoss(SESSION_ID, 8);
		verify(repository, never()).recordAway(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
	}

	/** 빈 요청은 세션을 조회하기도 전에 막는다 — 받아 봐야 쓸 곳이 없고 화면 쪽 버그가 묻힌다. */
	@Test
	void 신호가_하나도_없으면_세션을_보지도_않고_거절한다() {
		assertThatThrownBy(() ->
				service.recordActivity(USER_ID, SESSION_ID, new SessionActivityRequest(null, null, null)))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.ACTIVITY_SIGNAL_REQUIRED);
		verifyNoInteractions(repository);
	}

	/**
	 * 세션을 닫은 뒤 도착한 복귀 비콘까지 받아 주면 종료 시각 이후의 이탈이 합계에 섞이고,
	 * 그 합계를 무효 응시 판정({@code EXCESSIVE_WINDOW_LEAVE})이 읽는다.
	 */
	@Test
	void 끝난_세션의_신호는_받지_않는다() {
		when(repository.findOwned(SESSION_ID, USER_ID))
				.thenReturn(Optional.of(head("INITIAL", "COMPLETED")));

		assertThatThrownBy(() ->
				service.recordActivity(USER_ID, SESSION_ID, new SessionActivityRequest(42, null, null)))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.SESSION_ALREADY_ENDED);
		verify(repository, never()).recordAway(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
	}

	// ── 픽스처 ──

	private static SessionHead head(String attemptType) {
		return head(attemptType, "IN_PROGRESS");
	}

	private static SessionHead head(String attemptType, String status) {
		return new SessionHead(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), USER_ID, UUID.randomUUID(),
				attemptType, status, PROBLEM_ID, STAGE_ID, Instant.now(), null, null, UUID.randomUUID());
	}

	/**
	 * 힌트를 {@code hintsUsed}개 연 단계. 질문 슬롯은 <b>비워 둔다</b> — 힌트는 미달 후 자동으로도,
	 * 학생이 원할 때 직접도 열리므로 "먼저 답했다"가 전제가 아니다.
	 */
	private static SessionStage stage(int hintsUsed) {
		return stage(hintsUsed, new SlotState(null, null, null, null));
	}

	/** 첫 답에 통과해 닫힌 단계. 힌트는 둘 다 남아 있지만 열 이유가 없다. */
	private static SessionStage passedStage() {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(STAGE_ID, PROBLEM_ID, 1, "L1", 1, "질문", "힌트1", "힌트2", "PASSED",
				new SlotState("답", (short) 4, true, Instant.now()), empty, empty, null, null, 3L);
	}

	private static SessionStage stage(int hintsUsed, SlotState question) {
		SlotState empty = new SlotState(null, null, null, null);
		return new SessionStage(STAGE_ID, PROBLEM_ID, 1, "L1", 1, "질문", "힌트1", "힌트2", "IN_PROGRESS",
				question, empty, empty,
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
