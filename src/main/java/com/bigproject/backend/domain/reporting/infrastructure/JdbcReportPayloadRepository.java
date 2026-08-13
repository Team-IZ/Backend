package com.bigproject.backend.domain.reporting.infrastructure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AI {@code POST /reports} 요청 본문의 재료와, 결과를 {@code report_evidence}로 옮길 때 필요한
 * 맥락을 읽는다.
 *
 * <p>세 배열({@code transcript}·{@code analysisDocuments}·{@code teaches})을 DTO가 아니라
 * {@link JsonNode}로 만들어 내보낸다. AI 쪽 타입이 {@code list[dict[str, Any]]}로 열려 있어
 * 고정 스키마가 없고, 백엔드가 자기 DTO로 좁히면 <b>AI가 필드를 추가할 때 조용히 값이 잘린다</b>
 * ({@code ReportGenerationRequest} javadoc과 같은 판단이다).
 *
 * <h2>🔴 이 클래스는 도메인 경계를 넘는다</h2>
 *
 * <p>{@code problem_stage}·{@code assessment_problem}·{@code assessment_session}은
 * <b>Assessment 도메인 소유</b>다. Reporting이 직접 읽는 이유는 리포트 요청 조립이
 * Reporting 몫이기 때문이고(결정 6), 조회만 하고 쓰지 않는다.
 *
 * <p><b>그래서 이 SQL은 남의 스키마 변경에 깨진다.</b> 컴파일도 단위 테스트도 잡지 못한다 —
 * 네이티브 SQL이고 리포지토리가 mock되기 때문이다. 이 위험은 코드가 아니라 합의로 막는다:
 * <b>Assessment가 {@code problem_stage}의 컬럼이나 {@code status} 값 집합을 바꿀 때 통지</b>하기로
 * 했다(2026-08-10 지속 의무 1번). 통지가 오면 이 클래스의 세 질의를 함께 확인해야 한다.
 *
 * <p>깨졌을 때 어떻게 드러나는지도 알아 둘 것 — 예외로 터지지 않고
 * {@link #findConceptContext}가 빈 값을 내면 그 문제의 개념 카드가 <b>화면에서 통째로 사라진다</b>
 * ({@code ReportRunFinalizer}가 {@code log.warn} 후 건너뛴다).
 */
@Repository
@RequiredArgsConstructor
public class JdbcReportPayloadRepository {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	/**
	 * 이 문제의 채점 기록. AI 설명이 <i>"이 문제의 턴만. 점수가 이미 확정된 기록(최대 4단계 × 3시도)"</i>
	 * 이라 {@code problem_stage} 한 행이 곧 한 단계다.
	 *
	 * <p>키 이름을 컬럼명의 camelCase로 맞춘다. AI 응답 {@code result.problem.stages[]}가
	 * {@code questionScore}·{@code firstHintPassed}처럼 정확히 그 형태로 돌아오므로, 보내는 쪽도
	 * 같은 이름을 쓰는 것이 계약에 가장 가깝다.
	 *
	 * <p>답변이 없는 슬롯도 뺴지 않고 null로 보낸다 — "안 물어본 단계"와 "답을 못 한 단계"를
	 * AI가 구분해야 {@code unreachedAxes}를 채울 수 있다.
	 */
	public List<JsonNode> findTranscript(UUID sessionId, UUID problemId) {
		String sql = """
				SELECT axis_code, question_sequence_no, status,
				       question_text, question_answer_text, question_score, question_passed,
				       first_hint_text, first_hint_answer_text, first_hint_score, first_hint_passed,
				       second_hint_text, second_hint_answer_text, second_hint_score, second_hint_passed
				  FROM problem_stage
				 WHERE session_id = ? AND problem_id = ?
				 ORDER BY axis_code, question_sequence_no
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> {
			ObjectNode turn = objectMapper.createObjectNode();
			turn.put("axisCode", rs.getString("axis_code"));
			turn.put("questionSequenceNo", rs.getInt("question_sequence_no"));
			turn.put("status", rs.getString("status"));
			putSlot(turn, rs, "question", "question_text", "question_answer_text",
					"question_score", "question_passed");
			putSlot(turn, rs, "firstHint", "first_hint_text", "first_hint_answer_text",
					"first_hint_score", "first_hint_passed");
			putSlot(turn, rs, "secondHint", "second_hint_text", "second_hint_answer_text",
					"second_hint_score", "second_hint_passed");
			return (JsonNode) turn;
		}, sessionId, problemId);
	}

	/**
	 * 코드 분석 문서. AI 스키마가 {@code [{kind, content}]}인데 <b>{@code kind}의 값 집합이 계약에
	 * 없다.</b> 지금은 문서가 {@code code_analysis.analysis_document} 하나뿐이라
	 * {@code CODE_ANALYSIS} 단일값으로 보낸다 — 값 집합이 정해지면 여기만 고치면 된다.
	 *
	 * <p>분석이 없는 응시({@code code_analysis_id}가 NULL)도 있다. 그때는 빈 배열이고, AI는
	 * 코드 근거 없이 문답만으로 서술한다.
	 */
	public List<JsonNode> findAnalysisDocuments(UUID codeAnalysisId) {
		if (codeAnalysisId == null) {
			return List.of();
		}
		String sql = "SELECT analysis_document::text FROM code_analysis WHERE analysis_id = ?";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> {
			ObjectNode document = objectMapper.createObjectNode();
			document.put("kind", "CODE_ANALYSIS");
			document.set("content", readJson(rs.getString(1)));
			return (JsonNode) document;
		}, codeAnalysisId);
	}

	/**
	 * 이 문제가 참조하는 교안. AI 스키마는 {@code [{id, label, unitId, sourcePages}]}다.
	 *
	 * <p>{@code assessment_problem_reference}의 {@code CURRICULUM_EVIDENCE} 행에서 출발한다 —
	 * <p><b>세 경로를 UNION한다.</b> 처음에는 {@code assessment_problem_reference}의
	 * {@code CURRICULUM_EVIDENCE} 행만 읽었는데, 실제 데이터로 확인해 보니 그 참조가 붙은 문제가
	 * 드물어 <b>{@code teaches}가 대부분 빈 배열로 나갔다.</b> 교안 참조가 비면 AI가 "무엇을 근거로
	 * 더 보라고 할지"를 잃는다. 반면 {@code project_verification_concept}은 문제마다 붙으므로,
	 * 그쪽을 주 경로로 두고 나머지 둘을 합친다.
	 *
	 * <p>{@code sourcePages}는 {@code curriculum_teaches_mapping.source_pages}(JSONB 배열)를 그대로
	 * 옮긴다. 매핑이 없으면 빈 배열이다 — 키를 빼면 AI 쪽에서 필드 자체가 없는 것과
	 * 페이지를 모르는 것을 구분하지 못한다.
	 */
	public List<JsonNode> findTeaches(UUID problemId) {
		String sql = """
				WITH linked AS (
				    -- ① 검증 개념 경로. 문제마다 반드시 하나 붙는 정규 경로다.
				    SELECT pvc.teaches_id, pvc.sequence_no AS ordinal
				      FROM assessment_problem ap
				      JOIN project_verification_concept pvc
				        ON pvc.project_concept_id = ap.project_verification_concept_id
				     WHERE ap.problem_id = ?
				    UNION
				    -- ② v08부터 TEAM_SHARED_PROBLEM 은 개념 ID가 NULL일 수 있다. 그때 개념 연결을
				    --    대신 보존하는 컬럼이 assessment_problem.teaches_id 다.
				    SELECT ap2.teaches_id, 0
				      FROM assessment_problem ap2
				     WHERE ap2.problem_id = ? AND ap2.teaches_id IS NOT NULL
				    UNION
				    -- ③ 근거 참조 경로. DDL CHECK가 이 유형에만 teaches_id를 필수로 건다.
				    SELECT apr.teaches_id, COALESCE(apr.display_order, 0)
				      FROM assessment_problem_reference apr
				     WHERE apr.problem_id = ?
				       AND apr.reference_type = 'CURRICULUM_EVIDENCE'
				       AND apr.teaches_id IS NOT NULL
				)
				SELECT t.teaches_id,
				       t.canonical_name,
				       m.section_id,
				       COALESCE(m.source_pages, '[]'::jsonb)::text AS source_pages
				  FROM linked
				  JOIN teaches t ON t.teaches_id = linked.teaches_id
				  LEFT JOIN LATERAL (
				      SELECT x.section_id, x.source_pages
				        FROM curriculum_teaches_mapping x
				       WHERE x.teaches_id = t.teaches_id
				         AND x.mapping_status = 'ACTIVE'
				       ORDER BY x.sequence_no
				       LIMIT 1
				  ) m ON TRUE
				 GROUP BY t.teaches_id, t.canonical_name, m.section_id, m.source_pages
				 ORDER BY min(linked.ordinal), t.canonical_name
				""";

		return jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> {
			ObjectNode teach = objectMapper.createObjectNode();
			teach.put("id", rs.getString("teaches_id"));
			teach.put("label", rs.getString("canonical_name"));
			teach.put("unitId", rs.getString("section_id"));
			teach.set("sourcePages", readJson(rs.getString("source_pages")));
			return (JsonNode) teach;
		}, problemId, problemId, problemId);
	}

	/**
	 * {@code report_evidence} 한 행을 만드는 데 필요한 맥락.
	 *
	 * <p><b>대표 stage는 "막힌 축"이다.</b> 통과하지 못한 가장 낮은 축, 전부 통과했으면 L4다.
	 * 학생이 다시 볼 지점이 거기이고, 시드({@code enrich-report-domain.sql} §2)가 쓰는 정의와도 같다.
	 *
	 * <p>개념 이름은 두 경로를 COALESCE한다. {@code project_verification_concept}가 정규 경로이지만
	 * v08부터 {@code TEAM_SHARED_PROBLEM}에서 {@code project_verification_concept_id}가 NULL일 수
	 * 있어({@code ck_assessment_problem_problem_scope_2}), 그때는 문제에 직접 붙은
	 * {@code assessment_problem.teaches_id}가 개념 연결을 대신 보존한다.
	 *
	 * <h2>⚠️ 알려진 한계 — 미통과 집합에 진행 중 상태가 없다</h2>
	 *
	 * <p>{@code block_level}은 {@code NOT_PASSED}·{@code NOT_ANSWERED}·{@code NOT_REACHED}만 센다.
	 * stage가 {@code PREPARED}·{@code IN_PROGRESS}로 남아 있으면 그 축은 <b>미통과로 잡히지 않고</b>
	 * {@code block_level}이 NULL이 되어 <b>L4가 대표 축이 된다</b> — 학생이 도달조차 못 한 축을
	 * "여기서 막혔다"고 보여주게 된다.
	 *
	 * <p>정상 경로에서는 생기지 않는다. 대상 선별이 {@code s.status='COMPLETED' AND
	 * ma.status='COMPLETED'}라, <b>세션 종료 로직이 남은 stage를 정리한다는 전제</b>에 기대고 있다.
	 * 그 전제는 Assessment가 지키기로 했고 결과를 통지받기로 했다(2026-08-10 지속 의무 2번).
	 *
	 * <p>{@code IN_PROGRESS}가 남는 설계로 간다는 통지가 오면 <b>아래 CTE의 {@code IN} 목록 한 줄</b>에
	 * 추가하면 된다. 이 한계는 통합본 §6 한계 4로도 등록돼 있다.
	 */
	public Optional<ConceptContext> findConceptContext(UUID sessionId, UUID problemId) {
		String sql = """
				WITH reach AS (
				    SELECT MAX(CASE WHEN status = 'PASSED' THEN SUBSTR(axis_code, 2)::int ELSE 0 END) AS reach_level,
				           -- ⚠️ 이 IN 목록에 PREPARED·IN_PROGRESS 가 없다(한계 4, javadoc 참고).
				           --    남아 있으면 block_level 이 NULL 이 되어 아래 조인이 L4 를 대표로 집는다.
				           --    Assessment 의 세션 종료가 stage 를 정리한다는 전제에 기대고 있는 줄이다.
				           MIN(CASE WHEN status IN ('NOT_PASSED', 'NOT_ANSWERED', 'NOT_REACHED')
				                    THEN SUBSTR(axis_code, 2)::int END)                              AS block_level
				      -- 🔴 problem_stage 는 Assessment 소유다. 클래스 javadoc의 "도메인 경계" 참고.
				      FROM problem_stage
				     WHERE session_id = ? AND problem_id = ?
				)
				SELECT ps.problem_stage_id,
				       ps.axis_code,
				       ps.status,
				       COALESCE(ps.second_hint_answer_text, ps.first_hint_answer_text,
				                ps.question_answer_text)                        AS quote_excerpt,
				       (ps.question_answer_text    IS NOT NULL)::int
				     + (ps.first_hint_answer_text  IS NOT NULL)::int
				     + (ps.second_hint_answer_text IS NOT NULL)::int            AS answered_slots,
				       reach.reach_level,
				       COALESCE(t_pvc.canonical_name, t_ap.canonical_name)      AS concept_name,
				       pvc.sequence_no                                          AS display_order,
				       cs.sequence_no                                           AS section_sequence_no,
				       cs.title                                                 AS section_title,
				       m.page_start,
				       m.page_end
				  FROM reach
				  JOIN problem_stage ps
				    ON ps.session_id = ? AND ps.problem_id = ?
				   AND ps.axis_code = 'L' || COALESCE(reach.block_level, 4)
				  JOIN assessment_problem ap ON ap.problem_id = ps.problem_id
				  LEFT JOIN project_verification_concept pvc
				         ON pvc.project_concept_id = ap.project_verification_concept_id
				  LEFT JOIN teaches t_pvc ON t_pvc.teaches_id = pvc.teaches_id
				  LEFT JOIN teaches t_ap  ON t_ap.teaches_id  = ap.teaches_id
				  LEFT JOIN curriculum_teaches_mapping m ON m.mapping_id = pvc.source_mapping_id
				  LEFT JOIN curriculum_section cs        ON cs.section_id = m.section_id
				 LIMIT 1
				""";

		List<ConceptContext> rows = jdbcTemplate.query(sql, (ResultSet rs, int rowNum) -> new ConceptContext(
				rs.getObject("problem_stage_id", UUID.class),
				rs.getString("axis_code"),
				rs.getString("status"),
				rs.getString("quote_excerpt"),
				rs.getInt("answered_slots"),
				rs.getInt("reach_level"),
				rs.getString("concept_name"),
				(Integer) rs.getObject("display_order"),
				(Integer) rs.getObject("section_sequence_no"),
				rs.getString("section_title"),
				(Integer) rs.getObject("page_start"),
				(Integer) rs.getObject("page_end")
		), sessionId, problemId, sessionId, problemId);

		return rows.stream().findFirst();
	}

	/**
	 * 한 슬롯(질문·1차 힌트·2차 힌트)의 텍스트·답변·점수·통과를 넣는다.
	 *
	 * <p>{@code getInt}·{@code getBoolean}은 NULL을 0·false로 돌려주므로 {@code wasNull()}로 되묻는다.
	 * 그러지 않으면 <b>답하지 않은 슬롯이 0점으로 보인다</b> — 채점 결과가 통째로 달라진다.
	 */
	private static void putSlot(ObjectNode turn, ResultSet rs, String prefix,
			String textColumn, String answerColumn, String scoreColumn, String passedColumn)
			throws SQLException {

		turn.put(prefix + "Text", rs.getString(textColumn));
		turn.put(prefix + "AnswerText", rs.getString(answerColumn));

		int score = rs.getInt(scoreColumn);
		if (rs.wasNull()) {
			turn.putNull(prefix + "Score");
		} else {
			turn.put(prefix + "Score", score);
		}

		boolean passed = rs.getBoolean(passedColumn);
		if (rs.wasNull()) {
			turn.putNull(prefix + "Passed");
		} else {
			turn.put(prefix + "Passed", passed);
		}
	}

	/** JSONB 원문을 트리로 읽는다. 깨진 값이면 null 노드로 떨어뜨린다 — 요청 하나가 통째로 막히지 않게. */
	private JsonNode readJson(String raw) {
		if (raw == null || raw.isBlank()) {
			return objectMapper.nullNode();
		}
		try {
			return objectMapper.readTree(raw);
		} catch (tools.jackson.core.JacksonException exception) {
			return objectMapper.nullNode();
		}
	}

	/**
	 * 개념 카드 1장의 재료.
	 *
	 * @param reachLevel   통과한 가장 높은 축(0~4). 0은 "안 물어본 것"이 아니라 "못한 것"이다.
	 * @param displayOrder 개념 정렬 순서. {@code ck_report_evidence_display_order}가 0 이하를 막는다.
	 */
	public record ConceptContext(
			UUID problemStageId,
			String axisCode,
			String stageStatus,
			String quoteExcerpt,
			int answeredSlots,
			int reachLevel,
			String conceptName,
			Integer displayOrder,
			Integer sectionSequenceNo,
			String sectionTitle,
			Integer pageStart,
			Integer pageEnd
	) {
	}
}
