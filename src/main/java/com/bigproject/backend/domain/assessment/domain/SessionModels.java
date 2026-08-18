package com.bigproject.backend.domain.assessment.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 세션 조회 모델 모음. <b>DB 행을 그대로 옮긴 읽기 전용 구조</b>이며 화면 응답도 AI 요청도 이것으로 만든다.
 *
 * <p>한 파일에 모아 둔 이유: 전부 {@code problem_stage}·{@code assessment_problem} 한 벌에서
 * 같이 읽어 오는 값들이라 따로 두면 어느 것이 같은 조회의 결과인지 흩어진다. 레코드마다 파일을 나누면
 * 열 개가 넘고 각각은 필드 대여섯 개뿐이다.
 */
public final class SessionModels {

	private SessionModels() {
	}

	/**
	 * 세션 한 건의 머리 정보. {@code assessment_session} + {@code measurement_attempt} 조인 결과다.
	 *
	 * @param mode                    {@code attempt_type}. {@code REVIEW}면 다시 보기이며 힌트가 없고 판정에 반영되지 않는다
	 * @param timeLimitAt             정책 시간 상한. NULL이면 상한이 없다(정의서 §6 "시간은 안내만 하고 강제로 끊지 않는다"의
	 *                                예외가 60분 하드 상한이라, 값이 있으면 그때 닫는다)
	 * @param currentProblemStartedAt 지금 문제의 L1 축이 교육생에게 처음 표시된 시각({@code problem_stage.question_presented_at}).
	 *                                문제별 20분 제한의 기준점이며, 세션 레벨과 달리 이 값을 담는 컬럼을 따로 두지 않고
	 *                                이미 있는 컬럼을 그대로 읽는다 — L1이 표시된 순간이 곧 그 문제가 시작된 순간이다.
	 * @param assessmentCloseAt       <b>개인</b> 응시 창 종료({@code measurement_attempt.assessment_close_at}).
	 *                                NULL이면 창이 아직 정해지지 않은 것이라 막지 않는다. 회차 창은 폐기됐으므로
	 *                                (2026-08-16) 응시 가능 여부를 정하는 것은 이 값 하나뿐이다
	 */
	public record SessionHead(
			UUID sessionId,
			UUID orgId,
			UUID attemptId,
			UUID userId,
			UUID assessmentRoundId,
			String mode,
			String status,
			UUID currentProblemId,
			UUID currentProblemStageId,
			Instant startedAt,
			Instant timeLimitAt,
			Instant reviewDueAt,
			UUID sourceSubmissionId,
			Instant currentProblemStartedAt,
			Instant assessmentCloseAt
	) {
		/** 응시 창을 검사하지 않는 맥락(테스트 등)에서는 null로 둔다. */
		public SessionHead(
				UUID sessionId, UUID orgId, UUID attemptId, UUID userId, UUID assessmentRoundId,
				String mode, String status, UUID currentProblemId, UUID currentProblemStageId,
				Instant startedAt, Instant timeLimitAt, Instant reviewDueAt, UUID sourceSubmissionId,
				Instant currentProblemStartedAt) {
			this(sessionId, orgId, attemptId, userId, assessmentRoundId, mode, status, currentProblemId,
					currentProblemStageId, startedAt, timeLimitAt, reviewDueAt, sourceSubmissionId,
					currentProblemStartedAt, null);
		}

		/** 기존 호출부 호환용. 문제별 시간 제한을 검사하지 않는 맥락(테스트 등)에서는 null로 둔다. */
		public SessionHead(
				UUID sessionId, UUID orgId, UUID attemptId, UUID userId, UUID assessmentRoundId,
				String mode, String status, UUID currentProblemId, UUID currentProblemStageId,
				Instant startedAt, Instant timeLimitAt, Instant reviewDueAt, UUID sourceSubmissionId) {
			this(sessionId, orgId, attemptId, userId, assessmentRoundId, mode, status, currentProblemId,
					currentProblemStageId, startedAt, timeLimitAt, reviewDueAt, sourceSubmissionId, null);
		}

		public boolean isReview() {
			return "REVIEW".equals(mode);
		}

		public boolean isEnded() {
			return !"READY".equals(status) && !"IN_PROGRESS".equals(status) && !"PAUSED".equals(status);
		}

		/**
		 * 이 세션에 걸린 마감. 다시 보기는 {@code review_due_at}, 1차는 <b>개인</b> 응시 창이다.
		 *
		 * <p>두 값을 한 자리에서 고르는 이유는 {@code SessionGuard}가 두 종류의 세션을 같은 경로로
		 * 검사하기 때문이다. 호출부마다 갈라 쓰면 한쪽만 고쳐지는 순간 다시 새어 나간다.
		 *
		 * @return 마감. NULL이면 마감이 없다(막지 않는다)
		 */
		public Instant deadlineAt() {
			return isReview() ? reviewDueAt : assessmentCloseAt;
		}

		/** 마감이 있고 이미 지났는가. 마감이 없으면 언제나 {@code false}다. */
		public boolean isDeadlinePassed(Instant now) {
			Instant deadline = deadlineAt();
			return deadline != null && !now.isBefore(deadline);
		}
	}

	/**
	 * 문제 하나와 그 코드 근거. {@code codeSnippet}은 <b>문제를 낸 파일 전체</b>다 —
	 * AI가 그렇게 보내고 자르는 위치는 화면이 정한다("Spring이 다시 자르지 마세요").
	 */
	public record SessionProblem(
			UUID problemId,
			int problemNo,
			String title,
			String problemType,
			java.math.BigDecimal priority,
			String questionFocusItemId,
			UUID teachId,
			String snippetKey,
			String codeLanguage,
			String sourcePath,
			int lineStart,
			int lineEnd,
			String evidenceHash,
			Integer extractorVersion,
			String codeSnippet,
			String contentHash,
			List<SessionProblemReference> references,
			List<SessionStage> stages
	) {
	}

	/**
	 * {@code assessment_problem_reference} 한 행. 호출부·교안 증적·하이라이트가 여기 들어 있다.
	 *
	 * <p>{@code teachLabel}·{@code sourcePages}는 {@code CURRICULUM_EVIDENCE}에서만 채워진다
	 * (39차 R4) — 그 타입은 DDL이 {@code sourcePath}/{@code lineStart}/{@code lineEnd}를
	 * 강제로 NULL로 두는 대신 {@code teachId}를 갖기 때문이다.
	 */
	public record SessionProblemReference(
			String referenceType,
			int displayOrder,
			String sourcePath,
			Integer lineStart,
			Integer lineEnd,
			String axisCode,
			UUID teachId,
			String evidenceHash,
			String teachLabel,
			List<Integer> sourcePages
	) {
	}

	/**
	 * {@code problem_stage} 한 행 = 축 하나. 질문·힌트 2개가 분석 시점에 동결돼 있고, 답변·점수·통과는
	 * 슬롯 셋에 누적된다.
	 */
	public record SessionStage(
			UUID problemStageId,
			UUID problemId,
			int problemNo,
			String axisCode,
			int questionSequenceNo,
			String questionText,
			String firstHintText,
			String secondHintText,
			String status,
			SlotState question,
			SlotState firstHint,
			SlotState secondHint,
			Instant firstHintPresentedAt,
			Instant secondHintPresentedAt,
			long rowVersion
	) {
		/** 지금까지 연 힌트 수. 새로고침 복귀 때 {@code hintsUsed}를 이 값으로 되살린다. */
		public int hintsUsed() {
			if (secondHintPresentedAt != null) {
				return 2;
			}
			return firstHintPresentedAt != null ? 1 : 0;
		}

		/**
		 * 다음 답변이 들어갈 슬롯. <b>연 힌트 수가 곧 슬롯이다</b> — 힌트를 하나 보고 쓴 답은
		 * {@code first_hint_*}에 들어간다.
		 *
		 * <p>힌트가 열리는 경로는 둘이고 이 계산은 둘 다에 같게 적용된다 — 학생이 `다시 설명해 주세요`를
		 * 눌러 <b>미리</b> 연 경우와, 답변이 3점 미만이라 <b>자동으로</b> 열린 경우다.
		 * 이탈·첫 타이핑 지연을 쌓을 컬럼 묶음도 이 슬롯으로 고른다.
		 */
		public AnswerSlot nextSlot() {
			return AnswerSlot.ofHintsUsed(hintsUsed());
		}

		public boolean isTerminal() {
			return "PASSED".equals(status) || "NOT_PASSED".equals(status)
					|| "NOT_ANSWERED".equals(status);
		}

		public SlotState slot(AnswerSlot slot) {
			return switch (slot) {
				case QUESTION -> question;
				case FIRST_HINT -> firstHint;
				case SECOND_HINT -> secondHint;
			};
		}
	}

	/** 슬롯 하나의 답변 상태. 네 값은 CHECK가 "전부 NULL이거나 전부 채움"을 강제한다. */
	public record SlotState(String answerText, Short score, Boolean passed, Instant answeredAt) {
		public boolean isAnswered() {
			return answerText != null;
		}
	}
}
