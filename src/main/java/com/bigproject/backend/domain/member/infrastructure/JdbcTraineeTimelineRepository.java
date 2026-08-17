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

	/**
	 * 32차 R9② — <b>뷰를 한 번만 훑는다.</b>
	 *
	 * <p>종전에는 전체 건수·회차 차수·이벤트를 각각 물었고 셋 다 같은 뷰를 바깥 {@code WHERE}로만
	 * 걸렀다. 뷰가 그 조건을 안으로 밀어 넣지 못하면 <b>같은 계산이 세 번</b> 일어난다.
	 * 프론트 실측에서 {@code size=1}로 줄여도 11.7초가 그대로였던 것이 그 고정 비용이다.
	 *
	 * <h2>{@code AS MATERIALIZED}가 핵심이다</h2>
	 *
	 * <p>PostgreSQL 12부터 CTE는 기본이 인라인이라, 그냥 {@code WITH}로 묶으면 참조하는 자리마다
	 * 뷰가 다시 펼쳐져 <b>합친 의미가 없어진다.</b> {@code MATERIALIZED}를 명시해야 한 번 계산한
	 * 결과를 세 곳이 공유한다.
	 *
	 * <h2>페이지 경계</h2>
	 *
	 * <p>{@code page}가 회차 차수를 {@code size + 1}개까지 읽어 다음 페이지 유무를 함께 판정한다.
	 * 본문은 <b>앞에서 {@code size}개</b>만 조인하므로, 더 읽은 한 건이 결과에 섞이지 않는다.
	 *
	 * <p>{@code total_elements}는 {@code scoped} 전체를 센다 — 페이지와 무관한 값이라
	 * {@code page} 조인 밖에서 스칼라로 뽑는다.
	 */
	@Override
	public TimelinePage findPage(UUID managerId, UUID cohortId, UUID traineeId,
			String eventType, Integer cursorSequenceNo, int size) {

		List<Object> args = new ArrayList<>(List.of(managerId, cohortId, traineeId));
		StringBuilder scoped = new StringBuilder(
				"SELECT * FROM manager_trainee_detail_timeline_view" + SCOPE);
		appendTypeFilter(scoped, args, eventType);

		StringBuilder page = new StringBuilder(
				"SELECT DISTINCT analysis_sequence_no FROM scoped");
		// 커서는 차수 하나다. 회차 정렬 키가 차수이므로 복합 키가 필요 없다.
		if (cursorSequenceNo != null) {
			page.append(" WHERE analysis_sequence_no < ?");
			args.add(cursorSequenceNo);
		}
		page.append(" ORDER BY analysis_sequence_no DESC LIMIT ?");
		args.add(size + 1);

		String sql = "WITH scoped AS MATERIALIZED (" + scoped + "),\n"
				+ "page AS (" + page + "),\n"
				+ """
				kept AS (SELECT analysis_sequence_no FROM page ORDER BY analysis_sequence_no DESC LIMIT ?)
				SELECT (SELECT COUNT(*) FROM scoped)                         AS total_elements,
				       (SELECT COUNT(*) FROM page) > (SELECT COUNT(*) FROM kept) AS has_next,
				       s.assessment_round_id, s.project_id, s.analysis_sequence_no,
				       s.round_name, s.project_name,
				       s.team_id_at_round, s.team_name_at_round,
				       s.round_activity_start_at, s.round_activity_end_at,
				       s.event_id, s.event_type, s.occurred_at,
				       s.source_entity_type, s.source_entity_id,
				       s.source_status, s.session_id, s.is_expandable, s.detail_action_code,
				       s.payload::text AS payload,
				       s.row_aggregation_status, s.is_stale, s.as_of_at
				FROM scoped s
				JOIN kept k ON k.analysis_sequence_no = s.analysis_sequence_no
				ORDER BY s.analysis_sequence_no DESC, s.occurred_at, s.event_type_order, s.event_id
				""";
		args.add(size);

		List<EventRow> rows = new ArrayList<>();
		// 집계 둘은 모든 행에 같은 값으로 실려 온다. 행이 하나도 없으면 별도로 세어야 한다.
		int[] totalElements = {-1};
		boolean[] hasNext = {false};
		jdbcTemplate.query(sql, rs -> {
			totalElements[0] = rs.getInt("total_elements");
			hasNext[0] = rs.getBoolean("has_next");
			rows.add(mapRow(rs, rows.size()));
		}, args.toArray());

		if (totalElements[0] >= 0) {
			return new TimelinePage(totalElements[0], List.copyOf(rows), hasNext[0]);
		}
		// 이 페이지에 그릴 회차가 없다. 그래도 머리글의 전체 건수는 필요하다 —
		// 필터가 이번 페이지만 비게 했을 수 있어서 0으로 단정하면 안 된다.
		return new TimelinePage(countEvents(managerId, cohortId, traineeId, eventType), List.of(), false);
	}

	/** 페이지가 비었을 때만 쓴다. 정상 경로는 {@link #findPage}가 한 번에 가져온다. */
	private int countEvents(UUID managerId, UUID cohortId, UUID traineeId, String eventType) {
		List<Object> args = new ArrayList<>(List.of(managerId, cohortId, traineeId));
		StringBuilder sql = new StringBuilder(
				"SELECT COUNT(*) FROM manager_trainee_detail_timeline_view" + SCOPE);
		appendTypeFilter(sql, args, eventType);
		Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args.toArray());
		return count == null ? 0 : count;
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
