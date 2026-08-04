package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.ManagerAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ManagerAssignmentRepository extends JpaRepository<ManagerAssignment, UUID> {

	// 특정 반의 현재 활성(미해제) 담당 배정 조회. 담당 매니저를 새로 배정하기 전에 기존 배정을 해제하는 데 사용
	List<ManagerAssignment> findByClassIdAndOrgIdAndUnassignedAtIsNull(UUID classId, UUID orgId);

	// 여러 반의 활성 담당 배정을 한 번에 조회 (목록 조회에서 반마다 따로 쿼리하지 않도록)
	List<ManagerAssignment> findByClassIdInAndOrgIdAndUnassignedAtIsNull(List<UUID> classIds, UUID orgId);

	/**
	 * 매니저가 현재 유효하게 담당하는 반 ID 목록. 교육생 명부의 조회 범위를 이 값으로 제한한다.
	 *
	 * <p>과거에 담당했더라도 배정이 해제됐으면 조회할 수 없으므로 status='ACTIVE'와 unassigned_at IS NULL을 함께 본다.
	 */
	@Query("""
			SELECT a.classId
			FROM ManagerAssignment a
			WHERE a.managerUserId = :managerUserId
				AND a.orgId = :orgId
				AND a.status = 'ACTIVE'
				AND a.unassignedAt IS NULL
			ORDER BY a.classId
			""")
	List<UUID> findAccessibleClassIds(@Param("managerUserId") UUID managerUserId, @Param("orgId") UUID orgId);
}
