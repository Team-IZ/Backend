package com.bigproject.backend.domain.projectexecution.infrastructure;

import java.util.Map;
import java.util.UUID;

/**
 * 직전 회차 도달 단계 조회 전용 포트. Assessment 도메인(measurement_attempt·assessment_session)이
 * 아직 이 저장소에 엔티티로 없어 순수 SQL로만 읽는다(ProjectMembershipQueryRepository와 같은 이유).
 */
public interface AssessmentAxisQueryRepository {

    /**
     * 그 프로젝트에서 각 교육생이 도달한 최종 단계. ended_axis_code(L1~L4)를 1~4로 바꿔 담는다.
     * 세션이 없거나 아직 안 끝났으면(ended_axis_code가 null) 그 사람은 이 맵에 없다 —
     * 호출부가 없는 사람을 0(가장 낮은 취급)으로 처리한다.
     */
    Map<UUID, Integer> findReachedAxisByProject(UUID projectId, UUID orgId);
}