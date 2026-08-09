package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.CohortMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	/**
	 * 여러 기수의 <b>재적</b> 교육생 수를 기수 ID별로 한 번에 집계한다(10차 R3).
	 *
	 * <p>기수 목록이 한 화면에 20건씩 나오므로 기수마다 COUNT를 날리면 조회가 20건 더 나간다 —
	 * 반 목록의 {@code countActiveByClassIdIn}과 같은 이유로 IN 절 하나로 접는다.
	 *
	 * <p>{@code leftAt IS NULL}이 "재적"의 정의다. 이탈자를 포함하면 명단 조회(223명)와는 맞지만
	 * 반 배정 합계(208명)와 어긋나고, 화면이 기수 규모로 읽는 값이므로 지금 다니는 사람만 센다.
	 * 리포트 도메인이 쓰는 정의({@code cohort_member.left_at IS NULL})와도 같다.
	 */
	@Query("""
			SELECT cm.cohortId AS cohortId, COUNT(cm) AS count
			FROM CohortMember cm
			WHERE cm.cohortId IN :cohortIds
				AND cm.orgId = :orgId
				AND cm.leftAt IS NULL
			GROUP BY cm.cohortId
			""")
	List<CohortTraineeCount> countActiveByCohortIdIn(@Param("cohortIds") List<UUID> cohortIds,
	                                                 @Param("orgId") UUID orgId);

	interface CohortTraineeCount {
		UUID getCohortId();

		long getCount();
	}
}