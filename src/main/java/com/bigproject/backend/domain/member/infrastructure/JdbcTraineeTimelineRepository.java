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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcTraineeTimelineRepository implements TraineeTimelineRepository {
	private static final String SCOPE =
			" WHERE manager_user_id = ? AND cohort_id = ? AND target_user_id = ?";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public int countEvents(UUID managerId, UUID cohortId, UUID traineeId, String eventType) {
		List<Object> args = new ArrayList<>(List.of(managerId, cohortId, traineeId));
		StringBuilder sql = new StringBuilder(
				"SELECT COUNT(*) FROM manager_trainee_detail_timeline_view" + SCOPE);
		appendTypeFilter(sql, args, eventType);
		Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
		return count == null ? 0 : count;
	}

	@Override
	public List<Integer> findRoundSequenceNos(UUID managerId, UUID cohortId, UUID traineeId,
			String eventType, Integer cursorSequenceNo, int limit) {
		List<Object> args = new ArrayList<>(List.of(managerId, cohortId, traineeId));
		StringBuilder sql = new StringBuilder(
				"SELECT DISTINCT analysis_sequence_no FROM manager_trainee_detail_timeline_view" + SCOPE);
		appendTypeFilter(sql, args, eventType);
		// 커서는 차수 하나다. 회차 정렬 키가 차수이므로 복합 키가 필요 없다.
		if (cursorSequenceNo != null) {
			sql.append(" AND analysis_sequence_no < ?");
			args.add(cursorSequenceNo);
		}
		sql.append(" ORDER BY analysis_sequence_no DESC LIMIT ?");
		args.add(limit);
		return jdbcTemplate.queryForList(sql.toString(), Integer.class, args.toArray());
	}

	@Override
	public List<EventRow> findEvents(UUID managerId, UUID cohortId, UUID traineeId,
			String eventType, List<Integer> sequenceNos) {
		if (sequenceNos.isEmpty()) {
			return List.of();
		}
		List<Object> args = new ArrayList<>(List.of(managerId, cohortId, traineeId));
		StringBuilder sql = new StringBuilder("""
				SELECT assessment_round_id, project_id, analysis_sequence_no, round_name, project_name,
				  team_id_at_round, team_name_at_round, round_activity_start_at, round_activity_end_at,
				  event_id, event_type, occurred_at, source_entity_type, source_entity_id,
				  source_status, session_id, is_expandable, detail_action_code, payload::text,
				  row_aggregation_status, is_stale, as_of_at
				FROM manager_trainee_detail_timeline_view""" + SCOPE);
		appendTypeFilter(sql, args, eventType);
		sql.append(" AND analysis_sequence_no IN (")
				.append("?, ".repeat(sequenceNos.size() - 1))
				.append("?)");
		args.addAll(sequenceNos);
		// 회차는 최신 차수부터, 회차 안에서는 일어난 순서대로다.
		sql.append(" ORDER BY analysis_sequence_no DESC, occurred_at, event_type_order, event_id");
		return jdbcTemplate.query(sql.toString(), this::mapRow, args.toArray());
	}

	private void appendTypeFilter(StringBuilder sql, List<Object> args, String eventType) {
		if (eventType != null) {
			sql.append(" AND event_type = ?");
			args.add(eventType);
		}
	}

	private EventRow mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new EventRow(
				rs.getObject("assessment_round_id", UUID.class),
				rs.getObject("project_id", UUID.class),
				integer(rs, "analysis_sequence_no"),
				rs.getString("round_name"),
				rs.getString("project_name"),
				rs.getObject("team_id_at_round", UUID.class),
				rs.getString("team_name_at_round"),
				time(rs.getTimestamp("round_activity_start_at")),
				time(rs.getTimestamp("round_activity_end_at")),
				rs.getObject("event_id", UUID.class),
				rs.getString("event_type"),
				time(rs.getTimestamp("occurred_at")),
				rs.getString("source_entity_type"),
				rs.getObject("source_entity_id", UUID.class),
				rs.getString("source_status"),
				rs.getObject("session_id", UUID.class),
				rs.getBoolean("is_expandable"),
				rs.getString("detail_action_code"),
				rs.getString("payload"),
				rs.getString("row_aggregation_status"),
				rs.getBoolean("is_stale"),
				time(rs.getTimestamp("as_of_at")));
	}

	private Integer integer(ResultSet rs, String name) throws SQLException {
		int value = rs.getInt(name);
		return rs.wasNull() ? null : value;
	}

	private OffsetDateTime time(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
	}
}
