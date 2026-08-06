package com.bigproject.backend.domain.academicoperations.application;

import com.bigproject.backend.domain.academicoperations.domain.Cohort;
import com.bigproject.backend.domain.academicoperations.domain.CohortStatus;
import com.bigproject.backend.domain.academicoperations.infrastructure.CohortRepository;
import com.bigproject.backend.domain.organization.domain.OrganizationPolicy;
import com.bigproject.backend.domain.organization.infrastructure.OrganizationPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CohortService {

    private final CohortRepository cohortRepository;
    private final OrganizationPolicyRepository organizationPolicyRepository;
    private final ClassroomService classroomService;

    // 기수 생성
    @Transactional
    public Cohort createCohort(UUID orgId, String name, LocalDate startDate,
                               LocalDate endDate, UUID creatorUserId) {

        // 규칙 1: 기관 내 기수명 중복 금지
        if (cohortRepository.existsByOrgIdAndNameAndDeletedAtIsNull(orgId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 기수명입니다: " + name);
        }

        // 규칙 2: 엔티티 생성자가 던지기 전에 먼저 걸러서 400으로 응답 (IllegalArgumentException은 전역에서 안 잡힘)
        if (startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시작일은 종료일보다 늦을 수 없습니다.");
        }

        OrganizationPolicy policy = organizationPolicyRepository
                .findByOrgIdAndStatus(orgId, OrganizationPolicy.Status.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "기관에 활성 운영 정책이 없어 기수를 만들 수 없습니다."));

        Cohort cohort = Cohort.builder()
                .orgId(orgId)
                .name(name)
                .startDate(startDate)
                .endDate(endDate)
                .createdBy(creatorUserId)
                .disclosureScope(policy.getDefaultDisclosureScope())
                .disclosurePolicyId(policy.getPolicyId())
                .build();

        return cohortRepository.save(cohort);
    }

    // 기수 단건 조회
    public Cohort findCohort(UUID cohortId, UUID orgId) {
        return cohortRepository.findByCohortIdAndOrgIdAndDeletedAtIsNull(cohortId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기수를 찾을 수 없습니다."));
    }

    // 기수 종료: 기수 상태 변경 + 소속 반 배정·매니저 배정 일괄 해제
    // retention_policy_id/retention_until은 종료 시점의 활성 기관 정책을 스냅샷으로 고정한다 (createCohort의
    // disclosure 스냅샷과 동일한 패턴). DB CHECK(ck_cohort_closed)가 CLOSED 전이 시 이 두 값을 필수로 요구한다.
    @Transactional
    public Cohort closeCohort(UUID cohortId, UUID orgId, UUID actorUserId) {
        Cohort cohort = findCohort(cohortId, orgId);
        OrganizationPolicy policy = organizationPolicyRepository
                .findByOrgIdAndStatus(orgId, OrganizationPolicy.Status.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "기관에 활성 운영 정책이 없어 기수를 종료할 수 없습니다."));

        cohort.close(actorUserId, policy.getPolicyId(), policy.getRetentionDays());
        classroomService.releaseAllAssignmentsForCohort(cohortId, orgId, actorUserId);
        return cohort;
    }

    // 기수 목록 조회
    public Page<Cohort> findCohorts(UUID orgId, CohortStatus status, String query, Pageable pageable) {
        return cohortRepository.findCohorts(orgId, status, query, pageable);
    }
}