package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.application.AnswerGradingContract.AnswerResult;
import com.bigproject.backend.domain.assessment.domain.AnswerSlot;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionProblem;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import com.bigproject.backend.domain.assessment.presentation.dto.AnswerSubmitResponse;
import lombok.RequiredArgsConstructor;
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
@Service
@RequiredArgsConstructor
public class SessionTurnStore {

	private final JdbcSessionRepository repository;
	private final SessionGuard guard;

	/** 채점에 필요한 것을 한 번에 읽는다. 이 트랜잭션이 끝난 뒤 AI를 부른다. */
	@Transactional(readOnly = true)
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
		int score = result.turn() == null ? 0 : result.turn().score();
		boolean passed = result.turn() != null && result.turn().passed();

		if (repository.applyAnswer(stage.problemStageId(), input.slot(), answerText, score, passed,
				stageStatus(input.slot(), passed), stage.rowVersion()) == 0) {
			throw new SessionException(SessionErrorCode.ANSWER_ALREADY_SUBMITTED);
		}

		UUID sessionId = input.head().sessionId();
		if (result.cursor() != null && result.cursor().problemId() != null) {
			repository.moveCursor(sessionId, result.cursor().problemId(),
					resolveStageId(sessionId, result.cursor().problemId(), result.cursor().axisCode()));
		} else {
			// 커서가 null이면 AI가 "더 물을 것이 없다"고 판정한 것이다. 종료 판정은 AI가 소유하므로
			// 백엔드가 커서 변화로 역추론하지 않는다(AI 계약 주석).
			repository.end(sessionId,
					input.head().isReview() ? "ALL_REVIEW_TARGETS_TERMINAL" : "ALL_PROBLEMS_TERMINAL",
					result.endedLevel());
		}
		return AnswerSubmitResponse.of(result, score, passed);
	}

	/**
	 * 단계 상태. 통과면 {@code PASSED}, 두 번째 힌트까지 쓰고도 미달이면 {@code NOT_PASSED},
	 * 그 사이는 {@code IN_PROGRESS}다.
	 *
	 * <p>{@code NOT_PASSED}를 마지막 슬롯에서만 쓰는 것은 취향이 아니라 제약이다 —
	 * {@code ck_problem_stage_status_2}가 슬롯 셋이 <b>모두</b> FALSE일 것을 요구한다.
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
