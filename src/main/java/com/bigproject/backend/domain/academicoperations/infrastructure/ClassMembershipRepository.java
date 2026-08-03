package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.ClassMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClassMembershipRepository extends JpaRepository<ClassMembership, UUID> {

	// 주어진 cohort_member들의 현재 활성(미해제) 배정 조회. 반을 새로 배정하기 전에 기존 배정을 해제하는 데 사용
	List<ClassMembership> findByCohortMemberIdInAndOrgIdAndUnassignedAtIsNull(List<UUID> cohortMemberIds, UUID orgId);

	// 여러 반의 활성 배정 인원수를 반 ID별로 한 번에 집계 (목록 조회에서 반마다 따로 COUNT 쿼리를 날리지 않도록)
	@Query("""
			SELECT cm.classId AS classId, COUNT(cm) AS count
			FROM ClassMembership cm
			WHERE cm.classId IN :classIds
				AND cm.orgId = :orgId
				AND cm.unassignedAt IS NULL
			GROUP BY cm.classId
			""")
	List<ClassTraineeCount> countActiveByClassIdIn(@Param("classIds") List<UUID> classIds, @Param("orgId") UUID orgId);

	interface ClassTraineeCount {
		UUID getClassId();

		long getCount();
	}
}
