package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.domain.TraineeTimelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcTraineeTimelineRepository implements TraineeTimelineRepository {
	private final JdbcTemplate jdbcTemplate;

	@Override
	public List<TimelineRow> findAll(UUID managerId, UUID cohortId, UUID traineeId, String type) {
		String typeClause = switch (type == null ? "ALL" : type) {
			case "ASSESSMENT" -> " AND timeline_item_type = 'ASSESSMENT' AND COALESCE(review_target_count, 0) = 0";
			case "REVIEW" -> " AND timeline_item_type = 'ASSESSMENT' AND COALESCE(review_target_count, 0) > 0";
			case "REPORT" -> " AND timeline_item_type = 'REPORT'";
			case "INTERVIEW" -> " AND timeline_item_type = 'INTERVIEW'";
			default -> "";
		};
		String sql = """
				SELECT timeline_item_id, timeline_item_type, source_entity_type, source_entity_id,
				  project_id, assessment_round_id, analysis_sequence_no, timeline_group_key,
				  group_sort_at, team_id_at_round, team_name_at_round, timeline_occurred_at,
				  source_status, display_title, summary, detail_summary,
				  problem_results::text, review_result_items::text, review_change_status,
				  review_target_count, interview_record_status, identified_cause_summary,
				  guidance_summary, next_action_summary, is_expandable, detail_action_code,
				  row_aggregation_status, is_stale, as_of_at
				FROM manager_trainee_detail_timeline_view
				WHERE manager_user_id = ? AND cohort_id = ? AND target_user_id = ?
				""" + typeClause + " ORDER BY group_sort_at DESC, timeline_item_type_order, timeline_item_id";
		return jdbcTemplate.query(sql, this::mapRow, managerId, cohortId, traineeId);
	}

	private TimelineRow mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new TimelineRow(
				rs.getObject("timeline_item_id", UUID.class), rs.getString("timeline_item_type"),
				rs.getString("source_entity_type"), rs.getObject("source_entity_id", UUID.class),
				rs.getObject("project_id", UUID.class), rs.getObject("assessment_round_id", UUID.class),
				integer(rs, "analysis_sequence_no"), rs.getString("timeline_group_key"),
				time(rs.getTimestamp("group_sort_at")), rs.getObject("team_id_at_round", UUID.class),
				rs.getString("team_name_at_round"), time(rs.getTimestamp("timeline_occurred_at")),
				rs.getString("source_status"), rs.getString("display_title"), rs.getString("summary"),
				rs.getString("detail_summary"), rs.getString("problem_results"),
				rs.getString("review_result_items"), rs.getString("review_change_status"),
				integer(rs, "review_target_count"), rs.getString("interview_record_status"),
				rs.getString("identified_cause_summary"), rs.getString("guidance_summary"),
				rs.getString("next_action_summary"), rs.getBoolean("is_expandable"),
				rs.getString("detail_action_code"), rs.getString("row_aggregation_status"),
				rs.getBoolean("is_stale"), time(rs.getTimestamp("as_of_at")));
	}

	private Integer integer(ResultSet rs, String name) throws SQLException {
		int value = rs.getInt(name);
		return rs.wasNull() ? null : value;
	}

	private OffsetDateTime time(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
	}
}
