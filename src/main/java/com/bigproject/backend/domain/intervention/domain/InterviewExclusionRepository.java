package com.bigproject.backend.domain.intervention.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 면담 후보 제외·재포함.
 *
 * <h2>허용 전이 (2026-08-14 DDL 개정)</h2>
 *
 * <pre>
 * ELIGIBLE            → EXCLUDED
 * EXCLUDED            → ELIGIBLE
 * ELIGIBLE            → INTERVIEW_CREATED
 * INTERVIEW_CREATED   → EXCLUDED            ← 이번 개정에서 추가
 * EXCLUDED            → INTERVIEW_CREATED   ← 이번 개정에서 추가
 * </pre>
 *
 * <p>테이블 COMMENT가 규정한 두 가지를 그대로 따른다.
 *
 * <ul>
 *   <li><b>제외는 연결된 면담이 PENDING인 후보로 한정</b>한다. IN_PROGRESS·COMPLETED 면담이
 *       있는 후보는 제외하지 않는다 — 이미 만난 사람을 큐에서 빼는 것은 의미가 없다.</li>
 *   <li><b>제외해도 Interview·브리프·원천 행은 삭제하지 않는다.</b> {@code candidate_id} UNIQUE
 *       연결도 유지한다 — 되돌렸을 때 만들어 둔 브리프가 그대로 있어야 한다.</li>
 * </ul>
 *
 * <p>재포함은 <b>연결된 면담 유무로 복귀 상태가 갈린다</b> — 면담이 남아 있으면
 * {@code INTERVIEW_CREATED}, 없으면 {@code ELIGIBLE}. 어느 쪽이든 제외 4컬럼을 NULL로 되돌린다
 * ({@code ck_interview_candidate_status_2}가 강제한다).
 */
public interface InterviewExclusionRepository {

	/** 담당 반 스코프까지 확인한 뒤 현재 상태를 읽는다. 담당이 아니면 비어 있다. */
	Optional<CandidateState> findState(UUID managerUserId, UUID orgId, UUID candidateId);

	/**
	 * @param expectedRowVersion 낙관적 잠금. 그 사이 다른 요청이 바꿨으면 0을 반환한다
	 * @return 갱신된 행 수
	 */
	int exclude(UUID candidateId, int expectedRowVersion, String reasonCode, UUID actorUserId);

	/**
	 * @param toStatus {@code INTERVIEW_CREATED} 또는 {@code ELIGIBLE}. 호출부가 면담 유무로 정한다
	 * @return 갱신된 행 수
	 */
	int reinclude(UUID candidateId, int expectedRowVersion, String toStatus);

	/**
	 * 상태 이력 1건. {@code request_id}가 NOT NULL이라 호출부가 만들어 넘긴다 —
	 * 같은 요청이 두 번 들어와도 이력에서 구분된다.
	 */
	void insertStatusHistory(UUID candidateId, String fromStatus, String toStatus,
			UUID actorUserId, String reasonCode, int candidateRowVersion, UUID requestId);

	/**
	 * @param status         후보 상태 {@code ELIGIBLE} / {@code EXCLUDED} / {@code INTERVIEW_CREATED}
	 * @param interviewId    연결된 면담. 없으면 null
	 * @param interviewStatus 연결된 면담 상태. 없으면 null
	 */
	record CandidateState(
			UUID candidateId,
			String status,
			int rowVersion,
			UUID interviewId,
			String interviewStatus) {

		/** 면담을 아직 시작하지 않았는가. 제외 가능 여부를 가른다. */
		public boolean interviewNotStarted() {
			return interviewStatus == null || "PENDING".equals(interviewStatus);
		}
	}
}
