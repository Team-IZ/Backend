package com.bigproject.backend.domain.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * report_evidence 테이블 매핑 엔티티. <b>개인 리포트의 개념 카드 1장</b>이다.
 *
 * <h2>이 행이 없으면 리포트가 빈 껍데기다</h2>
 *
 * <p>{@code trainee_report_problem_view}의 구동 조인이 <b>INNER JOIN</b>이다.
 * <pre>
 * FROM report rpt
 * JOIN report_snapshot rs  ON rs.report_id = rpt.report_id AND rs.is_active
 * JOIN report_evidence re  ON re.snapshot_id = rs.snapshot_id
 * </pre>
 * {@code JdbcTraineeReportQueryRepository.findStageAnswers}(화면 {@code qa[]})도 이 테이블을 거쳐
 * {@code problem_stage}로 조인한다. 즉 {@code report_snapshot.summary_payload}만 채우면
 * <b>{@code concepts[]}도 {@code qa[]}도 0건</b>이 되고, 화면은 `발행됨`인데 내용이 없는 리포트를 그린다.
 *
 * <h2>문제 1건당 정확히 1행</h2>
 *
 * <p>뷰에 집계가 없어 <b>evidence 행 하나가 화면의 개념 카드 하나</b>가 된다. 축(L1~L4)마다 행을 쓰면
 * 같은 개념이 네 번 뜬다. 그래서 문제당 한 행만 쓰고, {@code problem_stage_id}로는
 * <b>막힌 축</b>(첫 미통과 축, 전부 통과했으면 L4)의 stage를 가리킨다 — 학생이 다시 볼 지점이 거기다.
 * 이 모양의 근거는 {@code docs/dummy-data/enrich-report-domain.sql} §2다.
 *
 * <h2>매핑하지 않는 컬럼</h2>
 *
 * <p>{@code participant_*}·{@code baseline_value}·{@code outcome_value}·{@code is_joint_top} 등
 * 기수·반 단위 리포트 전용 컬럼 12개는 필드로 두지 않는다. 전부 nullable이고 개인 리포트에서는
 * 채울 값이 없다 — 매핑만 늘리면 무엇이 이 리포트의 관심사인지 흐려진다.
 */
@Getter
@Entity
@Table(name = "report_evidence")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportEvidence {

	@Id
	@UuidGenerator
	@Column(name = "evidence_id", updatable = false, nullable = false)
	private UUID evidenceId;

	@Column(name = "snapshot_id", nullable = false, updatable = false)
	private UUID snapshotId;

	@Column(name = "problem_id", updatable = false)
	private UUID problemId;

	/** 막힌 축의 stage. 화면 {@code qa[]}가 이 값으로 problem_stage를 찾는다. */
	@Column(name = "problem_stage_id", updatable = false)
	private UUID problemStageId;

	@Enumerated(EnumType.STRING)
	@Column(name = "evidence_category", nullable = false, updatable = false, length = 100)
	private ReportEvidenceCategory evidenceCategory;

	/**
	 * 뷰가 {@code COALESCE(re.subject_display_snapshot->>'conceptName', t.canonical_name)}로 읽는다.
	 * 키 이름이 {@code conceptName}이 아니면 조용히 teaches 이름으로 떨어진다.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "subject_display_snapshot", updatable = false)
	private String subjectDisplaySnapshot;

	@Column(name = "axis_code", updatable = false, length = 10)
	private String axisCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "decision_code", updatable = false, length = 100)
	private ReportEvidenceDecision decisionCode;

	/** 화면의 `결과 설명`. 뷰의 {@code result_explanation}이다. */
	@Column(name = "evidence_summary", updatable = false, columnDefinition = "text")
	private String evidenceSummary;

	/** 화면의 `답변 발췌`. 뷰의 {@code answer_excerpt}다. */
	@Column(name = "quote_excerpt", updatable = false, columnDefinition = "text")
	private String quoteExcerpt;

	/** 개념 카드 정렬 순서. DB CHECK: NULL이거나 > 0. */
	@Column(name = "display_order", updatable = false)
	private Integer displayOrder;

	/** NOT NULL. 이 근거가 어떤 조회 조건에서 산출됐는지의 스냅샷이다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "filter_snapshot", nullable = false, updatable = false)
	private String filterSnapshot;

	/** DB CHECK: NULL이거나 > 0. */
	@Column(name = "policy_version", updatable = false)
	private Integer policyVersion;

	/**
	 * NOT NULL. 화면이 여기서 {@code curriculumLocation}과 {@code reviewBeforeAfterItems}를 꺼낸다.
	 *
	 * <p>🔴 {@code curriculumLocation}의 하위 키는 <b>{@code chapter}·{@code pages}·{@code title}</b>
	 * 셋이어야 한다. {@code TraineeReportServiceImpl.curriculumRef()}가 그 이름으로 읽고,
	 * 어긋나면 예외 없이 조용히 null이 된다.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "trace_payload", nullable = false, updatable = false)
	private String tracePayload;

	private ReportEvidence(UUID snapshotId, UUID problemId, UUID problemStageId,
			ReportEvidenceCategory evidenceCategory, String subjectDisplaySnapshot, String axisCode,
			ReportEvidenceDecision decisionCode, String evidenceSummary, String quoteExcerpt,
			Integer displayOrder, String filterSnapshot, Integer policyVersion, String tracePayload) {
		this.snapshotId = snapshotId;
		this.problemId = problemId;
		this.problemStageId = problemStageId;
		this.evidenceCategory = evidenceCategory;
		this.subjectDisplaySnapshot = subjectDisplaySnapshot;
		this.axisCode = axisCode;
		this.decisionCode = decisionCode;
		this.evidenceSummary = evidenceSummary;
		this.quoteExcerpt = quoteExcerpt;
		this.displayOrder = displayOrder;
		this.filterSnapshot = filterSnapshot;
		this.policyVersion = policyVersion;
		this.tracePayload = tracePayload;
	}

	/**
	 * 개인 리포트의 개념 카드 1장을 만든다.
	 *
	 * <p>{@link ReportEvidenceCategory#RESULT_EXPLANATION}으로 고정한다. 뷰가 한 행에서 설명·발췌·
	 * 교안 위치를 모두 읽으므로 세 범주로 나눌 이유가 없고, 나누면 개념이 세 번 뜬다.
	 *
	 * <p>{@code display_order}는 0 이하를 CHECK가 막는다. 개념 순번이 0부터 오는 경우가 있어
	 * 여기서 1 미만을 NULL로 떨어뜨린다 — 정렬이 흐트러지는 편이 저장 실패보다 낫다.
	 */
	public static ReportEvidence conceptCard(UUID snapshotId, UUID problemId, UUID problemStageId,
			String conceptDisplaySnapshot, String axisCode, ReportEvidenceDecision decisionCode,
			String evidenceSummary, String quoteExcerpt, Integer displayOrder,
			String filterSnapshot, Integer policyVersion, String tracePayload) {
		return new ReportEvidence(snapshotId, problemId, problemStageId,
				ReportEvidenceCategory.RESULT_EXPLANATION, conceptDisplaySnapshot, axisCode, decisionCode,
				evidenceSummary, quoteExcerpt,
				displayOrder == null || displayOrder < 1 ? null : displayOrder,
				filterSnapshot, policyVersion, tracePayload);
	}
}
