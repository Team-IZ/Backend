package com.bigproject.backend.domain.projectexecution.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcAssessmentAxisQueryRepository implements AssessmentAxisQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Map<UUID, Integer> findReachedAxisByProject(UUID projectId, UUID orgId) {
        // 한 사람이 응시를 여러 번(재시도) 했을 수 있어 가장 최근 시도 하나만 본다.
        // ended_axis_code가 'L1'~'L4'라 뒷자리만 정수로 뽑는다. null(세션 미종료·이탈)은 결과에서 빠진다 —
        // 호출부가 "이 맵에 없으면 0(가장 낮음)"으로 처리한다.
        Map<UUID, Integer> result = new HashMap<>();
        jdbcTemplate.query("""
				SELECT DISTINCT ON (ma.user_id) ma.user_id,
				       CAST(SUBSTRING(s.ended_axis_code FROM 2) AS INTEGER) AS axis_level
				FROM measurement_attempt ma
				JOIN assessment_session s ON s.attempt_id = ma.attempt_id
				WHERE ma.project_id = ? AND ma.org_id = ? AND ma.attempt_type = 'INITIAL'
				  AND s.ended_axis_code IS NOT NULL
				ORDER BY ma.user_id, ma.attempt_sequence_no DESC
				""",
                rs -> {
                    result.put(UUID.fromString(rs.getString("user_id")), rs.getInt("axis_level"));
                },
                projectId, orgId);
        return result;
    }
}