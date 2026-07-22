package com.bigproject.backend.domain.cohort.application;

import com.bigproject.backend.domain.cohort.domain.Cohort;
import com.bigproject.backend.domain.cohort.domain.CohortStatus;
import com.bigproject.backend.domain.cohort.infrastructure.CohortRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CohortService {

    private final CohortRepository cohortRepository;

    /** 기수 생성  */
    @Transactional
    public Cohort createCohort(Long orgId, String name, LocalDate startDate,
                               LocalDate endDate, String educationTrack, Long creatorUserId) {

        // 규칙 1: 기관 내 기수명 중복 금지
        if (cohortRepository.existsByOrgIdAndNameAndDeletedAtIsNull(orgId, name)) {
            throw new IllegalStateException("이미 존재하는 기수명입니다: " + name);
        }

        // 규칙 2: 기간 검증은 엔티티 생성자가 수행
        Cohort cohort = Cohort.builder()
                .orgId(orgId)
                .name(name)
                .startDate(startDate)
                .endDate(endDate)
                .educationTrack(educationTrack)
                .createdBy(creatorUserId)
                .build();

        return cohortRepository.save(cohort);
    }

    /** 기수 단건 조회 */
    public Cohort findCohort(Long cohortId, Long orgId) {
        return cohortRepository.findByCohortIdAndOrgIdAndDeletedAtIsNull(cohortId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("기수를 찾을 수 없습니다."));
    }

    /** 기수 종료  */
    @Transactional
    public Cohort closeCohort(Long cohortId, Long orgId, Long actorUserId) {
        Cohort cohort = findCohort(cohortId, orgId);
        cohort.close(actorUserId);
        return cohort;
    }

    /** 기수 목록 조회 */
    public Page<Cohort> findCohorts(Long orgId, CohortStatus status, String query, Pageable pageable) {
        return cohortRepository.findCohorts(orgId, status, query, pageable);
    }
}