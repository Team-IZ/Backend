package com.bigproject.backend.domain.assessment.domain;

import java.util.List;
import java.util.UUID;

/** 다시 보기(REVIEW) 파생에 쓰는 행 모델. 조회 결과를 그대로 담는 자리라 동작을 두지 않는다. */
public final class ReviewModels {

	private ReviewModels() {
	}

	/**
	 * 다시 보기의 근거 한 벌 — 리포트·스냅샷과 그 회차의 1차 응시·세션.
	 *
	 * <p>넷이 함께 있어야 REVIEW 응시를 만들 수 있다. 리포트·스냅샷은 DDL이 요구하고
	 * ({@code ck_measurement_attempt_attempt_type_2}), 1차 응시는 복사 원본이며, 1차 세션은
	 * 질문을 가져올 곳이다.
	 *
	 * @param sourceSessionId 1차 세션. <b>null일 수 있다</b> — 분석이 실패해 세션이 열리지 않았으면
	 *                        가져올 질문 자체가 없다
	 */
	public record ReviewSource(
			UUID reportId,
			UUID snapshotId,
			UUID assessmentRoundId,
			UUID sourceAttemptId,
			String sourceAttemptStatus,
			UUID sourceSessionId
	) {

		/** 1차를 끝내지 않았으면 다시 볼 것이 없다. 도달 단계가 아직 확정되지 않았기 때문이다. */
		public boolean isSourceCompleted() {
			return "COMPLETED".equals(sourceAttemptStatus);
		}
	}

	/**
	 * 이미 만들어져 있는 다시 보기. 회차당 한 번이므로 있으면 새로 만들지 않는다.
	 *
	 * @param sessionId 세션이 없을 수 있다 — 응시만 만들어지고 세션 생성이 실패한 흔적이다
	 */
	public record ExistingReview(UUID attemptId, String attemptStatus, UUID sessionId, String sessionStatus) {

		/** 이미 끝냈는가. 끝난 다시 보기는 다시 열지 않는다 — 회차당 한 번이다. */
		public boolean isFinished() {
			return attemptStatus != null
					&& List.of("COMPLETED", "FAILED", "EXPIRED").contains(attemptStatus);
		}

		/** 이어서 할 수 있는 세션이 남아 있는가. */
		public boolean isResumable() {
			return hasSession() && List.of("READY", "IN_PROGRESS", "PAUSED").contains(sessionStatus);
		}

		/**
		 * 세션 행이 이미 있는가. <b>이어서 할 수 있는지와 다른 질문</b>이다.
		 *
		 * <p>{@code uq_assessment_session_attempt_id}가 응시당 세션을 하나로 묶으므로, 끝난 세션이
		 * 붙어 있는 응시에 세션을 하나 더 넣으면 무결성 위반으로 500이 난다. 세션을 채워도 되는 것은
		 * <b>세션이 아예 없을 때뿐</b>이라 그 판정을 따로 둔다.
		 */
		public boolean hasSession() {
			return sessionId != null && sessionStatus != null;
		}
	}
}
