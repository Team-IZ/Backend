package com.bigproject.backend.domain.organization.domain;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * cohort/member 도메인은 아직 organization이 가져다 쓸 수 있는 엔티티/리포지토리가 없어서(cohort는 엔티티 자체가 없고,
 * member는 cohort 단위로만 교육생을 조회할 수 있음), organization 도메인이 자기 응답(cohortCount/managerCount/traineeCount)에
 * 필요한 집계만 직접 조회하기 위한 전용 포트. auth.domain.AuthUserRepository와 같은 패턴(인터페이스는 domain,
 * JDBC 구현은 infrastructure)을 따른다.
 */
public interface OrganizationStatsRepository {

	Map<UUID, Integer> countActiveCohortsByOrgId(Collection<UUID> organizationIds);

	Map<UUID, Integer> countActiveManagersByOrgId(Collection<UUID> organizationIds);

	Map<UUID, Integer> countActiveTraineesByOrgId(Collection<UUID> organizationIds);
}
