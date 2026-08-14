package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.domain.InterviewListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * MG-03 면담 목록 SQL.
 *
 * <p>본체는 {@code manager_interview_list_view}이고 그 위에 LATERAL 넷을 얹는다.
 * 뷰를 두고 조인을 다시 짜면 담당 반 스코프 규칙이 두 벌이 되어 어긋난다 —
 * "목록에 보이는데 열면 404"가 나는 전형적인 자리다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcInterviewListRepository implements InterviewListRepository {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 뷰 + LATERAL 넷. 목록과 집계가 같은 FROM 절을 쓰도록 상수로 뽑았다 —
	 * 둘이 어긋나면 "개수는 5인데 행은 4개"가 된다.
	 *
	 * <p>사유(rsn)를 상관 LATERAL로 둔 이유: 케이스마다 활성 사유가 0~N건이라 직접 조인하면
	 * 행이 곱해진다. LATERAL은 살아남은 행에 대해서만 실행되므로 회차 필터가 자연히 먼저 걸린다.
	 */
	private static final String FROM_CLAUSE = """
			FROM manager_interview_list_view v
			LEFT JOIN app_user ex
			       ON ex.user_id = v.excluded_by
			LEFT JOIN LATERAL (
			    SELECT ARRAY_AGG(x.reason_code       ORDER BY x.detected_at) AS codes,
			           ARRAY_AGG(x.reason_summary    ORDER BY x.detected_at) AS summaries,
			           ARRAY_AGG(x.evaluation_status ORDER BY x.detected_at) AS evaluations
			    FROM interview_candidate_reason x
			    WHERE x.candidate_id = v.candidate_id
			      AND x.reason_status = 'ACTIVE'
			) rsn ON TRUE
			LEFT JOIN LATERAL (
			    SELECT x.attempt_id
			    FROM measurement_attempt x
			    WHERE x.assessment_round_id = v.assessment_round_id
			      AND x.user_id             = v.target_user_id
			      AND x.attempt_type        = 'INITIAL'
			    ORDER BY x.attempt_sequence_no DESC
			    LIMIT 1
			) att ON TRUE
			LEFT JOIN LATERAL (
			    SELECT x.status, x.opening_remark_text
			    FROM interview_brief x
			    WHERE x.interview_id = v.interview_id
			    ORDER BY CASE WHEN x.status = 'DRAFT'     THEN 0
			                  WHEN x.status = 'CONFIRMED' THEN 1
			                  ELSE 2 END,
			             x.version_no DESC
			    LIMIT 1
			) b ON TRUE
			LEFT JOIN LATERAL (
			    SELECT x.next_action
			    FROM interview_activity x
			    WHERE x.interview_id = v.interview_id
			      AND x.next_action IS NOT NULL
			    ORDER BY x.occurred_at DESC
			    LIMIT 1
			) act ON TRUE
			WHERE v.manager_user_id     = ?
			  AND v.org_id              = ?
			  AND v.assessment_round_id = ?
			""";

	/**
	 * 화면 상태 3종으로 접는 식. 목록·집계·필터가 모두 이 식을 써야 세 곳이 어긋나지 않는다.
	 *
	 * <p>후보와 면담 두 테이블에 걸쳐 있다 — 제외는 후보 상태이고 예정·종결은 면담 상태다.
	 * 면담 행이 아직 없는 후보(ELIGIBLE)도 화면에서는 `예정`이다.
	 */
	private static final String STATUS_EXPR = """
			CASE WHEN v.candidate_status = 'EXCLUDED' THEN 'EXCLUDED'
			     WHEN v.interview_status = 'COMPLETED' THEN 'DONE'
			     ELSE 'PLANNED' END
			""";

	/**
	 * 화면 위험 유형 4종으로 접는 식.
	 *
	 * <p>🔴 <b>DB 5종과 화면 4종이 1:1이 아니다.</b> 세 개는 이름만 다르고
	 * ({@code INVALID_ATTEMPT}·{@code PERSISTENT_LOW}·{@code STAGE_DECLINE}),
	 * 화면의 `관찰`(OBSERVE)은 DB에 코드가 없다 — 1차 회차라 비교 대상이 없는 상태를
	 * {@code evaluation_status='NOT_APPLICABLE'}로 표현한다(테이블 COMMENT:
	 * "첫 미니프로젝트 단계 하락은 NOT_APPLICABLE/FIRST_MINI_PROJECT").
	 *
	 * <p>⚠️ 이 해석은 DB 담당자 확인 대기 중이다(제안서 B-1). 확인 결과에 따라 이 식만 고치면 된다.
	 *
	 * <p>{@code CONTRIBUTION_UNDERSTANDING_GAP}·{@code LOW_PARTICIPATION}은 화면에 배지가 없어
	 * 우선순위에서 뒤로 두고 `관찰`로 접는다 — 판정 산식 자체가 아직 없어(제안서 D) 실제로 오지 않는다.
	 */
	private static final String RISK_EXPR = """
			CASE WHEN 'INVALID_ATTEMPT' = ANY(rsn.codes) THEN 'INVALID'
			     WHEN 'NOT_APPLICABLE'  = ANY(rsn.evaluations) THEN 'OBSERVE'
			     WHEN 'PERSISTENT_LOW'  = ANY(rsn.codes) THEN 'LOW_PERSISTENT'
			     WHEN 'STAGE_DECLINE'   = ANY(rsn.codes) THEN 'DECLINE'
			     ELSE 'OBSERVE' END
			""";

	@Override
	public List<InterviewListRow> findCases(InterviewListQuery query) {
		/*
		 * 화면 상태·위험 유형을 SELECT에서 함께 계산해 내려보낸다. 필터·정렬·집계가 같은 식을
		 * 쓰므로 표시 값만 Java에서 다시 계산하면 어긋날 자리가 생긴다 — 한 곳에서만 정한다.
		 */
		StringBuilder sql = new StringBuilder("SELECT (" + STATUS_EXPR + ") AS screen_status,\n"
				+ "       (" + RISK_EXPR + ") AS screen_risk_type,\n");
		sql.append("""
				       v.candidate_id,
				       v.target_user_id,
				       v.target_user_name,
				       v.class_id,
				       v.class_name,
				       v.candidate_status,
				       v.interview_status,
				       rsn.codes,
				       rsn.summaries,
				       rsn.evaluations,
				       v.is_first_mini_project,
				       v.validity_review_status,
				       att.attempt_id,
				       b.status AS brief_status,
				       (b.opening_remark_text IS NOT NULL) AS brief_has_content,
				       v.excluded_at,
				       ex.name AS excluded_by_name,
				       v.completed_at,
				       act.next_action
				""");
		sql.append(FROM_CLAUSE);

		List<Object> args = new ArrayList<>();
		args.add(query.managerUserId());
		args.add(query.orgId());
		args.add(query.assessmentRoundId());

		/*
		 * 선택 필터는 절을 붙일 때만 파라미터를 넣는다. `(? IS NULL OR col = ?)` 형태로 두면
		 * PostgreSQL이 NULL 파라미터의 타입을 정하지 못해 필터를 안 쓴 호출까지 통째로 실패한다
		 * (JdbcManagedReportQueryRepository가 같은 이유로 같은 방식을 쓴다).
		 */
		if (hasText(query.search())) {
			sql.append("  AND v.target_user_name ILIKE ?\n");
			args.add("%" + query.search().trim() + "%");
		}
		if (hasText(query.status())) {
			sql.append("  AND (").append(STATUS_EXPR).append(") = ?\n");
			args.add(query.status());
		}
		if (hasText(query.riskType())) {
			sql.append("  AND (").append(RISK_EXPR).append(") = ?\n");
			args.add(query.riskType());
		}
		if (query.classId() != null) {
			sql.append("  AND v.class_id = ?\n");
			args.add(query.classId());
		}

		/*
		 * 정렬은 사용자가 못 고른다(정의서 §3). 규칙은 프론트 `sortCases`와 같아야 한다 —
		 * ① 무효 응시는 상태·반과 무관하게 항상 최상단(9-4 "못하는 것보다 안 하는 것이 더 급하다")
		 * ② 상태(예정·제외 먼저, 종결 나중) ③ 반 이름 ④ 이름 가나다순(동점 결정성).
		 *
		 * 정렬을 서버가 갖는 이유: 화면이 페이저 없이 전량을 그대로 그리므로 순서가 곧 화면이다.
		 */
		sql.append("""
				ORDER BY CASE WHEN 'INVALID_ATTEMPT' = ANY(rsn.codes) THEN 0 ELSE 1 END,
				         CASE WHEN v.interview_status = 'COMPLETED' THEN 1 ELSE 0 END,
				         v.class_name,
				         v.target_user_name
				""");

		return jdbcTemplate.query(sql.toString(), this::mapRow, args.toArray());
	}

	@Override
	public List<InterviewCountRow> countByRound(UUID managerUserId, UUID orgId, UUID assessmentRoundId) {
		/*
		 * 상태별·위험 유형별 개수를 한 번에 뽑는다. 화면이 둘 다 필터 옵션 라벨에 싣는데
		 * 쿼리를 나누면 두 값이 서로 다른 순간의 스냅샷이 될 수 있다.
		 */
		String sql = "SELECT (" + STATUS_EXPR + ") AS status, (" + RISK_EXPR + ") AS risk_type, COUNT(*) AS cnt\n"
				+ FROM_CLAUSE
				+ "GROUP BY 1, 2\n";

		return jdbcTemplate.query(sql,
				(rs, rowNum) -> new InterviewCountRow(
						rs.getString("status"),
						rs.getString("risk_type"),
						rs.getLong("cnt")),
				managerUserId, orgId, assessmentRoundId);
	}

	private InterviewListRow mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new InterviewListRow(
				rs.getObject("candidate_id", UUID.class),
				rs.getObject("target_user_id", UUID.class),
				rs.getString("target_user_name"),
				rs.getObject("class_id", UUID.class),
				rs.getString("class_name"),
				rs.getString("screen_status"),
				rs.getString("screen_risk_type"),
				rs.getString("candidate_status"),
				rs.getString("interview_status"),
				toStringList(rs.getArray("codes")),
				toStringList(rs.getArray("summaries")),
				toStringList(rs.getArray("evaluations")),
				rs.getBoolean("is_first_mini_project"),
				rs.getString("validity_review_status"),
				rs.getObject("attempt_id", UUID.class),
				rs.getString("brief_status"),
				rs.getBoolean("brief_has_content"),
				toInstant(rs.getTimestamp("excluded_at")),
				rs.getString("excluded_by_name"),
				toInstant(rs.getTimestamp("completed_at")),
				rs.getString("next_action"));
	}

	/**
	 * 활성 사유가 없으면 배열 자체가 NULL이다(LATERAL의 {@code ARRAY_AGG}가 빈 그룹에서 NULL을 낸다).
	 *
	 * <p>같은 도메인의 다른 리포지토리도 {@code TEXT[]}를 읽으므로 공유한다 —
	 * NULL 배열 처리를 각자 구현하면 한쪽만 빠뜨려 NPE가 난다.
	 */
	static List<String> toStringList(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		Object value = array.getArray();
		if (!(value instanceof Object[] elements)) {
			return List.of();
		}
		return Arrays.stream(elements)
				.map(element -> element == null ? null : element.toString())
				.toList();
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
