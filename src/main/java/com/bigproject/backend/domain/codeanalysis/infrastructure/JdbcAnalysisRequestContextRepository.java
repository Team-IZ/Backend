package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.FocusItem;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.RequirementItem;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient.TeachItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /analyses} 요청 본문 중 {@code focusItems}·{@code requirements}·{@code teaches}를
 * 채우는 재료를 읽는다.
 *
 * <p>제출 시점(분석 전)에 필요한 조회라 {@code assessment_problem} 같은 분석 결과물을 거치지 않는다 —
 * {@code JdbcReportPayloadRepository.findTeaches}(리포트 생성용)는 이미 만들어진 문제에서 거슬러
 * 올라가지만, 여기는 프로젝트·회차가 확정한 <b>검증 대상 자체</b>를 직접 읽는다.
 *
 * <p>{@code curriculumVersionId}를 채우는 메서드는 없다 — AI가 이 값을 읽는 코드가 없다고 확인돼
 * (2026-08-10) 요청 계약에서 아예 빠졌다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcAnalysisRequestContextRepository {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	/** {@code active=TRUE}만, 프로젝트 안에서 표시 순서({@code sequence_no})대로. */
	public List<RequirementItem> findRequirements(UUID projectId) {
		return jdbcTemplate.query("""
				SELECT requirement_id, title
				  FROM project_requirement
				 WHERE project_id = ? AND active = TRUE
				 ORDER BY sequence_no
				""", (rs, rowNum) -> new RequirementItem(
				rs.getString("requirement_id"), rs.getString("title")
		), projectId);
	}

	/**
	 * 이 회차에 적용된 질문 초점 집합의 <b>현재 유효 버전</b> 하나. 정의서 제약: "회차별 현재 유효
	 * version_no는 하나만 존재해야 한다" — {@code effective_to IS NULL}이 그 버전이다.
	 *
	 * <p>지금은 {@code TEAM_SHARED_PROBLEM}만 다루므로 이 메서드를 부르는 곳이 없다 — 개인 모드(P5)를
	 * 만들 때 쓴다.
	 */
	public List<FocusItem> findFocusItems(UUID assessmentRoundId) {
		return jdbcTemplate.query("""
				SELECT qfi.question_focus_item_id, qfi.name, qfi.description
				  FROM assessment_round_question_focus arqf
				  JOIN question_focus_item qfi ON qfi.question_focus_item_id = arqf.question_focus_item_id
				 WHERE arqf.assessment_round_id = ?
				   AND arqf.effective_to IS NULL
				""", (rs, rowNum) -> new FocusItem(
				rs.getString("question_focus_item_id"), rs.getString("name"), rs.getString("description")
		), assessmentRoundId);
	}

	/**
	 * 이 회차가 검증할 개념들. 회차가 자기만의 개념 집합을 지정했으면
	 * ({@code project_assessment_round.concept_set_id}) 그것을, 없으면 프로젝트의 ACTIVE
	 * {@code project_verification_concept_set}을 쓴다.
	 *
	 * <p>{@code unitId}는 리포트 생성 요청({@code JdbcReportPayloadRepository.findTeaches})과 같은
	 * 컨벤션이다 — {@code curriculum_teaches_mapping.section_id}.
	 *
	 * <p>{@code TEAM_SHARED_PROBLEM}에서 빈 결과는 <b>호출부가 AI를 부르기 전에 걸러야 한다</b> —
	 * AI가 teaches 없는 팀 모드 요청을 거부한다(2026-08-10 확인).
	 */
	public List<TeachItem> findTeaches(UUID assessmentRoundId, UUID projectId) {
		return jdbcTemplate.query("""
				WITH concept_set AS (
				    SELECT COALESCE(
				        (SELECT pr.concept_set_id FROM project_assessment_round pr
				          WHERE pr.assessment_round_id = ?),
				        (SELECT pvcs.concept_set_id FROM project_verification_concept_set pvcs
				          WHERE pvcs.project_id = ? AND pvcs.status = 'ACTIVE'
				          ORDER BY pvcs.version_no DESC LIMIT 1)
				    ) AS concept_set_id
				)
				SELECT t.teaches_id,
				       t.canonical_name,
				       m.section_id,
				       COALESCE(m.source_pages, '[]'::jsonb)::text AS source_pages
				  FROM concept_set cs
				  JOIN project_verification_concept pvc ON pvc.concept_set_id = cs.concept_set_id
				  JOIN teaches t ON t.teaches_id = pvc.teaches_id
				  LEFT JOIN curriculum_teaches_mapping m ON m.mapping_id = pvc.source_mapping_id
				 ORDER BY pvc.sequence_no
				""", (rs, rowNum) -> new TeachItem(
				rs.getString("teaches_id"),
				rs.getString("canonical_name"),
				rs.getString("section_id"),
				toIntList(rs.getString("source_pages"))
		), assessmentRoundId, projectId);
	}

	private List<Integer> toIntList(String json) {
		if (json == null) {
			return List.of();
		}
		JsonNode node = objectMapper.readTree(json);
		List<Integer> values = new ArrayList<>();
		node.forEach(element -> values.add(element.asInt()));
		return values;
	}
}
