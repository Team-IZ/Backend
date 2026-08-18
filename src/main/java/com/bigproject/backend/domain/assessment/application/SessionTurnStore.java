package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.AnswerResult;
import com.bigproject.backend.domain.assessment.domain.AnswerGrade;
import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import com.bigproject.backend.domain.assessment.presentation.dto.AnswerSubmitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 답변 한 턴의 <b>읽기 전 · 쓰기 후</b> 트랜잭션. 채점(AI 호출)은 이 둘 사이에 트랜잭션 없이 일어난다.
 *
 * <p><b>{@link AssessmentSessionService}와 클래스를 나눈 이유는 트랜잭션이다.</b> 같은 클래스 안에서
 * 자기 메서드를 부르면 Spring AOP가 프록시를 거치지 않아 {@code @Transactional}이 조용히 무시된다
 * ({@code ReportRunFinalizer}가 같은 이유로 나뉘어 있다). 별도 빈이면 프록시를 탄다.
 *
 * <p>왜 굳이 나누느냐 — 채점이 4.5~7.7초다. 한 트랜잭션으로 묶으면 그동안 DB 커넥션이 잠겨
 * 동시 응시 인원만큼 풀이 마른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionTurnStore {

	private final JdbcSessionRepository repository;
	private final SessionGuard guard;

	/**
	 * 채점에 필요한 것을 한 번에 읽는다. 이 트랜잭션이 끝난 뒤 AI를 부른다.
	 *
	 * <p><b>{@code readOnly}가 아니다.</b> 하는 일은 읽기뿐이지만 {@link SessionGuard#running}이
	 * 시간 상한을 넘긴 세션을 <b>그 자리에서 닫는다</b>(세션 상한이면 {@code end}, 문제 상한이면
	 * {@code expireCurrentProblem}). {@code readOnly=true}면 Hibernate가 커넥션에
	 * {@code setReadOnly(true)}를 걸고 PostgreSQL이 그 UPDATE를 {@code 25006}으로 거절하므로,
	 * 상한을 넘긴 제출이 {@code SESSION_TIMEOUT}(409) 대신 500이 되고 세션은 열린 채 남는다 —
	 * 다음 제출도 같은 500을 받는다. 힌트 열기·활동 기록이 같은 이유로 쓰기 트랜잭션이다.
	 */
	@Transactional
	public GradingInput loadForGrading(UUID userId, UUID sessionId, String answerText) {
		if (answerText == null || answerText.isBlank()) {
			throw new SessionException(SessionErrorCode.ANSWER_TEXT_REQUIRED);
		}
		SessionHead head = guard.running(userId, sessionId);
		SessionStage stage = guard.currentStage(head);

		AnswerSlot slot = stage.nextSlot();
		if (stage.slot(slot).isAnswered()) {
			throw new SessionException(SessionErrorCode.ANSWER_ALREADY_SUBMITTED);
		}
		List<SessionProblem> problems = repository.findProblems(sessionId, head.sourceSubmissionId());
		return new GradingInput(head, problems, stage, slot);
	}

	/**
	 * 채점 결과를 확정하고 커서를 옮긴다.
	 *
	 * <p>{@code row_version}이 어긋나면 읽은 뒤 누군가 같은 자리에 답한 것이다. 덮어쓰지 않고 거절한다 —
	 * 되돌릴 수 없는 제출이라 마지막 쓰기가 이기게 두면 학생이 쓴 답이 조용히 사라진다.
	 */
	@Transactional
	public AnswerSubmitResponse applyGrading(GradingInput input, AnswerResult result, String answerText) {
		SessionStage stage = input.stage();
		AnswerGrade grade = gradeOf(result);

		// 방금 채점을 요청할 때 쓴 멱등키를 다시 만든다. 키가 결정론적이라(세션·단계·축·힌트사용수)
		// 같은 값이 나오는 것이 보장되고, 생성 규칙이 한 곳에만 있게 된다 — 값을 들고 다니면
		// 규칙이 둘로 갈릴 자리가 생긴다.
		UUID gradingRequestId = SessionAnswerGrader.idempotencyKey(
				input.head().sessionId(), stage, input.slot());

		if (repository.applyAnswer(stage.problemStageId(), input.slot(), answerText, grade.score(),
				grade.passed(), stageStatus(input.slot(), grade.passed()), gradingRequestId,
				stage.rowVersion()) == 0) {
			throw new SessionException(SessionErrorCode.ANSWER_ALREADY_SUBMITTED);
		}

		UUID sessionId = input.head().sessionId();
		if (input.slot() == AnswerSlot.SECOND_HINT && !grade.passed()) {
			return closeProblem(input, result);
		}

		if (result.cursor() != null && result.cursor().problemId() != null) {
			// 커서가 다른 문제로 넘어갔다면 방금까지 풀던 문제가 끝난 것이다. 그 자리에서 닫아
			// 종료 표식을 찍는다 — 이 경로를 빠뜨리면 남은 축이 열린 채로 남아 그 문제의 리포트가
			// 영영 만들어지지 않는다. 같은 문제의 다른 축으로 옮기는 것은 종료가 아니다.
			if (!input.stage().problemId().equals(result.cursor().problemId())) {
				repository.closeProblem(sessionId, input.stage().problemId(),
						JdbcSessionRepository.CLOSE_CURSOR_MOVED);
			}
			repository.moveCursor(sessionId, result.cursor().problemId(),
					resolveStageId(sessionId, result.cursor().problemId(), result.cursor().axisCode()));
		} else {
			// 커서가 null이면 AI가 "더 물을 것이 없다"고 판정한 것이다. 종료 판정은 AI가 소유하므로
			// 백엔드가 커서 변화로 역추론하지 않는다(AI 계약 주석).
			repository.end(sessionId,
					input.head().isReview() ? "ALL_REVIEW_TARGETS_TERMINAL" : "ALL_PROBLEMS_TERMINAL",
					result.endedLevel());
		}
		return AnswerSubmitResponse.of(result, input.problems(), autoHint(input, result, grade));
	}

	/**
	 * 힌트를 둘 다 쓰고도 미달이면 <b>그 문제를 접고 다음 문제로 넘어간다.</b> 축이 L1이든 L4든 같다 —
	 * 두 번 설명하고도 닿지 않았다면 같은 코드에 대해 더 물어도 얻을 것이 없다.
	 *
	 * <p><b>AI 커서를 덮어쓴다.</b> 종료 판정은 원래 AI가 소유하고 백엔드는 커서를 따르기만 했다
	 * ({@link #applyGrading}의 다른 분기). 여기만 예외인 이유는 이 규칙이 <b>학습 정책</b>이라
	 * 모델 응답에 따라 흔들리면 안 되기 때문이다 — 같은 상황에서 어떤 학생은 다음 질문을 받고 어떤
	 * 학생은 다음 문제로 가면, 리포트의 "도달 축"이 사람마다 다른 뜻이 된다.
	 *
	 * <p>남은 축은 {@code NOT_REACHED}·{@code NOT_PASSED}로 닫고 종료 표식을 찍는다
	 * ({@link JdbcSessionRepository#closeProblem}). 그러지 않으면 {@code PREPARED}로 남아 리포트가
	 * "여기까지 오지도 못했다"를 표현할 수 없고, 표식이 없으면 리포트 자체가 만들어지지 않는다.
	 */
	private AnswerSubmitResponse closeProblem(GradingInput input, AnswerResult result) {
		UUID sessionId = input.head().sessionId();
		UUID closedProblemId = input.stage().problemId();
		repository.closeProblem(sessionId, closedProblemId, JdbcSessionRepository.CLOSE_HINTS_EXHAUSTED);

		SessionStage nextStage = repository.findStages(sessionId).stream()
				.filter(stage -> !stage.problemId().equals(closedProblemId))
				.filter(stage -> stage.problemNo() > input.stage().problemNo())
				.findFirst()
				.orElse(null);

		if (nextStage == null) {
			// 마지막 문제였다. 세션을 닫는다 — 이때는 AI의 endedLevel을 그대로 쓴다(도달 축 판정은
			// 여전히 AI 것이고, 우리가 덮어쓴 것은 "다음에 무엇을 물을까"뿐이다).
			repository.end(sessionId,
					input.head().isReview() ? "ALL_REVIEW_TARGETS_TERMINAL" : "ALL_PROBLEMS_TERMINAL",
					result.endedLevel());
			return AnswerSubmitResponse.sessionEnded();
		}

		repository.moveCursor(sessionId, nextStage.problemId(), nextStage.problemStageId());
		return AnswerSubmitResponse.problemClosed(nextStage, input.problems());
	}

	/**
	 * 3점 미만이면 <b>다음 힌트를 자동으로 연다.</b> 학생이 `다시 설명해 주세요`를 누르기를 기다리지
	 * 않는다 — 정의서 §6의 "미달이면 그때 힌트를 보여준다"가 이 경로다.
	 *
	 * <p>여는 방식은 {@code POST /hints}와 <b>같은 UPDATE</b>여야 한다. 표시 시각을 남기지 않고
	 * 문구만 응답에 실으면, 새로고침 복귀 때 {@code hintsUsed}가 0으로 되돌아가 학생이 힌트를
	 * 세 번, 네 번 쓴다.
	 *
	 * <p>열지 않는 경우 셋 — 통과했다(더 설명할 것이 없다), 힌트를 다 썼다, 커서가 다른 자리로
	 * 옮겨 갔다. 마지막은 AI가 "이 질문은 여기까지"라고 판정한 것이므로 닫힌 질문에 힌트를 붙이지
	 * 않는다.
	 *
	 * <p><b>다시 보기도 연다(37차 R2).</b> 종전에는 {@code isReview}면 건너뛰었는데, 그러면 힌트 표시
	 * 시각이 안 남아 {@link SessionStage#nextSlot()}이 영원히 {@code QUESTION}이 되고 미달한 축에서
	 * 세션이 갇혔다. 1차와 같은 경로를 그대로 쓰면 미달 → 힌트 → 재답변 → (2회 소진 시) 문제 종료가
	 * 다시 보기에서도 그대로 돈다.
	 */
	private AnswerSubmitResponse.AutoHint autoHint(GradingInput input, AnswerResult result, AnswerGrade grade) {
		SessionStage stage = input.stage();
		int hintsUsed = stage.hintsUsed();
		if (grade.passed() || hintsUsed >= 2 || !staysOnSameStage(input, result)) {
			return null;
		}

		AnswerSlot opening = AnswerSlot.ofHintsUsed(hintsUsed + 1);
		// row_version은 방금의 applyAnswer가 1 올렸다. 읽어 둔 값 그대로 쓰면 어긋난다.
		if (repository.openHint(stage.problemStageId(), opening, stage.rowVersion() + 1) == 0) {
			// 같은 자리에 다른 요청이 끼어들었다. 답변 저장은 이미 끝났으므로 실패시키지 않고
			// 힌트만 비운다 — 화면은 `다시 설명해 주세요`로 직접 열 수 있다.
			log.warn("자동 힌트 열기가 낙관적 잠금에 걸렸다. 답변은 저장됐다: stageId={}, slot={}",
					stage.problemStageId(), opening);
			return null;
		}
		String hintText = opening == AnswerSlot.FIRST_HINT ? stage.firstHintText() : stage.secondHintText();
		return new AnswerSubmitResponse.AutoHint(hintText, hintsUsed + 1, 2 - (hintsUsed + 1));
	}

	/** AI 커서가 방금 답한 그 질문에 그대로 서 있는가. 옮겨 갔으면 이 질문은 닫힌 것이다. */
	private static boolean staysOnSameStage(GradingInput input, AnswerResult result) {
		return result.cursor() != null
				&& input.stage().problemId().equals(result.cursor().problemId())
				&& input.stage().axisCode().equals(result.cursor().axisCode());
	}


	/**
	 * 채점 결과를 판정으로 바꾼다. <b>통과 여부는 점수에서 도출한다</b>({@link AnswerGrade}) —
	 * 3점 미만이면 실패다.
	 *
	 * <p>{@code turn}이 없으면 채점이 되지 않은 것이다. 예전에는 이때 0점·실패로 적었는데, 그러면
	 * <b>AI가 답을 읽지도 못한 답변이 "0점 실패"로 기록되고</b> 학생은 힌트를 하나 잃는다. 저장하지 않고
	 * 재제출을 요구하는 편이 맞다 — 멱등키가 자리마다 고정이라 같은 답을 다시 보내도 비용이 늘지 않는다.
	 */
	private AnswerGrade gradeOf(AnswerResult result) {
		if (result.turn() == null) {
			throw new SessionException(SessionErrorCode.GRADING_FAILED);
		}
		AnswerGrade grade = AnswerGrade.of(result.turn().score());
		if (grade.passed() != result.turn().passed()) {
			// 저장은 점수 기준으로 한다. 어긋난다는 것은 AI의 임계값이 우리와 다르다는 뜻이라 계약 문제다.
			log.warn("AI 통과 판정이 점수와 어긋난다. 점수 기준으로 저장한다: score={}, aiPassed={}, 임계값={}",
					result.turn().score(), result.turn().passed(), AnswerGrade.PASS_SCORE);
		}
		return grade;
	}

	/**
	 * 단계 상태. 통과면 {@code PASSED}, 마지막 슬롯까지 쓰고도 미달이면 {@code NOT_PASSED},
	 * 그 사이는 {@code IN_PROGRESS}다 — 힌트가 남아 있으면 같은 질문에 다시 답할 수 있다.
	 *
	 * <p>{@code NOT_PASSED}를 마지막 슬롯에서만 쓰는 것은 취향이 아니라 제약이다 —
	 * {@code ck_problem_stage_status_2}가 답한 슬롯 중 통과한 것이 없을 것을 요구한다.
	 */
	private static String stageStatus(AnswerSlot slot, boolean passed) {
		if (passed) {
			return "PASSED";
		}
		return slot == AnswerSlot.SECOND_HINT ? "NOT_PASSED" : "IN_PROGRESS";
	}

	/** AI가 준 커서(문제 + 축)를 우리 단계 ID로 되돌린다. */
	private UUID resolveStageId(UUID sessionId, UUID problemId, String axisCode) {
		return repository.findStages(sessionId).stream()
				.filter(stage -> stage.problemId().equals(problemId) && stage.axisCode().equals(axisCode))
				.map(SessionStage::problemStageId)
				.findFirst()
				.orElseThrow(() -> new SessionException(SessionErrorCode.STAGE_NOT_FOUND));
	}

	/** 채점 전에 읽어 둔 것들. AI 호출 동안 트랜잭션을 붙들지 않기 위한 운반 상자다. */
	public record GradingInput(SessionHead head, List<SessionProblem> problems, SessionStage stage,
			AnswerSlot slot) {
	}
}
