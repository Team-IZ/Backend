package com.bigproject.backend.domain.intervention.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code caseId}(=candidate_id) 하나로 케이스를 짚는다.
 *
 * <h2>왜 별도 조회가 필요한가</h2>
 *
 * <p>화면은 목록·브리프·제외·무효 확인을 <b>{@code caseId} 하나로</b> 처리한다. 그런데
 * 브리프 뷰는 {@code interview_id} 기준이고 무효 확인 API는 {@code attempt_id}를 받는다 —
 * 그 사이를 잇는 조회가 필요하다.
 *
 * <p>{@code manager_interview_list_view}를 그대로 쓴다. 담당 반 스코프가 그 안에 있어
 * <b>조회에 성공했다는 것 자체가 권한 확인</b>이 된다 — 목록에 보이는 케이스와 열 수 있는
 * 케이스가 같은 모집단이어야 "목록에는 있는데 열면 404"가 안 난다.
 */
public interface InterviewCaseLookupRepository {

	Optional<CaseSummary> findCase(UUID managerUserId, UUID orgId, UUID caseId);

	/**
	 * 무효 확인 상태. {@code PENDING}이면 아직 사람이 판정하지 않았다.
	 *
	 * <p>브리프 생성 전에 확인해야 한다 — 판정 전에는 {@code briefType}을 정할 수 없다.
	 * 수행이 없으면(회차에 응시 기록 자체가 없으면) 비어 있다.
	 */
	Optional<String> findValidityReviewStatus(UUID managerUserId, UUID orgId, UUID caseId);

	/**
	 * @param interviewId 아직 브리프를 만들지 않았으면 null이다
	 * @param riskType    화면 4종. 목록과 같은 SQL 식이 계산한다
	 */
	record CaseSummary(
			UUID candidateId,
			UUID traineeUserId,
			String traineeName,
			String className,
			UUID interviewId,
			String interviewStatus,
			String riskType,
			String riskSummary,
			UUID assessmentRoundId) {
	}
}
