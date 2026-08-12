package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import com.bigproject.backend.domain.reporting.domain.ReportCompletionStatus;
import com.bigproject.backend.domain.reporting.domain.ReportErrorCode;
import com.bigproject.backend.domain.reporting.domain.ReportException;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.ConceptRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.RoundRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.StageAnswerRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.UnaskedConceptRow;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse.ComparedReachResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse.ConceptReportResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse.CurriculumRefResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse.QaEntryResponse;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse.RoundListItem;
import com.bigproject.backend.domain.reporting.presentation.dto.TraineeReportsResponse.RoundReportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * TR-04 조립. 뷰에서 읽은 행들을 화면 계약({@code types.ts})의 모양으로 맞춘다.
 *
 * <p>여기서 하는 일은 <b>판정이 아니라 번역</b>이다 — 도달 단계·공개 범위 판정은 전부
 * DB(뷰·CHECK)가 이미 내렸고, 이 클래스는 그 값을 화면 어휘로 바꾼다.
 * 판정을 여기서 다시 하면 뷰와 두 벌이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraineeReportServiceImpl implements TraineeReportService {

	private final TraineeReportQueryRepository queryRepository;
	private final ObjectMapper objectMapper;

	@Override
	@Transactional(readOnly = true)
	public TraineeReportsResponse findMyReports(UUID userId) {
		List<RoundRow> rounds = queryRepository.findRounds(userId);
		Map<UUID, List<ConceptRow>> conceptsByReport = conceptsByReport(userId);
		Map<UUID, List<UnaskedConceptRow>> unaskedByReport = unaskedByReport(userId);
		Map<UUID, Map<UUID, List<StageAnswerRow>>> answersByReport = answersByReport(userId);

		List<RoundListItem> railItems = new ArrayList<>();
		Map<String, RoundReportResponse> reportsById = new LinkedHashMap<>();

		for (RoundRow round : rounds) {
			String roundId = round.assessmentRoundId().toString();
			railItems.add(new RoundListItem(roundId, round.roundName(), isRetryPending(round)));
			reportsById.put(roundId, toRoundReport(round, conceptsByReport, unaskedByReport, answersByReport));
		}

		return new TraineeReportsResponse(railItems, reportsById);
	}

	@Override
	@Transactional(readOnly = true)
	public RoundReportResponse findMyReport(UUID userId, UUID reportId) {
		// 회차 목록에서 reportId로 찾는다. 없는 리포트와 남의 리포트가 같은 404가 되는데,
		// 이건 의도한 것이다 — 403으로 구분해 주면 "그 리포트가 존재한다"는 사실이 새어 나간다.
		RoundRow target = queryRepository.findRounds(userId).stream()
				.filter(row -> reportId.equals(row.reportId()))
				.findFirst()
				.orElseThrow(() -> new ReportException(ReportErrorCode.REPORT_NOT_FOUND));

		return toRoundReport(target, conceptsByReport(userId), unaskedByReport(userId),
				answersByReport(userId));
	}

	private Map<UUID, List<ConceptRow>> conceptsByReport(UUID userId) {
		return queryRepository.findConcepts(userId).stream()
				.collect(Collectors.groupingBy(ConceptRow::reportId, LinkedHashMap::new, Collectors.toList()));
	}

	/** 묻지 못한 개념. 리포트 하나에 보통 0건이고 많아야 2건이다(개념 3개 중). */
	private Map<UUID, List<UnaskedConceptRow>> unaskedByReport(UUID userId) {
		return queryRepository.findUnaskedConcepts(userId).stream()
				.collect(Collectors.groupingBy(UnaskedConceptRow::reportId, LinkedHashMap::new,
						Collectors.toList()));
	}

	/** 문답은 (리포트, 문제) 단위로 묶는다 — 개념 하나가 문제 하나에 대응한다. */
	private Map<UUID, Map<UUID, List<StageAnswerRow>>> answersByReport(UUID userId) {
		return queryRepository.findStageAnswers(userId).stream()
				.collect(Collectors.groupingBy(StageAnswerRow::reportId, LinkedHashMap::new,
						Collectors.groupingBy(StageAnswerRow::problemId, LinkedHashMap::new, Collectors.toList())));
	}

	/**
	 * 회차 하나를 화면 상태로 번역한다.
	 *
	 * <p>판정 순서가 곧 우선순위다. 응시 자체가 성립하지 않은 경우(미응시·무효·중단)를 먼저
	 * 걸러야 한다 — 리포트 상태보다 앞선 사실이기 때문이다. 무효 응시인데 `발행 대기`로
	 * 그리면 학생이 결과를 기다리게 된다.
	 */
	private RoundReportResponse toRoundReport(
			RoundRow round,
			Map<UUID, List<ConceptRow>> conceptsByReport,
			Map<UUID, List<UnaskedConceptRow>> unaskedByReport,
			Map<UUID, Map<UUID, List<StageAnswerRow>>> answersByReport
	) {
		String id = round.assessmentRoundId().toString();
		// 회차당 리포트는 최대 1건이다(uq_report_active_user). 아직 만들어지지 않은 회차는 null이고,
		// @JsonInclude(NON_NULL)이라 그때는 키 자체가 빠진다.
		String reportId = round.reportId() == null ? null : round.reportId().toString();
		String label = round.roundName();

		// ① 무효 응시 — PENDING(검토 중)과 CONFIRMED_INVALID(무효 확정)를 같이 묶는다.
		//    화면 문구가 `확인 필요` 하나라 구분이 필요 없다(labels.ts VOID_ATTEMPT).
		String validity = round.validityReviewStatus();
		if ("PENDING".equals(validity) || "CONFIRMED_INVALID".equals(validity)) {
			return statusOnly(id, reportId, label, "VOID_ATTEMPT");
		}

		// ② 미응시 — 응시 기록이 없거나 제출·출석이 없는 채로 끝났다.
		String terminal = round.terminalReasonCode();
		if (round.attemptId() == null || "NOT_SUBMITTED".equals(terminal) || "NOT_ATTENDED".equals(terminal)) {
			return statusOnly(id, reportId, label, "NOT_ATTEMPTED");
		}

		// ③ 중단 — 세션을 시작했지만 끝내지 못했다.
		if ("SESSION_INCOMPLETE".equals(terminal)) {
			return statusOnly(id, reportId, label, "STOPPED");
		}

		// ④ 발행 전 — 리포트 행이 없거나 published_at이 비어 있다.
		//    publishAfter는 회차가 정해 둔 "이 시각 전에는 발행하지 않는다" 값이다.
		if (round.reportId() == null || round.publishedAt() == null) {
			return new RoundReportResponse(id, reportId, label, "PENDING_PUBLISH",
					iso(round.reportPublishNotBeforeAt()), null, null, null, null,
					null, null, null, null, null, null);
		}

		// ⑤ 발행됐지만 공개 범위 미지정 — 발행과 공개는 다른 사건이다.
		if (!round.canViewReport()) {
			return statusOnly(id, reportId, label, "PENDING_VISIBILITY");
		}

		// ⑥ 공개됨.
		List<ConceptRow> conceptRows = conceptsByReport.getOrDefault(round.reportId(), List.of());
		Map<UUID, List<StageAnswerRow>> answers = answersByReport.getOrDefault(round.reportId(), Map.of());

		// 두 값 다 회차 단위다. 개념 카드마다 다시 계산하지 않고 한 번 정해 넘긴다.
		boolean fullScope = DisclosureScope.FULL == scope(round.traineeDisclosureScope());
		boolean retryPending = isRetryPending(round);

		// 물은 개념과 묻지 못한 개념을 한 배열에 담는다. 화면이 개념 3개를 나란히 그리고, 빠진
		// 자리에 "코드에 이 개념이 없어 묻지 못했습니다"를 띄우려면 같은 목록에 있어야 한다 —
		// 두 배열로 나누면 개념 순서(concept_display_order)가 무너진다.
		List<ConceptReportResponse> concepts = new ArrayList<>(conceptRows.stream()
				.map(row -> toConcept(row, answers.getOrDefault(row.problemId(), List.of()),
						fullScope, retryPending))
				.toList());
		unaskedByReport.getOrDefault(round.reportId(), List.of()).stream()
				.map(row -> ConceptReportResponse.unasked(
						row.problemId() == null ? null : row.problemId().toString(),
						row.conceptDisplayName()))
				.forEach(concepts::add);

		return new RoundReportResponse(
				id, reportId, label, "PUBLISHED",
				null,
				iso(round.publishedAt()),
				round.projectName(),
				// 값 집합은 DB CHECK(ck_report_trainee_disclosure_scope)가 강제하므로 모르는 값을
				// 만나면 조용히 넘길 데이터가 아니다 — valueOf가 그대로 터지게 둔다
				// (ManagedReportListResponse.scope와 같은 판단).
				scope(round.traineeDisclosureScope()),
				// PUBLISHED 분기에서만 채운다. 앞의 ①~⑤는 활성 스냅샷이 없거나(발행 전)
				// 볼 수 없는 상태라 완전성을 말할 대상 자체가 없다.
				completion(round.completionStatus()),
				// 19차 Q1 — 생성 실패(장애)와 문항 없음(정상)을 화면이 가를 수 있게 건수를 준다.
				// 문항 없음은 위 concepts에 asked=false로 들어가고, 생성 실패는 아예 빠지므로
				// 배열만 봐서는 "왜 3개가 아닌가"를 알 수 없다.
				round.sampleCount(),
				round.missingCount(),
				concepts,
				retryState(round),
				iso(round.reviewDueAt()),
				iso(round.reviewCompletedAt())
		);
	}

	private static DisclosureScope scope(String value) {
		return value == null ? null : DisclosureScope.valueOf(value);
	}

	/**
	 * 활성 스냅샷의 완전성. 스냅샷이 없으면 null이고 {@code @JsonInclude(NON_NULL)}이라 키가 빠진다.
	 *
	 * <p>{@code ck_report_snapshot_completion_status}가 {@code FULL}·{@code PARTIAL} 둘만 허용하므로
	 * 모르는 값은 조용히 넘길 데이터가 아니다 — {@link #scope}와 같이 valueOf가 그대로 터지게 둔다.
	 */
	private static ReportCompletionStatus completion(String value) {
		return value == null ? null : ReportCompletionStatus.valueOf(value);
	}

	/**
	 * 개념 카드 하나.
	 *
	 * <h2>🔴 자기 답변을 언제 보여주는가</h2>
	 *
	 * <p>두 가지가 함께 막는다.
	 * <ol>
	 *   <li><b>공개 범위</b> — {@code FULL}이 아니면 답변을 보여주지 않는다. 뷰의
	 *       {@code can_view_own_answers}와 같은 판정이다</li>
	 *   <li><b>다시 보기 진행 상태</b> — 다시 보기 대상인데 <b>아직 안 했으면</b> 막는다.
	 *       다시 풀어야 할 문제의 답을 먼저 보여주면 다시 보기가 성립하지 않는다</li>
	 * </ol>
	 *
	 * <p>2번은 공개 범위와 무관하다. {@code FULL}로 공개된 리포트에서도 다시 보기 전이면 막는다 —
	 * 매니저가 전부 공개했다고 해서 다시 풀 문제의 답이 열려야 할 이유는 없다.
	 *
	 * @param fullScope    이 리포트의 공개 범위가 {@code FULL}인가
	 * @param retryPending 이 회차에 <b>아직 끝내지 않은</b> 다시 보기가 있는가
	 */
	private ConceptReportResponse toConcept(ConceptRow row, List<StageAnswerRow> answerRows,
			boolean fullScope, boolean retryPending) {

		boolean hideOwnAnswers = !fullScope || (row.reviewRequired() && retryPending);

		List<QaEntryResponse> qa = (hideOwnAnswers || answerRows.isEmpty())
				? null
				: answerRows.stream()
				.map(a -> new QaEntryResponse(slotLabel(a.axisCode(), a.slotCode()), a.questionText(), a.answerText()))
				.toList();

		return new ConceptReportResponse(
				// 개념 이름은 회차마다 반복되므로(`예외 처리와 롤백 전략`이 1·2·4차에 모두 나온다)
				// 화면이 개념을 지목할 안정 키가 필요하다.
				row.problemId() == null ? null : row.problemId().toString(),
				row.conceptDisplayName(),
				true,
				row.reachLevel(),
				row.resultExplanation(),
				row.reviewRequired(),
				row.canViewExplanation() ? curriculumRef(row.curriculumLocationJson()) : null,
				qa,
				explain(row, fullScope, retryPending),
				comparedReach(row.reviewBeforeAfterItemsJson())
		);
	}

	/** 다시 보기 상태. REVIEW 응시 기록이 없으면 대상이 아니다. */
	private static String retryState(RoundRow round) {
		if (round.reviewStatus() == null) {
			return "NONE";
		}
		return round.reviewCompletedAt() != null ? "DONE" : "PENDING";
	}

	private static boolean isRetryPending(RoundRow round) {
		return round.reviewStatus() != null && round.reviewCompletedAt() == null;
	}

	/** 화면 `[내 답변] 펼침`의 슬롯 이름. 축을 앞에 붙여 어느 단계의 문답인지 보이게 한다. */
	private static String slotLabel(String axisCode, String slotCode) {
		String slot = switch (slotCode) {
			case "FIRST_HINT" -> "힌트 1";
			case "SECOND_HINT" -> "힌트 2";
			default -> "질문";
		};
		return axisCode == null ? slot : axisCode + " " + slot;
	}

	/**
	 * 교안 위치 JSON({@code report_evidence.trace_payload->'curriculumLocation'})을 화면 모양으로.
	 * 모양이 깨져 있어도 리포트 전체를 실패시키지 않는다 — 교안 링크 한 줄이 없는 것과
	 * 리포트를 못 읽는 것은 무게가 다르다.
	 */
	private CurriculumRefResponse curriculumRef(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			JsonNode node = objectMapper.readTree(json);
			String chapter = node.path("chapter").asString(null);
			String pages = node.path("pages").asString(null);
			String title = node.path("title").asString(null);
			if (chapter == null && pages == null && title == null) {
				return null;
			}
			return new CurriculumRefResponse(chapter, pages, title);
		} catch (Exception exception) {
			log.warn("교안 위치 JSON을 읽지 못했습니다. 링크 없이 내보냅니다.", exception);
			return null;
		}
	}

	/** 막힌 이유 해설. 다시 보기 대상이고 근거 문장이 있을 때만 붙는다. */
	private static List<String> explain(ConceptRow row, boolean fullScope, boolean retryPending) {
		if (!row.reviewRequired() || !row.canViewExplanation()) {
			return null;
		}
		// 공개 범위가 FULL이 아니면 해설을 내보내지 않는다. SUMMARY는 "무엇을 어디까지 했는지"까지고,
		// 막힌 이유를 풀어 주는 것은 그보다 한 단계 더 여는 것이다.
		if (!fullScope) {
			return null;
		}
		// 다시 보기 전에는 해설도 막는다 — 다시 풀 문제의 해설을 먼저 주면 다시 보기가 성립하지 않는다.
		// 마친 뒤(retryState=DONE)에는 학습 자료로 열어 준다.
		if (retryPending) {
			return null;
		}

		List<String> lines = new ArrayList<>();
		if (row.resultExplanation() != null && !row.resultExplanation().isBlank()) {
			lines.add(row.resultExplanation());
		}
		/*
		 * 🔴 answer_excerpt 는 학생이 실제로 입력한 답변이다
		 * (problem_stage 의 second_hint/first_hint/question_answer_text 를 COALESCE 한 값이
		 *  report_evidence.quote_excerpt 로 저장된 것).
		 *
		 * 그래서 qa 와 같은 기준으로 막아야 한다. 위 !fullScope 로 이미 걸리지만 조건을 따로 둔다 —
		 * 그 조건이 나중에 완화되면 여기로 답변이 새기 때문이다. 실제로 종전 코드가 그 상태였다:
		 * SUMMARY 에서 qa 는 막으면서 이 줄로 같은 답변의 발췌를 내보내고 있었다.
		 */
		if (fullScope && row.answerExcerpt() != null && !row.answerExcerpt().isBlank()) {
			lines.add(row.answerExcerpt());
		}
		return lines.isEmpty() ? null : lines;
	}

	/** 다시 보기 전/후 도달 단계. 배열 첫 원소만 쓴다 — 개념 하나에 비교는 하나다. */
	private ComparedReachResponse comparedReach(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			JsonNode array = objectMapper.readTree(json);
			if (!array.isArray() || array.isEmpty()) {
				return null;
			}
			JsonNode first = array.get(0);
			if (!first.hasNonNull("before") || !first.hasNonNull("after")) {
				return null;
			}
			return new ComparedReachResponse(
					axisLevel(first.get("before").asString()),
					axisLevel(first.get("after").asString())
			);
		} catch (Exception exception) {
			log.warn("다시 보기 비교 JSON을 읽지 못했습니다. 비교 없이 내보냅니다.", exception);
			return null;
		}
	}

	/**
	 * 축 코드 `L0`~`L4` → 0~4. 다시 보기 비교 JSON({@code reviewBeforeAfterItems})만 축 코드를 쓴다 —
	 * 개념 카드의 {@code level}은 이미 숫자로 조회된다.
	 *
	 * <p><b>0은 1로 올리지 않는다.</b> 통과한 축이 하나도 없는데 1로 보내면 화면이
	 * `무엇을 하는지까지`(= L1 통과) 라벨을 붙여 학생에게 사실과 다른 말을 하게 된다.
	 */
	private static int axisLevel(String axisCode) {
		return switch (axisCode == null ? "" : axisCode) {
			case "L1" -> 1;
			case "L2" -> 2;
			case "L3" -> 3;
			case "L4" -> 4;
			default -> 0;
		};
	}

	/** 본문이 없는 상태들. 화면은 status만 보고 그린다. */
	private static RoundReportResponse statusOnly(String id, String reportId, String label, String status) {
		return new RoundReportResponse(id, reportId, label, status,
				null, null, null, null, null, null, null, null, null, null, null);
	}

	private static String iso(Instant instant) {
		return instant == null ? null : instant.toString();
	}
}
