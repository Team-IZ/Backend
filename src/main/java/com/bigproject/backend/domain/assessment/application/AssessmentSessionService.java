package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.AnswerResult;
import com.bigproject.backend.domain.assessment.application.SessionTurnStore.GradingInput;
import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SlotState;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import com.bigproject.backend.domain.assessment.presentation.dto.AnswerSubmitRequest;
import com.bigproject.backend.domain.assessment.presentation.dto.AnswerSubmitResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.HintResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.ProblemActivityResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.SessionActivityRequest;
import com.bigproject.backend.domain.assessment.presentation.dto.SessionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 검증 세션(TR-03) 다섯 경로의 업무 규칙.
 *
 * <h2>왜 답변 제출에 세션 ID만 받는가</h2>
 *
 * <p>질문·문제·커서를 클라이언트가 실어 보내게 하면 <b>학생이 어느 질문에 답하는지를 학생이 정하게
 * 된다.</b> 계단을 건너뛰거나 이미 닫힌 문제에 답을 붙이는 요청이 만들어지고, 서버는 그것이 진짜
 * 화면 상태인지 알 방법이 없다. 진행 위치는 {@code assessment_session}의 커서가 정본이고 요청은
 * 답변 원문만 싣는다.
 *
 * <h2>힌트가 별도 경로인 이유</h2>
 *
 * <p>한 경로가 "AI를 타는 채점"과 "DB만 읽는 힌트 열기"를 겸하면 응답 형태·지연·실패 모드가 요청 본문에
 * 따라 갈린다. 채점은 몇 초가 걸리고 실패하면 재전송을 요구하지만, 힌트는 즉답이고 몇 번을 눌러도 같다.
 */
@Service
@RequiredArgsConstructor
public class AssessmentSessionService {

	/**
	 * 인트로 고지 버전. 문구를 바꾸면 올린다 — 무효 응시 검토에서 "그때 무엇을 고지받았나"를
	 * 이 번호로 되짚는다.
	 */
	private static final int INTRO_NOTICE_VERSION = 1;

	private final JdbcSessionRepository repository;
	private final SessionGuard guard;
	private final SessionTurnStore turnStore;
	private final SessionAnswerGrader grader;

	/** 정책 시간 상한(분). 정의서 §2의 하드 상한 70분이 기본값이다. */
	@Value("${session.time-limit-minutes:70}")
	private int timeLimitMinutes;

	/**
	 * 지금 이어서 할 세션. 없으면 비어 있다 — 화면은 "진행 중인 회차 없음"으로 그린다.
	 *
	 * <p>새로고침·재접속 복귀가 이 하나로 해결된다. 진행 중인 세션을 먼저 고르므로 학생이 다시 들어오면
	 * 커서가 서 있던 자리가 그대로 나온다.
	 */
	@Transactional(readOnly = true)
	public Optional<SessionResponse> findCurrent(UUID userId) {
		return repository.findCurrent(userId)
				.map(head -> SessionResponse.of(head, repository.findStages(head.sessionId())));
	}

	/**
	 * 인트로 동의와 함께 세션을 연다. 이미 진행 중이면 그대로 돌려준다 — 새로고침 후 다시 눌러도
	 * 커서가 처음으로 돌아가지 않아야 한다.
	 */
	@Transactional
	public SessionResponse start(UUID userId, UUID sessionId) {
		SessionHead head = guard.live(userId, sessionId);
		if ("READY".equals(head.status())) {
			repository.start(sessionId, INTRO_NOTICE_VERSION,
					Instant.now().plus(Duration.ofMinutes(timeLimitMinutes)));
		}
		SessionHead started = guard.owned(userId, sessionId);

		// 갱신 건수가 아니라 "실제로 시작됐는가"를 다시 읽어 확인한다. 두 가지를 함께 걸러야 해서다.
		//   · 단계가 한 건도 없는 세션 — 문제 3개가 전부 NOT_GENERATED면 READY인데 물을 것이 없다.
		//     start의 UPDATE가 단계 하나를 찾아 커서를 세우므로 이때 0건이 되고, 그대로 두면
		//     화면은 200을 받고 전체화면으로 넘어가지만 서버는 시작되지 않은 상태로 남는다.
		//   · 동시 요청 — 다른 요청이 먼저 시작시켰으면 이쪽 UPDATE도 0건이지만 그건 정상이다.
		// 갱신 건수로 판정하면 뒤엣것을 오류로 만든다. 최종 상태로 판정하면 둘이 정확히 갈린다.
		if (!"IN_PROGRESS".equals(started.status())) {
			throw new SessionException(SessionErrorCode.STAGE_NOT_FOUND);
		}
		return SessionResponse.of(started, repository.findStages(sessionId));
	}

	/**
	 * 문제 하나의 활동(코드·질문·지금까지의 문답).
	 *
	 * <p><b>지금 문제만 열어 준다.</b> 정의서 §3 — 끝난 문제를 다시 열면 지금 문제와 무관한 데 시간을
	 * 쓰고 "아까 그거 틀린 것 같은데"만 남는다. 아직 시작하지 않은 뒤 문제도 같은 이유로 막는다.
	 */
	@Transactional(readOnly = true)
	public ProblemActivityResponse findProblem(UUID userId, UUID sessionId, int problemNo) {
		SessionHead head = guard.owned(userId, sessionId);
		List<SessionProblem> problems = repository.findProblems(sessionId, head.sourceSubmissionId());
		SessionProblem problem = problems.stream()
				.filter(candidate -> candidate.problemNo() == problemNo)
				.findFirst()
				.orElseThrow(() -> new SessionException(SessionErrorCode.PROBLEM_NOT_FOUND));

		boolean isCurrent = head.currentProblemId() != null
				&& head.currentProblemId().equals(problem.problemId());
		if (!isCurrent && !head.isEnded()) {
			throw new SessionException(SessionErrorCode.PROBLEM_ALREADY_CLOSED);
		}
		return ProblemActivityResponse.of(head, problem, problems.size());
	}

	/**
	 * 힌트를 연다. <b>AI를 부르지 않는다</b> — 힌트 문구는 분석 시점에 {@code problem_stage}에
	 * 동결돼 있고 세션은 그것을 꺼내 보여줄 뿐이다("힌트는 재진술만").
	 *
	 * <p>표시 시각을 남기는 것이 이 경로의 존재 이유다. 그 값이 없으면 힌트를 열어 둔 채 새로고침했을 때
	 * {@code hintsUsed}가 0으로 되돌아가 학생이 힌트를 세 번, 네 번 쓰게 된다.
	 */
	@Transactional
	public HintResponse openHint(UUID userId, UUID sessionId) {
		SessionHead head = guard.running(userId, sessionId);
		if (head.isReview()) {
			// 정의서 §6+ — "이번에는 다시 설명해 드리지 않아요. 지난번과 같은 질문이라 이미 한 번 들었어요."
			throw new SessionException(SessionErrorCode.HINT_NOT_AVAILABLE);
		}
		SessionStage stage = guard.currentStage(head);

		int hintsUsed = stage.hintsUsed();
		if (hintsUsed >= 2) {
			throw new SessionException(SessionErrorCode.HINT_EXHAUSTED);
		}

		// 힌트는 <b>직전 답변이 채점되어 미달일 때만</b> 열린다(정의서: 3점 미만이면 실패이고 그때 힌트를
		// 보여준다). 통과한 단계를 막는 것만으로는 부족하다 — 아직 답하지 않은 슬롯은 passed 가 NULL 이라
		// "통과 아님"으로 통과해 버리고, 그러면 학생이 질문에 답하기도 전에 힌트를 두 번 열 수 있다.
		//
		// 그렇게 건너뛴 슬롯은 영영 NULL 로 남는데, 마지막 힌트까지 미달일 때 쓰는 NOT_PASSED 는
		// ck_problem_stage_status_2 가 "슬롯 셋이 모두 FALSE"를 요구한다. 즉 사고는 힌트를 열 때가 아니라
		// 30분 뒤 마지막 제출에서 CHECK 위반 500 으로 터진다. 여기서 순서를 강제해야 그 자리가 생기지 않는다.
		SlotState answered = stage.slot(AnswerSlot.ofHintsUsed(hintsUsed));
		if (!answered.isAnswered() || Boolean.TRUE.equals(answered.passed())) {
			throw new SessionException(SessionErrorCode.HINT_NOT_AVAILABLE);
		}

		AnswerSlot opening = AnswerSlot.ofHintsUsed(hintsUsed + 1);
		if (repository.openHint(stage.problemStageId(), opening, stage.rowVersion()) == 0) {
			throw new SessionException(SessionErrorCode.ANSWER_ALREADY_SUBMITTED);
		}
		String hintText = opening == AnswerSlot.FIRST_HINT ? stage.firstHintText() : stage.secondHintText();
		return new HintResponse(stage.problemId(), stage.axisCode(), hintText,
				hintsUsed + 1, 2 - (hintsUsed + 1));
	}

	/**
	 * 응시 중 관찰 신호를 남긴다. AI를 부르지 않고 진행 상태도 바꾸지 않는다 — 오직 기록이다.
	 *
	 * <p><b>왜 별도 경로인가.</b> 이탈은 답변 제출과 짝이 맞지 않는다. 학생은 답을 쓰지 않고도 창을
	 * 열 번 드나들 수 있고, 그 답변이 영영 제출되지 않을 수도 있다. 제출에 실어 보내면 그때 전부
	 * 사라진다 — 정작 의심스러운 응시일수록 기록이 안 남는다.
	 *
	 * <p><b>귀속 자리는 서버 커서가 정한다.</b> 창 이탈과 첫 타이핑 지연은 지금 답을 쓰고 있는
	 * 슬롯({@link SessionStage#nextSlot()})에 붙는다. 화면 정의서 TR-03 §4의 "이탈은 세션이 아니라
	 * 답변에 붙인다"가 이것이고, 세션 합계는 무효 응시 판정이 따로 보므로 함께 올린다.
	 *
	 * <p>다시 보기(REVIEW)도 막지 않는다. 판정에 반영되지 않을 뿐 매니저 브리프는 같은 값을 읽는다.
	 */
	@Transactional
	public void recordActivity(UUID userId, UUID sessionId, SessionActivityRequest request) {
		if (request.isEmpty()) {
			throw new SessionException(SessionErrorCode.ACTIVITY_SIGNAL_REQUIRED);
		}
		SessionHead head = guard.running(userId, sessionId);
		SessionStage stage = guard.currentStage(head);
		AnswerSlot slot = stage.nextSlot();

		if (request.awaySeconds() != null) {
			repository.recordAway(sessionId, stage.problemStageId(), slot, request.awaySeconds());
		}
		if (request.disconnectedSeconds() != null) {
			repository.recordConnectionLoss(sessionId, request.disconnectedSeconds());
		}
		if (request.firstKeystrokeDelayMs() != null) {
			repository.recordFirstKeystroke(stage.problemStageId(), slot, request.firstKeystrokeDelayMs());
		}
	}

	/**
	 * 답변을 제출하고 채점 결과로 다음 자리를 정한다.
	 *
	 * <p>세 구간이다 — ① 읽기(트랜잭션) ② 채점(트랜잭션 밖) ③ 쓰기(트랜잭션). ②가 몇 초 걸려서 나눈
	 * 것이고, 그 사이 다른 요청이 같은 자리에 답하면 ③의 낙관적 잠금이 거절한다.
	 */
	public AnswerSubmitResponse submitAnswer(UUID userId, UUID sessionId, AnswerSubmitRequest request,
			String traceId) {
		String answerText = request.answerText() == null ? null : request.answerText().trim();
		GradingInput input = turnStore.loadForGrading(userId, sessionId, answerText);
		AnswerResult result = grader.grade(input.head(), input.problems(), input.stage(), input.slot(),
				answerText, traceId);
		return turnStore.applyGrading(input, result, answerText);
	}
}
