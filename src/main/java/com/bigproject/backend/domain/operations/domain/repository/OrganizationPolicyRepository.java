package com.bigproject.backend.domain.operations.domain.repository;

import com.bigproject.backend.domain.operations.domain.OrganizationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * ⚠️ 원본 폴더 구조 목록에는 없었지만, OperationsService의 "운영 설정 조회/변경" 기능이
 *    organization_policy에 대한 읽기·쓰기가 모두 필요해 추가했다(OrganizationPolicy 엔티티 자체는 원래 목록에 포함되어 있었음).
 */
public interface OrganizationPolicyRepository extends JpaRepository<OrganizationPolicy, UUID> {

	// 부분 유니크 인덱스 uq_organization_policy_current(org_id) WHERE status='ACTIVE' AND effective_to IS NULL 와 동일하게
	// 기관당 활성 정책은 항상 0 또는 1건이다.
	Optional<OrganizationPolicy> findByOrgIdAndStatus(UUID orgId, OrganizationPolicy.Status status);
}
