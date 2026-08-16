package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.ManagerAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ManagerAssignmentRepository extends JpaRepository<ManagerAssignment, UUID> {

	// 특정 반의 현재 활성(미해제) 담당 배정 조회. 담당 매니저를 새로 배정하기 전에 기존 배정을 해제하는 데 사용
	List<ManagerAssignment> findByClassIdAndOrgIdAndUnassignedAtIsNull(UUID classId, UUID orgId);

	// 여러 반의 활성 담당 배정을 한 번에 조회 (목록 조회에서 반마다 따로 쿼리하지 않도록)
	List<ManagerAssignment> findByClassIdInAndOrgIdAndUnassignedAtIsNull(List<UUID> classIds, UUID orgId);

	// 매니저 한 명이 현재 담당 중인 배정 전체. 계정 정지 시 담당 반을 한 트랜잭션에서 놓는 데 쓴다(9차 R7).
	List<ManagerAssignment> findByManagerUserIdAndOrgIdAndUnassignedAtIsNull(UUID managerUserId, UUID orgId);

	/**
	 * 담당 반 판정용(30차 R3). 위 메서드와 달리 {@code status}까지 본다 — 제출 현황·명단·히트맵의
	 * 네이티브 SQL이 {@code status = 'ACTIVE' AND unassigned_at IS NULL} 두 조건을 함께 걸므로,
	 * 팀 목록만 한 조건으로 좁히면 <b>같은 화면의 탭끼리 담당 반이 달라진다.</b>
	 */
	List<ManagerAssignment> findByManagerUserIdAndOrgIdAndStatusAndUnassignedAtIsNull(
			UUID managerUserId, UUID orgId, String status);
}
