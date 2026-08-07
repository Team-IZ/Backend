package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.CohortMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CohortMemberRepository extends JpaRepository<CohortMember, UUID> {

	/**
	 * 같은 기수·기관 안에서 주어진 user_id 목록에 해당하는 <b>유효한</b> 구성원 조회 (교육생 일괄 배정 시 사용).
	 *
	 * <p>LeftAtIsNull 조건이 핵심이다. 이것이 없으면 두 가지 문제가 생긴다.
	 * <ul>
	 *   <li>중도 이탈한 교육생이 반에 배정된다.</li>
	 *   <li>같은 사람이 이탈 후 재등록하면 (cohort_id, user_id)로 행이 2건 나와,
	 *       호출부의 {@code Collectors.toMap(CohortMember::getUserId, ...)}이
	 *       IllegalStateException(Duplicate key)을 던져 500 에러가 난다.</li>
	 * </ul>
	 */
	List<CohortMember> findByCohortIdAndOrgIdAndUserIdInAndLeftAtIsNull(
			UUID cohortId, UUID orgId, List<UUID> userIds);

	// 기수 종료 시 cascade 해제 대상 조회용: 이 기수의 유효한 구성원 전체
	List<CohortMember> findByCohortIdAndOrgIdAndLeftAtIsNull(UUID cohortId, UUID orgId);

	// GET /members/me/enrollments — 로그인한 사용자가 지금 유효하게 소속된 기수 전체(보통 1건)
	List<CohortMember> findByUserIdAndOrgIdAndLeftAtIsNull(UUID userId, UUID orgId);
}