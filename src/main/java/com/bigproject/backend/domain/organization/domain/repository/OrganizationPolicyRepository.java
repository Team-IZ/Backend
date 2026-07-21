package com.bigproject.backend.domain.organization.domain.repository;

import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationPolicyRepository extends JpaRepository<OrganizationPolicy, UUID> {

	// 부분 유니크 인덱스 uq_organization_policy_current(org_id) WHERE status='ACTIVE' AND effective_to IS NULL 와 동일하게
	// 기관당 활성 정책은 항상 0 또는 1건이다.
	Optional<OrganizationPolicy> findByOrgIdAndStatus(UUID orgId, OrganizationPolicy.Status status);

	// 기관 목록 조회 화면에서 데이터 보존기간(retentionDays)을 N+1 쿼리 없이 한 번에 조회하기 위한 배치 조회.
	List<OrganizationPolicy> findByOrgIdInAndStatus(Collection<UUID> orgIds, OrganizationPolicy.Status status);
}
