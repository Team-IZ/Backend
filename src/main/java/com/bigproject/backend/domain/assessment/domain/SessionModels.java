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
	 * @param mode          {@code attempt_type}. {@code REVIEW}면 다시 보기이며 힌트가 없고 판정에 반영되지 않는다
	 * @param timeLimitAt   정책 시간 상한. NULL이면 상한이 없다(정의서 §6 "시간은 안내만 하고 강제로 끊지 않는다"의
	 *                      예외가 70분 하드 상한이라, 값이 있으면 그때 닫는다)
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
			UUID sourceSubmissionId
	) {
		public boolean isReview() {
			return "REVIEW".equals(mode);
		}

		public boolean isEnded() {
			return !"READY".equals(status) && !"IN_PROGRESS".equals(status) && !"PAUSED".equals(status);
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

	/** {@code assessment_problem_reference} 한 행. 호출부·교안 증적·하이라이트가 여기 들어 있다. */
	public record SessionProblemReference(
			String referenceType,
			int displayOrder,
			String sourcePath,
			Integer lineStart,
			Integer lineEnd,
			String axisCode,
			UUID teachId,
			String evidenceHash
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
