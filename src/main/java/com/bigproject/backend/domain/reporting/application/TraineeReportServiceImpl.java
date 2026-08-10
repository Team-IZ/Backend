package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.disclosure.domain.DisclosureScope;
import com.bigproject.backend.domain.reporting.domain.ReportCompletionStatus;
import com.bigproject.backend.domain.reporting.domain.ReportErrorCode;
import com.bigproject.backend.domain.reporting.domain.ReportException;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.ConceptRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.RoundRow;
import com.bigproject.backend.domain.reporting.domain.TraineeReportQueryRepository.StageAnswerRow;
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
		Map<UUID, Map<UUID, List<StageAnswerRow>>> answersByReport = answersByReport(userId);

		List<RoundListItem> railItems = new ArrayList<>();
		Map<String, RoundReportResponse> reportsById = new LinkedHashMap<>();

		for (RoundRow round : rounds) {
			String roundId = round.assessmentRoundId().toString();
			railItems.add(new RoundListItem(roundId, round.roundName(), isRetryPending(round)));
			reportsById.put(roundId, toRoundReport(round, conceptsByReport, answersByReport));
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

		return toRoundReport(target, conceptsByReport(userId), answersByReport(userId));
	}

	private Map<UUID, List<ConceptRow>> conceptsByReport(UUID userId) {
		return queryRepository.findConcepts(userId).stream()
				.collect(Collectors.groupingBy(ConceptRow::reportId, LinkedHashMap::new, Collectors.toList()));
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
					iso(round.reportPublishNotBeforeAt()), null, null, null, null, null, null, null, null);
		}

		// ⑤ 발행됐지만 공개 범위 미지정 — 발행과 공개는 다른 사건이다.
		if (!round.canViewReport()) {
			return statusOnly(id, reportId, label, "PENDING_VISIBILITY");
		}

		// ⑥ 공개됨.
		List<ConceptRow> conceptRows = conceptsByReport.getOrDefault(round.reportId(), List.of());
		Map<UUID, List<StageAnswerRow>> answers = answersByReport.getOrDefault(round.reportId(), Map.of());

		List<ConceptReportResponse> concepts = conceptRows.stream()
				.map(row -> toConcept(row, answers.getOrDefault(row.problemId(), List.of())))
				.toList();

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

	private ConceptReportResponse toConcept(ConceptRow row, List<StageAnswerRow> answerRows) {
		List<QaEntryResponse> qa = answerRows.isEmpty()
				? null
				: answerRows.stream()
				.map(a -> new QaEntryResponse(slotLabel(a.axisCode(), a.slotCode()), a.questionText(), a.answerText()))
				.toList();

		return new ConceptReportResponse(
				// 개념 이름은 회차마다 반복되므로(`예외 처리와 롤백 전략`이 1·2·4차에 모두 나온다)
				// 화면이 개념을 지목할 안정 키가 필요하다.
				row.problemId() == null ? null : row.problemId().toString(),
				row.conceptDisplayName(),
				reachLevel(row.reachDisplayCode()),
				row.resultExplanation(),
				row.reviewRequired(),
				row.canViewExplanation() ? curriculumRef(row.curriculumLocationJson()) : null,
				qa,
				explain(row),
				comparedReach(row.reviewBeforeAfterItemsJson())
		);
	}

	/**
	 * `L0`~`L4` → 0~4.
	 *
	 * <p><b>0은 1로 올리지 않는다.</b> 통과한 축이 하나도 없는데 1로 보내면 화면이
	 * `무엇을 하는지까지`(= L1 통과) 라벨을 붙여 학생에게 사실과 다른 말을 하게 된다.
	 * 계약을 5단(0~4)으로 맞춘 이유가 이것이다.
	 */
	private static int reachLevel(String reachDisplayCode) {
		if (reachDisplayCode == null || reachDisplayCode.length() < 2) {
			return 0;
		}
		return switch (reachDisplayCode) {
			case "L1" -> 1;
			case "L2" -> 2;
			case "L3" -> 3;
			case "L4" -> 4;
			default -> 0;
		};
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
	private static List<String> explain(ConceptRow row) {
		if (!row.reviewRequired() || !row.canViewExplanation()) {
			return null;
		}
		List<String> lines = new ArrayList<>();
		if (row.resultExplanation() != null && !row.resultExplanation().isBlank()) {
			lines.add(row.resultExplanation());
		}
		if (row.answerExcerpt() != null && !row.answerExcerpt().isBlank()) {
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
					reachLevel(first.get("before").asString()),
					reachLevel(first.get("after").asString())
			);
		} catch (Exception exception) {
			log.warn("다시 보기 비교 JSON을 읽지 못했습니다. 비교 없이 내보냅니다.", exception);
			return null;
		}
	}

	/** 본문이 없는 상태들. 화면은 status만 보고 그린다. */
	private static RoundReportResponse statusOnly(String id, String reportId, String label, String status) {
		return new RoundReportResponse(id, reportId, label, status,
				null, null, null, null, null, null, null, null, null);
	}

	private static String iso(Instant instant) {
		return instant == null ? null : instant.toString();
	}
}
