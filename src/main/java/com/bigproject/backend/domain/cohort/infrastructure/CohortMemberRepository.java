package com.bigproject.backend.domain.cohort.infrastructure;

import com.bigproject.backend.domain.cohort.domain.CohortMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CohortMemberRepository extends JpaRepository<CohortMember, UUID> {

	// 같은 기수·기관 안에서 주어진 user_id 목록에 해당하는 구성원 조회 (교육생 일괄 배정 시 사용)
	List<CohortMember> findByCohortIdAndOrgIdAndUserIdIn(UUID cohortId, UUID orgId, List<UUID> userIds);
}
