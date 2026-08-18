package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SlotState;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import com.bigproject.backend.global.ai.AsyncAiProxyWarmUp;
import com.bigproject.backend.domain.assessment.presentation.dto.HintResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.ProblemActivityResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.SessionActivityRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
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
	private static final int PROBLEM_TIME_LIMIT_MINUTES = 20;

	private JdbcSessionRepository repository;
	private SessionAnswerGrader grader;
	private SessionExpirer expirer;
	private AssessmentSessionService service;

	@BeforeEach
	void setUp() {
		repository = mock(JdbcSessionRepository.class);
		grader = mock(SessionAnswerGrader.class);
		// 상한 정리는 독립 트랜잭션이라 별도 빈이다. 목으로 두면 "닫았는가"를 호출로 확인할 수 있다.
		expirer = mock(SessionExpirer.class);
		SessionGuard guard = new SessionGuard(repository, expirer);
		ReflectionTestUtils.setField(guard, "problemTimeLimitMinutes", PROBLEM_TIME_LIMIT_MINUTES);
		service = new AssessmentSessionService(repository, guard, new SessionTurnStore(repository, guard), grader,
				mock(AsyncAiProxyWarmUp.class));
		ReflectionTestUtils.setField(service, "problemTimeLimitMinutes", PROBLEM_TIME_LIMIT_MINUTES);
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

	/**
	 * 다시 보기도 1차와 <b>똑같이</b> 힌트를 준다(37차 R2).
	 *
	 * <p>막아 두면 {@code *_hint_presented_at}이 안 찍히고, 그러면 {@code hintsUsed()}가 항상 0이라
	 * {@code nextSlot()}이 영원히 {@code QUESTION}에 머문다 — 미달한 축에서 재제출이
	 * {@code ANSWER_ALREADY_SUBMITTED}로 막히고 축도 닫히지 않아 세션이 그 자리에 갇혔다.
	 * 이 시험은 그 교착이 다시 생기지 않는지를 지킨다.
	 */
	@Test
	void 다시_보기에서도_힌트가_열린다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("REVIEW")));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(0)));
		when(repository.openHint(any(), any(), anyLong())).thenReturn(1);

		HintResponse response = service.openHint(USER_ID, SESSION_ID);

		assertThat(response.hintText()).isEqualTo("힌트1");
		assertThat(response.hintsUsed()).isEqualTo(1);
		assertThat(response.hintsLeft()).isEqualTo(1);
		verify(repository).openHint(STAGE_ID, AnswerSlot.FIRST_HINT, 3L);
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
		verify(repository).recordFirstKeystroke(SESSION_ID, STAGE_ID, AnswerSlot.FIRST_HINT, 3500);
	}

	/** 네트워크 장애는 특정 답변에 귀속시킬 성질이 아니다(DDL v08 주석). 세션 합계에만 쌓는다. */
	@Test
	void 연결_끊김은_슬롯에_나누지_않고_세션에만_쌓는다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(0)));

		service.recordActivity(USER_ID, SESSION_ID, new SessionActivityRequest(null, 8, null));

		verify(repository).recordConnectionLoss(SESSION_ID, STAGE_ID, 8);
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

	// ── 시간 상한 ──

	/**
	 * 상한을 넘긴 세션은 <b>조회만 해도</b> 닫힌다.
	 *
	 * <p>종전에는 쓰기 요청만 상한을 봤다. 그래서 학생이 제출하지 않고 새로고침만 하면
	 * {@code GET /current}가 끝났어야 할 세션을 계속 진행 중으로 돌려줬다 — 남은 시간이 음수인 화면이
	 * 그려지고, 첫 제출에서야 409로 끊겼다(2026-08-15 실측).
	 */
	@Test
	void 상한을_넘긴_세션은_조회만_해도_닫히고_내려가지_않는다() {
		SessionHead timedOut = timedOutHead();
		// 닫힌 뒤 다시 고르면 이어서 할 세션이 없다 — findCurrent가 살아 있는 상태만 뽑기 때문이다.
		when(repository.findCurrent(USER_ID)).thenReturn(Optional.of(timedOut), Optional.empty());

		assertThat(service.findCurrent(USER_ID)).isEmpty();
		verify(expirer).closeTimedOutSession(SESSION_ID);
	}

	/**
	 * 문제 상한은 세션을 닫지 않고 <b>다음 문제로 커서를 옮긴다.</b> 조회 경로도 같은 정리를 하므로
	 * 새로고침만으로 다음 문제가 나온다.
	 */
	@Test
	void 문제_상한을_넘기면_조회에서_다음_문제로_옮긴다() {
		SessionHead stuck = problemTimedOutHead();
		SessionHead moved = head("INITIAL");
		when(repository.findCurrent(USER_ID)).thenReturn(Optional.of(stuck), Optional.of(moved));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(0)));
		when(repository.findStages(SESSION_ID)).thenReturn(List.of(stage(0)));

		assertThat(service.findCurrent(USER_ID)).isPresent();
		verify(expirer).expireTimedOutProblem(SESSION_ID, PROBLEM_ID, 1, false);
		verify(expirer, never()).closeTimedOutSession(any());
	}

	/**
	 * 🔴 닫기가 <b>독립 트랜잭션</b>이어야 하는 이유를 고정한다.
	 *
	 * <p>가드가 저장소를 직접 부르면 뒤이어 던지는 {@code SessionException}에 그 쓰기가 함께 롤백된다.
	 * 그래서 409는 받는데 세션은 열린 채 남고 커서도 그대로였다 — 학생이 그 문제에 영원히 갇혔다.
	 * {@link SessionExpirer}를 거치는 한 롤백돼도 닫기는 살아남는다.
	 */
	@Test
	void 상한_초과_쓰기는_저장소를_직접_부르지_않는다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(timedOutHead()));

		assertThatThrownBy(() ->
				service.recordActivity(USER_ID, SESSION_ID, new SessionActivityRequest(42, null, null)))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.SESSION_TIMEOUT);

		verify(expirer).closeTimedOutSession(SESSION_ID);
		verify(repository, never()).end(any(), any(), any());
	}

	/** 문제 상한도 같다 — 커서 이동이 롤백되면 다음 문제로 갈 방법이 없어진다. */
	@Test
	void 문제_상한_초과_쓰기도_저장소를_직접_부르지_않는다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(problemTimedOutHead()));
		when(repository.findStage(STAGE_ID)).thenReturn(Optional.of(stage(0)));

		assertThatThrownBy(() ->
				service.recordActivity(USER_ID, SESSION_ID, new SessionActivityRequest(42, null, null)))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.PROBLEM_TIME_LIMIT_EXCEEDED);

		verify(expirer).expireTimedOutProblem(SESSION_ID, PROBLEM_ID, 1, false);
		verify(repository, never()).expireCurrentProblem(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.anyBoolean());
	}

	/** 상한 안이면 아무것도 닫지 않는다. 정리 로직이 정상 세션을 건드리면 시험이 사라진다. */
	@Test
	void 상한_안의_세션은_그대로_내려간다() {
		when(repository.findCurrent(USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStages(SESSION_ID)).thenReturn(List.of(stage(0)));

		assertThat(service.findCurrent(USER_ID)).isPresent();
		verifyNoInteractions(expirer);
	}

	// ── 응시 창 ──

	/**
	 * 종전에는 세션 API가 응시 창을 <b>아예 읽지 않아서</b> 개인 창이 닫힌 뒤에도 시작이 통과했다.
	 * 홈 카드는 {@code ASSESSMENT_WINDOW_CLOSED}에 CTA {@code NONE}을 그리는데 API는 받아 주던
	 * 상태다(28차 P2). 창이 닫힌 계정으로 TR-03을 열면 바로 만나는 구멍이라 여기서 고정한다.
	 */
	@Test
	void 개인_응시_창이_닫히면_시작할_수_없다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(windowClosedHead()));

		assertThatThrownBy(() -> service.start(USER_ID, SESSION_ID))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.ASSESSMENT_WINDOW_CLOSED);

		verify(repository, never()).start(any(), org.mockito.ArgumentMatchers.anyInt(), any());
	}

	/**
	 * 힌트도 같은 자리에서 막힌다 — {@code running()}이 {@code live()}를 지나기 때문이다.
	 * 시간 상한보다 <b>먼저</b> 걸리므로 세션을 닫지도 않는다.
	 */
	@Test
	void 개인_응시_창이_닫히면_힌트도_받지_않는다() {
		when(repository.findOwned(SESSION_ID, USER_ID))
				.thenReturn(Optional.of(windowClosedHead("IN_PROGRESS")));

		assertThatThrownBy(() -> service.openHint(USER_ID, SESSION_ID))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.ASSESSMENT_WINDOW_CLOSED);

		verifyNoInteractions(expirer);
	}

	/**
	 * 다시 보기는 코드가 갈린다. 학생이 할 수 있는 일이 달라서다 — 응시 창은 매니저에게 문의할
	 * 여지가 있고, 다시 보기는 회차당 한 번뿐이라 마감이 지나면 그것으로 끝이다.
	 */
	@Test
	void 다시_보기_마감이_지나면_다른_코드로_막는다() {
		SessionHead review = new SessionHead(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), USER_ID,
				UUID.randomUUID(), "REVIEW", "READY", null, null, null, null,
				Instant.now().minus(Duration.ofMinutes(1)), UUID.randomUUID(), null, null);
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(review));

		assertThatThrownBy(() -> service.start(USER_ID, SESSION_ID))
				.isInstanceOf(SessionException.class)
				.extracting(exception -> ((SessionException) exception).getErrorCode())
				.isEqualTo(SessionErrorCode.REVIEW_DUE_AT_PASSED);
	}

	/** 마감 컬럼이 비어 있으면(창이 아직 정해지지 않았다) 막지 않는다 — 없는 규칙을 만들지 않는다. */
	@Test
	void 마감이_없으면_막지_않는다() {
		when(repository.findOwned(SESSION_ID, USER_ID)).thenReturn(Optional.of(head("INITIAL")));
		when(repository.findStages(SESSION_ID)).thenReturn(List.of());

		assertThat(service.start(USER_ID, SESSION_ID).status()).isEqualTo("IN_PROGRESS");
	}

	/**
	 * 창이 닫혀도 <b>조회는 열어 둔다.</b> {@code owned()}는 소유권만 보는 자리라, 마감이 지났다고
	 * 여기까지 막으면 학생이 자기가 푼 것을 다시 볼 수 없다.
	 */
	@Test
	void 창이_닫혀도_지난_문제_조회는_막지_않는다() {
		when(repository.findOwned(SESSION_ID, USER_ID))
				.thenReturn(Optional.of(windowClosedHead("COMPLETED")));
		when(repository.findProblems(any(), any())).thenReturn(List.of(problem(1, OTHER_PROBLEM_ID)));

		assertThat(service.findProblem(USER_ID, SESSION_ID, 1).problemNo()).isEqualTo(1);
	}

	// ── 픽스처 ──

	/** 개인 응시 창이 1분 전에 닫힌 머리. */
	private static SessionHead windowClosedHead() {
		return windowClosedHead("READY");
	}

	private static SessionHead windowClosedHead(String status) {
		return new SessionHead(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), USER_ID,
				UUID.randomUUID(), "INITIAL", status, PROBLEM_ID, STAGE_ID,
				"READY".equals(status) ? null : Instant.now().minus(Duration.ofMinutes(30)),
				null, null, UUID.randomUUID(), null,
				Instant.now().minus(Duration.ofMinutes(1)));
	}

	/** 세션 상한(timeLimitAt)을 1분 넘긴 머리. */
	private static SessionHead timedOutHead() {
		return new SessionHead(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), USER_ID, UUID.randomUUID(),
				"INITIAL", "IN_PROGRESS", PROBLEM_ID, STAGE_ID,
				Instant.now().minus(Duration.ofMinutes(61)), Instant.now().minus(Duration.ofMinutes(1)),
				null, UUID.randomUUID(), Instant.now().minus(Duration.ofMinutes(5)));
	}

	/** 세션 상한은 남았지만 지금 문제가 20분을 넘긴 머리. */
	private static SessionHead problemTimedOutHead() {
		return new SessionHead(SESSION_ID, UUID.randomUUID(), UUID.randomUUID(), USER_ID, UUID.randomUUID(),
				"INITIAL", "IN_PROGRESS", PROBLEM_ID, STAGE_ID,
				Instant.now().minus(Duration.ofMinutes(30)), Instant.now().plus(Duration.ofMinutes(30)),
				null, UUID.randomUUID(),
				Instant.now().minus(Duration.ofMinutes(PROBLEM_TIME_LIMIT_MINUTES + 1)));
	}

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
