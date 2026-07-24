package com.bigproject.backend.domain.operations.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * AiUsageRepository.sumCostByOrgId()의 JPQL "SELECT new ...(...)" 생성자 표현식 결과를 담는 프로젝션.
 * Hibernate의 HQL 파서가 중첩(nested) 클래스를 생성자 표현식 대상으로 못 찾는 문제가 있어(HHH 파서 제약),
 * AiUsageRepository 안의 nested record가 아니라 최상위 클래스로 분리해야 한다.
 */
public record OrgAiCostTotal(UUID orgId, BigDecimal totalCost) {
}
