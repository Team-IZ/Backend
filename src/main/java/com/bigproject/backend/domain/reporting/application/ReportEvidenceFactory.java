package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.domain.ReportEvidence;
import com.bigproject.backend.domain.reporting.domain.ReportEvidenceDecision;
import com.bigproject.backend.domain.reporting.infrastructure.JdbcReportPayloadRepository.ConceptContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AI 응답 + 세션 기록 → {@code report_evidence} 한 행.
 *
 * <h2>이 클래스가 없으면 리포트가 빈 껍데기로 발행된다</h2>
 *
 * <p>{@code trainee_report_problem_view}가 {@code report_evidence}를 INNER JOIN하고, 화면 {@code qa[]}도
 * 이 테이블을 거쳐 {@code problem_stage}를 찾는다. {@code report_snapshot.summary_payload}만 채우면
 * {@code concepts[]}·{@code qa[]}가 모두 0건이 된다({@link ReportEvidence} javadoc 참고).
 *
 * <h2>문제 1건 = 카드 1장</h2>
 *
 * <p>뷰에 집계가 없어 evidence 행 하나가 개념 카드 하나다. 축마다 행을 쓰면 같은 개념이 네 번 뜬다.
 */
@Component
@RequiredArgsConstructor
public class ReportEvidenceFactory {

	/** 산출 정책 버전. {@code ck_report_evidence_policy_version}이 0 이하를 막는다. */
	private static final int POLICY_VERSION = 1;

	private final ObjectMapper objectMapper;

	/**
	 * 개념 카드 1장을 만든다.
	 *
	 * @param result AI {@code GET /reports/{jobId}}의 {@code result}. 실패했으면 null이다 —
	 *               그때도 카드는 만든다. 세션 기록만으로도 도달 단계와 답변 발췌는 사실이고,
	 *               카드가 없으면 그 문제가 화면에서 <b>통째로 사라진다.</b>
	 */
	public ReportEvidence create(UUID snapshotId, UUID problemId, ConceptContext context,
			JsonNode result, UUID cohortId, UUID assessmentRoundId) {

		int reachedLevel = reachedLevel(result, context);

		return ReportEvidence.conceptCard(
				snapshotId,
				problemId,
				context.problemStageId(),
				conceptSnapshot(context),
				context.axisCode(),
				decision(result, reachedLevel),
				summary(result, context, reachedLevel),
				context.quoteExcerpt(),
				context.displayOrder(),
				filterSnapshot(cohortId, assessmentRoundId),
				POLICY_VERSION,
				tracePayload(context, reachedLevel)
		);
	}

	/**
	 * 뷰가 {@code subject_display_snapshot->>'conceptName'}으로 읽는다. 키 이름이 다르면 조용히
	 * {@code teaches.canonical_name} 조인 결과로 떨어지는데, 그쪽은 개념 연결이 끊긴 문제에서 NULL이다.
	 */
	private String conceptSnapshot(ConceptContext context) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("conceptName", context.conceptName());
		return ReportPayloads.toJson(objectMapper, node);
	}

	/** NOT NULL 컬럼. 이 근거가 어떤 범위에서 산출됐는지를 남긴다. */
	private String filterSnapshot(UUID cohortId, UUID assessmentRoundId) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("reportType", "TRAINEE_FINAL");
		node.put("cohortId", cohortId == null ? null : cohortId.toString());
		node.put("roundId", assessmentRoundId == null ? null : assessmentRoundId.toString());
		return ReportPayloads.toJson(objectMapper, node);
	}

	/**
	 * NOT NULL 컬럼. 화면이 여기서 교안 위치와 다시 보기 항목을 꺼낸다.
	 *
	 * <p>🔴 {@code curriculumLocation}의 하위 키는 <b>{@code chapter}·{@code pages}·{@code title}</b>
	 * 여야 한다. {@code TraineeReportServiceImpl.curriculumRef()}가 그 이름으로 읽고, 어긋나면
	 * 예외 없이 null이 된다. 교안 매핑이 없으면 키 자체를 만들지 않고 빈 객체를 둔다 —
	 * 절반만 채우면 화면이 `p.null–null` 같은 값을 그린다.
	 *
	 * <p>{@code reviewBeforeAfterItems}는 빈 배열로 둔다. 다시 보기(REVIEW) 응시를 만들 수 없는
	 * 상태이고({@code measurement_attempt}의 (회차, 교육생) 전체 UNIQUE), 그건 이 배치의 범위 밖이다.
	 */
	private String tracePayload(ConceptContext context, int reachedLevel) {
		ObjectNode node = objectMapper.createObjectNode();

		ObjectNode location = objectMapper.createObjectNode();
		if (context.sectionSequenceNo() != null) {
			location.put("chapter", context.sectionSequenceNo() + "장");
		}
		if (context.pageStart() != null && context.pageEnd() != null) {
			// en-dash(U+2013). 시드가 쓰는 표기와 맞춘다.
			location.put("pages", "p." + context.pageStart() + "–" + context.pageEnd());
		}
		if (context.sectionTitle() != null) {
			location.put("title", context.sectionTitle());
		}
		node.set("curriculumLocation", location);
		node.set("reviewBeforeAfterItems", objectMapper.createArrayNode());

		node.put("reachedLevel", reachedLevel);
		node.put("blockedAxis", context.axisCode());
		node.put("stageStatus", context.stageStatus());
		node.put("answeredSlots", context.answeredSlots());
		return ReportPayloads.toJson(objectMapper, node);
	}

	/**
	 * 도달 단계. AI {@code result.problem.reachedStage}를 우선한다 — 채점 근거를 쥔 쪽이 AI다.
	 * 응답이 없거나 실패했으면 {@code problem_stage}에서 센 값으로 떨어진다.
	 */
	private int reachedLevel(JsonNode result, ConceptContext context) {
		JsonNode reached = result == null ? null : result.path("problem").path("reachedStage");
		return reached != null && reached.isInt() ? reached.asInt() : context.reachLevel();
	}

	/**
	 * 다시 보기 대상인가. AI {@code result.retest}가 1차 근거다 —
	 * 스펙이 "L1과 L2를 둘 다 통과하지 못하면 재시험"으로 정의한 값이다.
	 *
	 * <p>응답이 없으면 같은 정의를 도달 단계로 흉내 낸다. 전부 {@code REVIEW_REQUIRED}로 두면
	 * 화면이 개념 카드를 모두 경고색으로 칠해 우선순위가 사라진다.
	 */
	private ReportEvidenceDecision decision(JsonNode result, int reachedLevel) {
		JsonNode retest = result == null ? null : result.path("retest");
		boolean required = retest != null && retest.isBoolean() ? retest.asBoolean() : reachedLevel < 2;
		return required ? ReportEvidenceDecision.REVIEW_REQUIRED : ReportEvidenceDecision.NOT_REQUIRED;
	}

	/**
	 * 화면의 `결과 설명`. AI {@code narrative.summary}가 이 문제 하나에 대한 총평이다
	 * (리포트가 문제 단위라 summary도 문제 단위다).
	 *
	 * <p>서술 생성이 실패했거나({@code narrativeFailed}) 응답이 없으면 도달 단계로 문장을 만든다.
	 * 설명을 비워 두면 뷰의 {@code explanation_status}가 {@code EMPTY}가 되어 화면이
	 * "설명 없음"을 그린다 — 점수는 나왔는데 설명만 없는 상태를 그렇게 알리는 것이 맞다.
	 */
	private String summary(JsonNode result, ConceptContext context, int reachedLevel) {
		String narrative = result == null ? null : result.path("narrative").path("summary").asString(null);
		if (narrative != null && !narrative.isBlank()) {
			return narrative;
		}
		return fallbackSummary(context.conceptName(), reachedLevel);
	}

	/**
	 * AI 서술이 없을 때의 대체 문장.
	 *
	 * <h2>🔴 문장은 "통과한 축 다음이 무엇인가"로 만든다</h2>
	 *
	 * <p>축 순서는 확정 채점 모델(2026-08-11)이다 —
	 * <b>1단 코드 이해 · 2단 설계 논리 · 3단 대안 비교 · 4단 반례 대응.</b> 그래서 3단은
	 * "대안까지 비교했고 반례에서 막혔다"이고, 그 반대가 아니다.
	 *
	 * <p>종전 문구는 3단과 4단의 축이 서로 뒤바뀌어 있었다(2026-08-13 교정). 이 문장은 화면에
	 * {@code level} 배지 바로 옆에 놓이므로, 어긋나면 <b>같은 카드가 서로 다른 두 말을 한다</b> —
	 * 20차 R2에서 프론트가 {@code said}와 {@code level}을 대조해 결함을 찾아낸 것도 그래서다.
	 */
	private static String fallbackSummary(String conceptName, int reachedLevel) {
		String name = conceptName == null ? "이 개념" : conceptName;
		return switch (reachedLevel) {
			case 0 -> name + " 은(는) 네 축 가운데 어느 것도 통과하지 못했습니다.";
			case 1 -> name + " 에서 무엇을 하는지까지는 설명했지만, 왜 그 구조를 선택했는지에서 막혔습니다.";
			case 2 -> name + " 에서 선택 이유까지 설명했지만, 같은 요구사항을 다른 방법으로 구현하는 "
					+ "대안은 제시하지 못했습니다.";
			case 3 -> name + " 에서 대안까지 비교했지만, 어떤 상황에서 이 방식이 깨지는지는 답하지 "
					+ "못했습니다.";
			default -> name + " 은(는) 네 축을 모두 통과했습니다.";
		};
	}
}
