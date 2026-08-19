package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.ManagerTraineeAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 담당 여부 판정 SQL. {@code ReportDisclosureRepository.isManagedBy}의 EXISTS 절을 옮겨 온 것이며,
 * 리포트 1건이 아니라 <b>교육생 1명</b>을 묻도록 시작점만 바꿨다 — 매니저 화면이 교육생 상세라
 * 회차별 리포트를 한꺼번에 보기 때문이다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcManagerTraineeAccessRepository implements ManagerTraineeAccessRepository {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public boolean isManagedBy(UUID traineeUserId, UUID managerUserId, UUID orgId) {
		String sql = """
				SELECT EXISTS (
				    SELECT 1
				    FROM cohort_member cm
				    JOIN class_membership clm
				      ON clm.cohort_member_id = cm.cohort_member_id
				     AND clm.org_id           = cm.org_id
				     AND clm.unassigned_at IS NULL
				    JOIN manager_assignment ma
				      ON ma.class_id        = clm.class_id
				     AND ma.org_id          = cm.org_id
				     AND ma.manager_user_id = ?
				     AND ma.unassigned_at IS NULL
				    WHERE cm.user_id = ?
				      AND cm.org_id  = ?
				      AND cm.left_at IS NULL
				)
				""";
		return Boolean.TRUE.equals(
				jdbcTemplate.queryForObject(sql, Boolean.class, managerUserId, traineeUserId, orgId));
	}
}
