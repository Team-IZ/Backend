package com.bigproject.backend.domain.reporting.application;

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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * TR-04 조립. 뷰에서 읽은 행들을 화면 계약({@code types.ts})의 모양으로 맞춘다.
 *
 * <p>여기서 하는 일은 <b>판정이 아니라 번역</b>이다 — 도달 단계 판정은 DB(뷰)가 이미 내렸고,
 * 이 클래스는 그 값을 화면 어휘로 바꾼다. 판정을 여기서 다시 하면 뷰와 두 벌이 된다.
 *
 * <p>예외는 <b>다시 보기 잠금</b> 하나다. 공개/비공개가 없어진 뒤 남은 유일한 가림막이라
 * 뷰가 아니라 여기서 정한다({@link #isRetryPending}).
 */
@Slf4j
@Service
public class TraineeReportServiceImpl implements TraineeReportService {

	/**
	 * 재시험 기준선. <b>2단(설계 논리)이 합격선</b>이라 그 미만만 다시 본다.
	 * 확정 채점 모델의 "불합격(2단 미만) 개념만 재시험"이 이 상수 하나로 표현된다.
	 */
	static final int RETRY_TARGET_BELOW_LEVEL = 2;

	/**
	 * {@code measurement_attempt.status}가 <b>아직 끝나지 않았다</b>는 뜻인 값들
	 * (2026-08-20 발견·수정). {@code ck_measurement_attempt_status}가 정한 8종 중
	 * {@code COMPLETED}·{@code FAILED}·{@code EXPIRED} 셋을 뺀 나머지다.
	 *
	 * <p>고치기 전에는 이 다섯 값이 전부 아래 ④(발행 전 = {@code PENDING_PUBLISH})로 떨어져
	 * "응시 완료"로 잘못 보였다 — 코드 제출은 했지만 <b>분석도 끝나지 않았고 이해도 확인은
	 * 시작도 안 한</b> 회차가 "다 봤고 리포트만 기다리는 중"으로 뜬 것이다. {@code FAILED}·
	 * {@code EXPIRED}는 빼야 한다 — 그 상태에서도 리포트가 만들어질 수 있어(예: 분석 실패해도
	 * 발행된 회차가 실제로 있다) ④·⑤ 판정을 그대로 타야 한다.
	 */
	private static final Set<String> ATTEMPT_STILL_IN_PROGRESS = Set.of(
			"NOT_STARTED", "SUBMITTED", "ANALYZING", "SESSION_READY", "SESSION_IN_PROGRESS");

	private final TraineeReportQueryRepository queryRepository;
	private final ObjectMapper objectMapper;

	/**
	 * 다시 보기 창(일). <b>발행일이 기산점</b>이다 — MG-06 정의서 §3 "발행 +3일".
	 *
	 * <p>🔴 {@code measurement_attempt.review_due_at}을 쓰지 않는 이유. 그 컬럼은 학생이 다시 보기를
	 * <b>연 순간</b> {@code now + N}으로 찍힌다({@code AssessmentReviewService}). 열지 않은 학생은
	 * 값 자체가 없어서 "기한이 지났는가"를 물을 수가 없다. 잠금은 열지 않은 학생에게도 걸려야
	 * 하므로 기산점이 발행일이어야 한다.
	 *
	 * <p>{@code session.review-window-days}와 <b>같은 값을 본다.</b> 리포트가 잠금을 푸는 시각과
	 * 세션이 닫히는 시각이 어긋나면, 잠금이 풀린 뒤에도 다시 보기 세션이 살아 있어 학생이
	 * 답을 보면서 다시 푸는 구간이 생긴다.
	 */
	private final int reviewWindowDays;

	/**
	 * {@code @RequiredArgsConstructor}를 쓰지 않는다 — 이 프로젝트에 lombok.config가 없어
	 * {@link Value}가 생성자 파라미터로 복사되지 않기 때문이다({@code ReportRunFinalizer}와 같은 이유).
	 */
	public TraineeReportServiceImpl(
			TraineeReportQueryRepository queryRepository,
			ObjectMapper objectMapper,
			@Value("${session.review-window-days:3}") int reviewWindowDays) {

		this.queryRepository = queryRepository;
		this.objectMapper = objectMapper;
		this.reviewWindowDays = reviewWindowDays;
	}

	@Override
	@Transactional(readOnly = true)
	public TraineeReportsResponse findMyReports(UUID userId) {
		return findReports(userId, false);
	}

	@Override
	@Transactional(readOnly = true)
	public TraineeReportsResponse findTraineeReportsForManager(UUID traineeUserId) {
		return findReports(traineeUserId, true);
	}

	/**
	 * 회차 목록 + 회차별 본문.
	 *
	 * @param managerView 매니저가 보는가. 다시 보기 잠금을 걸지 <b>않는다</b> — 잠금의 목적은
	 *                    "학생이 답을 먼저 보고 다시 푸는 것"을 막는 것이라 매니저에게는 해당이 없고,
	 *                    지도하려면 학생이 뭐라고 답했는지를 봐야 한다.
	 *                    {@code retryState}는 그대로 <b>사실대로</b> 내보낸다 — 매니저가 다시 보기를
	 *                    아직 안 한 학생을 찾는 근거이므로 가리면 안 된다.
	 */
	private TraineeReportsResponse findReports(UUID userId, boolean managerView) {
		List<RoundRow> rounds = queryRepository.findRounds(userId);
		Map<UUID, List<ConceptRow>> conceptsByReport = conceptsByReport(userId);
		Map<UUID, List<UnaskedConceptRow>> unaskedByReport = unaskedByReport(userId);
		Map<UUID, Map<UUID, List<StageAnswerRow>>> answersByReport = answersByReport(userId);

		List<RoundListItem> railItems = new ArrayList<>();
		Map<String, RoundReportResponse> reportsById = new LinkedHashMap<>();

		for (RoundRow round : rounds) {
			String roundId = round.assessmentRoundId().toString();
			boolean hasRetryTarget = hasRetryTarget(round, conceptsByReport);
			railItems.add(new RoundListItem(roundId, round.roundName(), isRetryPending(round, hasRetryTarget)));
			reportsById.put(roundId,
					toRoundReport(round, conceptsByReport, unaskedByReport, answersByReport, managerView));
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
				answersByReport(userId), false);
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
			Map<UUID, Map<UUID, List<StageAnswerRow>>> answersByReport,
			boolean managerView
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

		// ② 응시 기록이 없다. 여기서 <b>둘로 가른다</b> — 마감 전이면 아직 낼 수 있고(NOT_STARTED),
		//    마감이 지났으면 놓친 것이다(NOT_ATTEMPTED).
		//
		//    한 값으로 뭉치면 화면이 두 상황에 같은 문구를 쓰게 되는데, 그러면 **정말 놓친 학생에게서
		//    경고가 사라진다**("사정이 있었다면 매니저에게 알려 주세요"). 반대로 중립 문구를 마감 전
		//    학생에게 쓰면 아직 시간이 있는데 놓친 것처럼 읽힌다(26차 A1).
		//
		//    가르는 축은 제출 마감이다. 홈이 SUBMISSION_REQUIRED와 SUBMISSION_MISSED를 가를 때 쓰는
		//    값과 같아야 **같은 회차를 두 화면이 같은 말로 설명한다** — 지금까지 어긋났던 지점이다.
		String terminal = round.terminalReasonCode();
		if (round.attemptId() == null || "NOT_SUBMITTED".equals(terminal) || "NOT_ATTENDED".equals(terminal)) {
			return statusOnly(id, reportId, label, missedStatus(round));
		}

		// ③ 중단 — 세션을 시작했지만 끝내지 못했다.
		if ("SESSION_INCOMPLETE".equals(terminal)) {
			return statusOnly(id, reportId, label, "STOPPED");
		}

		// ③-2 아직 진행 중(2026-08-20 발견·수정) — 응시 기록은 있지만 COMPLETED에 이르지 못했다
		// (제출 전·분석 중·이해도 확인 세션 준비됨·이해도 확인 진행 중). ①②③ 어디에도 안 걸리고
		// 그대로 두면 ④(PENDING_PUBLISH="응시 완료")로 떨어져, 아직 응시조차 안 한 회차가
		// "다 보고 리포트만 기다리는 중"으로 잘못 보인다.
		if (ATTEMPT_STILL_IN_PROGRESS.contains(round.attemptStatus())) {
			return statusOnly(id, reportId, label, "IN_PROGRESS");
		}

		/*
		 * ③-3 분석 실패 — 리포트가 만들어질 수 없다(2026-08-21 발견·수정).
		 *
		 * 코드 분석 자체가 실패하면 이해도 확인 문항이 없어 리포트를 만들 근거가 없다.
		 * 그런데 이 사실을 거르는 자리가 없었다 — ②는 NOT_SUBMITTED·NOT_ATTENDED만 보고,
		 * ③-2는 FAILED를 의도적으로 뺀다(그 상태가 그대로 ④로 떨어져도 "발행 대기"만큼은
		 * 맞을 수 있다고 봤기 때문인데, 리포트 행 자체가 없으면 그마저도 거짓 약속이 된다).
		 * 그래서 학생은 홈에서는 "코드를 분석하지 못했어요, 다시 제출해 주세요"를 정확히
		 * 보면서 리포트 화면에서는 "리포트를 만들고 있어요 · 발행 예정 N월 N일 이후"라는,
		 * 이미 지난 날짜의 헛된 약속을 봤다(실사용 재현: 문주안 계정 미프 3차).
		 *
		 * 🔴 **리포트 행이 있으면 이 분기를 타지 않는다.** 분석 실패 회차에도 실제로 리포트가
		 * 걸려 있는 경우가 있다(실측 84건 — 대부분 CHECKPOINT 리포트가 같은 assessment_round_id를
		 * 공유해 걸린 것으로 보인다, 별도 확인 필요한 기존 동작이라 이 수정 범위 밖). 그 경우는
		 * 그대로 ④·⑤ 판정으로 넘어가 기존 화면을 깨지 않는다.
		 */
		if ("ANALYSIS_FAILED".equals(terminal) && round.reportId() == null) {
			return statusOnly(id, reportId, label, "ANALYSIS_FAILED");
		}

		// ④ 발행 전 — 리포트 행이 없거나 published_at이 비어 있다.
		//    publishAfter는 회차가 정해 둔 "이 시각 전에는 발행하지 않는다" 값이다.
		if (round.reportId() == null || round.publishedAt() == null) {
			return new RoundReportResponse(id, reportId, label, "PENDING_PUBLISH",
					iso(round.reportPublishNotBeforeAt()), null, null, null,
					null, null, null, null, null, null);
		}

		// ⑤ 발행됨 — 곧 공개다. 종전에는 여기에 `발행은 됐지만 매니저가 공개 범위를 안 정했다`
		//    (PENDING_VISIBILITY) 분기가 하나 더 있었는데, 공개/비공개 개념이 없어지면서 사라졌다.
		List<ConceptRow> conceptRows = conceptsByReport.getOrDefault(round.reportId(), List.of());
		Map<UUID, List<StageAnswerRow>> answers = answersByReport.getOrDefault(round.reportId(), Map.of());

		// 회차 단위 값이라 개념 카드마다 다시 계산하지 않고 한 번 정해 넘긴다.
		boolean hasRetryTarget = conceptRows.stream().anyMatch(TraineeReportServiceImpl::isRetryTarget);
		boolean retryPending = !managerView && isRetryPending(round, hasRetryTarget);

		// 물은 개념과 묻지 못한 개념을 한 배열에 담는다. 화면이 개념 3개를 나란히 그리고, 빠진
		// 자리에 "코드에 이 개념이 없어 묻지 못했습니다"를 띄우려면 같은 목록에 있어야 한다 —
		// 두 배열로 나누면 개념 순서(concept_display_order)가 무너진다.
		List<ConceptReportResponse> concepts = new ArrayList<>(conceptRows.stream()
				.map(row -> toConcept(row, answers.getOrDefault(row.problemId(), List.of()), retryPending))
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
				// PUBLISHED 분기에서만 채운다. 앞의 ①~④는 활성 스냅샷이 없어서(발행 전)
				// 완전성을 말할 대상 자체가 없다.
				completion(round.completionStatus()),
				// 19차 Q1 — 생성 실패(장애)와 문항 없음(정상)을 화면이 가를 수 있게 건수를 준다.
				// 문항 없음은 위 concepts에 asked=false로 들어가고, 생성 실패는 아예 빠지므로
				// 배열만 봐서는 "왜 3개가 아닌가"를 알 수 없다.
				round.sampleCount(),
				round.missingCount(),
				concepts,
				retryState(round, hasRetryTarget),
				iso(effectiveRetryDueAt(round, hasRetryTarget)),
				// DONE(=isRetryDone)이 아닌데 이 값을 내보내면 FAILED·EXPIRED로 끝난 REVIEW의
				// terminal_at이 새서 "완료 시각이 있는데 PENDING"이라는 모순된 조합이 나간다.
				isRetryDone(round) ? iso(round.reviewCompletedAt()) : null
		);
	}

	/**
	 * 활성 스냅샷의 완전성. 스냅샷이 없으면 null이고 {@code @JsonInclude(NON_NULL)}이라 키가 빠진다.
	 *
	 * <p>{@code ck_report_snapshot_completion_status}가 {@code FULL}·{@code PARTIAL} 둘만 허용하므로
	 * 모르는 값은 조용히 넘길 데이터가 아니다 — valueOf가 그대로 터지게 둔다.
	 */
	private static ReportCompletionStatus completion(String value) {
		return value == null ? null : ReportCompletionStatus.valueOf(value);
	}

	/**
	 * 개념 카드 하나.
	 *
	 * <h2>🔴 자기 답변을 언제 보여주는가</h2>
	 *
	 * <p>막는 조건은 <b>하나뿐이다</b> — 다시 보기 대상({@code reachLevel < 2})인데 아직 안 했으면
	 * 막는다. 다시 풀어야 할 문제의 답을 먼저 보여주면 다시 보기가 성립하지 않는다.
	 *
	 * <p>종전에는 공개 범위({@code SUMMARY}면 전부 가림)가 하나 더 있었는데, 공개/비공개가
	 * 없어지면서 리포트는 항상 전문 공개다. 매니저가 문제별로 무엇을 가릴지 정하던 자리를
	 * <b>도달 단계가 그대로 물려받는다</b> — 2단을 통과한 개념은 발행 즉시 열리고,
	 * 못 넘은 개념만 다시 보기를 마칠 때까지 닫힌다.
	 *
	 * @param retryPending 이 회차에 <b>아직 끝내지 않은</b> 다시 보기가 있는가.
	 *                     매니저 조회에서는 항상 false다.
	 */
	private ConceptReportResponse toConcept(ConceptRow row, List<StageAnswerRow> answerRows,
			boolean retryPending) {

		boolean retryTarget = isRetryTarget(row);
		boolean hideOwnAnswers = retryTarget && retryPending;

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
				retryTarget,
				row.canViewExplanation() ? curriculumRef(row.curriculumLocationJson()) : null,
				qa,
				explain(row, retryPending, retryTarget),
				comparedReach(row.reviewBeforeAfterItemsJson())
		);
	}

	/**
	 * 재시험 대상인가. <b>정책이 정한다 — 저장된 판정을 그대로 내보내지 않는다.</b>
	 *
	 * <p>확정 채점 모델의 규칙은 <b>"2단 미만만 재시험"</b> 하나다. 그런데
	 * {@code report_evidence.decision_code}에는 그 규칙과 어긋나는 값이 남아 있다 — 2단을 통과한
	 * 개념이 {@code REVIEW_REQUIRED}로 얼어 있는 리포트가 실제로 있었다(23차 R2). 발행 시점에 AI가
	 * 준 {@code retest}를 그대로 믿었거나, 그 이전에 적재된 데이터다.
	 *
	 * <p>🔴 <b>그대로 두면 학생이 합격한 개념을 다시 본다.</b> 다시 보기는 회차당 한 번뿐이라,
	 * 그 한 번을 이미 통과한 개념에 쓰면 <b>정작 막힌 개념을 볼 기회가 사라진다.</b> 저장된 값보다
	 * 이 손해가 크므로 읽는 시점에 정책으로 덮는다 — 이미 발행된 리포트도 함께 맞는다.
	 *
	 * <p>쓰는 쪽({@code ReportEvidenceFactory})도 같은 규칙으로 확정하므로 앞으로 저장되는 값은
	 * 여기서 뒤집히지 않는다. 두 곳에 같은 규칙이 있는 것은 중복이 아니라 <b>옛 데이터까지 덮기
	 * 위한 것</b>이며, 규칙 자체는 {@link #RETRY_TARGET_BELOW_LEVEL} 하나에서 나온다.
	 */
	private static boolean isRetryTarget(ConceptRow row) {
		return row.reachLevel() < RETRY_TARGET_BELOW_LEVEL;
	}

	/**
	 * 이 회차에 다시 볼 개념이 하나라도 있는가. {@link #isRetryTarget}과 <b>같은 원천</b>이다.
	 *
	 * <p>개념 행이 없는 회차(미응시·발행 전)는 대상도 없다. 다만 <b>행 유무로 열람 가능 여부를
	 * 판단하면 안 된다</b> — {@code trainee_report_problem_view}는 그것을 행 유무가 아니라
	 * {@code can_view_explanation} 컬럼으로 표현한다. 판정은 {@link #isRetryPending}이 한다.
	 */
	private static boolean hasRetryTarget(RoundRow round, Map<UUID, List<ConceptRow>> conceptsByReport) {
		if (round.reportId() == null) {
			return false;
		}
		return conceptsByReport.getOrDefault(round.reportId(), List.of()).stream()
				.anyMatch(TraineeReportServiceImpl::isRetryTarget);
	}

	/**
	 * 응시 기록이 없는 회차의 상태. <b>제출 마감이 지났는가</b> 하나로 갈린다.
	 *
	 * <p>종료 사유가 남아 있으면({@code NOT_SUBMITTED}·{@code NOT_ATTENDED}) 그 회차는 이미 끝난
	 * 것이므로 마감을 따지지 않는다 — 응시 행이 종료됐다는 것 자체가 기회가 닫혔다는 뜻이다.
	 *
	 * <p>마감 시각을 모르는 회차는 {@code NOT_ATTEMPTED}로 둔다. "아직 낼 수 있다"고 말하려면
	 * 낼 수 있는 기한이 있어야 하는데, 그 값이 없으면 근거 없이 안심시키는 쪽이 된다.
	 */
	private static String missedStatus(RoundRow round) {
		boolean roundOver = round.terminalReasonCode() != null;
		boolean beforeDeadline = round.submissionDueAt() != null
				&& round.submissionDueAt().isAfter(Instant.now());
		return !roundOver && beforeDeadline ? "NOT_STARTED" : "NOT_ATTEMPTED";
	}

	/**
	 * 다시 보기 상태.
	 *
	 * <h2>🔴 대상이 0개면 {@code PENDING}을 만들지 않는다</h2>
	 *
	 * <p>종전에는 REVIEW 응시 행의 존재 여부만 봤다. 그런데 대상 판정({@link #isRetryTarget})은
	 * 23차 R2에서 <b>2단 미만</b>으로 확정됐고, 그보다 느슨한 기준으로 만들어진 REVIEW 응시가
	 * 데이터에 남아 있다 — 2단을 통과한 개념까지 재시험 대상으로 잡던 시절의 행이다.
	 * 그래서 <b>다시 볼 문제가 0개인데 {@code PENDING}</b>인 회차가 생겼고, 화면은
	 * "다시 볼 수 있는 문제가 0개 있어요" 배너와 빈 세션으로 들어가는 버튼을 그렸다(24차 R1).
	 *
	 * <p>판정 순서는 이렇다.
	 * <ol>
	 *   <li>REVIEW 응시를 마쳤다 → {@code DONE}. <b>대상 수와 무관하다</b> —
	 *       이미 일어난 사실의 기록이라 지금 대상이 0개여도 참이고, 이 값이 해설 잠금을 푼다</li>
	 *   <li>{@link #isRetryPending}이 참이다 → {@code PENDING}</li>
	 *   <li>그 외 → {@code NONE}</li>
	 * </ol>
	 *
	 * <p>🔴 <b>{@link #isRetryPending}과 같은 판정을 써야 한다.</b> 화면은 이 값으로 `다시 볼 문제
	 * N개` 배너와 시작 버튼을 그리고, 잠금은 저쪽이 정한다. 둘이 어긋나면 "답은 가려져 있는데
	 * 다시 볼 것은 없다고 하는" 회차나 그 반대가 생긴다. 기한이 지나 잠금이 풀린 회차가
	 * {@code NONE}으로 떨어지는 것도 이 때문이다 — 들어갈 수 없는 세션의 버튼을 그리면 안 된다.
	 * 대상이었다는 사실은 개념 카드의 {@code isRetryTarget}에 그대로 남는다.
	 */
	private String retryState(RoundRow round, boolean hasRetryTarget) {
		if (isRetryDone(round)) {
			return "DONE";
		}
		return isRetryPending(round, hasRetryTarget) ? "PENDING" : "NONE";
	}

	/**
	 * 다시 보기를 <b>정상 완료</b>했는가. {@code AssessmentRoundQueryService.retryState()}의
	 * {@code completedReviewCount() > 0}(REVIEW 응시가 {@code status='COMPLETED'}인 것만 셈)과
	 * 같은 기준이어야 한다.
	 *
	 * <p>🔴 종전에는 {@code round.reviewCompletedAt() != null}(= {@code measurement_attempt.terminal_at}
	 * 이 찍혔는가)로 판정했다. 그런데 REVIEW 응시가 {@code FAILED}·{@code EXPIRED}로 끝나도
	 * {@code terminal_at}은 찍힌다(REVIEW attempt가 종료 상태로 들어가면 항상 채워지는 컬럼이라서다) —
	 * 그래서 다시 보기를 실패·만료로 놓친 학생도 {@code DONE}으로 잘못 나가 답·해설 잠금이 풀리고,
	 * 홈 화면({@code assessment-rounds})은 여전히 {@code PENDING}이라 두 API가 어긋났다.
	 */
	private static boolean isRetryDone(RoundRow round) {
		return "COMPLETED".equals(round.reviewStatus());
	}

	/**
	 * 다시 보기가 아직 남아 있는가. <b>자기 답변·해설을 가리는 유일한 조건</b>이자
	 * {@code rounds[].hasPendingRetry}다.
	 *
	 * <h2>🔴 REVIEW 응시 행의 유무를 보지 않는다</h2>
	 *
	 * <p>종전 판정은 {@code reviewStatus != null}, 즉 <b>학생이 다시 보기를 연 적이 있는가</b>를
	 * 함께 봤다. 그래서 아예 열지 않은 학생은 잠금이 걸리지 않았고 — 리포트에서 답과 해설을
	 * 그대로 볼 수 있었다. <b>안 들어가는 쪽이 이득</b>인 셈이라 잠금이 목적을 잃는다.
	 * 매니저가 공개해 줄 때까지 리포트 자체가 안 보이던 시절에는 드러나지 않았지만,
	 * 발행 즉시 공개로 바뀌면 그대로 노출된다.
	 *
	 * <p>이제는 <b>다시 볼 문제가 있다는 사실만으로</b> 잠근다. 푸는 것은 완료뿐이다.
	 *
	 * <h2>기한이 지나면 열어 준다</h2>
	 *
	 * <p>다시 보기를 못 한 채 창이 닫히면 기회는 사라지지만 잠금도 함께 푼다 — 잠금의 목적이
	 * "답을 먼저 보고 다시 푸는 것"을 막는 것인데, 다시 풀 방법이 없어진 뒤에는 막을 것이 없다.
	 * 그때부터는 학습 자료로 열어 두는 편이 낫다.
	 *
	 * <p>기산점은 {@code review_due_at}이 아니라 <b>발행일</b>이다. 이유는
	 * {@link #reviewWindowDays} 참고 — 열지 않은 학생에게는 {@code review_due_at}이 없다.
	 */
	private boolean isRetryPending(RoundRow round, boolean hasRetryTarget) {
		if (!hasRetryTarget || isRetryDone(round)) {
			return false;
		}
		// 발행 전이면 애초에 볼 수 있는 본문이 없다. 창을 계산할 기산점도 없으므로 잠긴 것으로 둔다.
		Instant publishedAt = round.publishedAt();
		return publishedAt == null
				|| Instant.now().isBefore(publishedAt.plus(Duration.ofDays(reviewWindowDays)));
	}

	/**
	 * 화면에 보여줄 다시 보기 마감일. {@link #isRetryPending}과 <b>같은 기산점</b>을 써야 한다.
	 *
	 * <h2>🔴 {@code round.reviewDueAt()}을 그대로 내보내면 안 되는 이유</h2>
	 *
	 * <p>{@code isRetryPending}은 위 주석대로 "열지 않은 학생에게는 {@code review_due_at}이 없다"는
	 * 이유로 그 컬럼 대신 발행일 기준을 쓴다. 그런데 화면에 내려주는 마감일 필드는 종전에
	 * {@code round.reviewDueAt()}을 그대로 썼다 — 그러면 <b>한 번도 열지 않은 학생</b>은
	 * {@code retryState}가 {@code PENDING}인데 마감일만 {@code null}이 되고, 화면의 배너 조건
	 * (`retryState === 'PENDING' && retryDueAt && retryCount > 0`)이 마감일에서 막혀 배너 자체가
	 * 그려지지 않는다 — 다시 볼 문제가 있다고도, 버튼을 주지도 않는 상태로 남는다.
	 *
	 * <p>이미 연 학생은 {@code review_due_at}이 그 학생의 실제 세션 마감이라 그대로 쓰고,
	 * 아직 안 연 학생만 {@code isRetryPending}과 같은 발행일+{@link #reviewWindowDays} 값으로 채운다.
	 */
	private Instant effectiveRetryDueAt(RoundRow round, boolean hasRetryTarget) {
		if (round.reviewDueAt() != null) {
			return round.reviewDueAt();
		}
		if (!isRetryPending(round, hasRetryTarget) || round.publishedAt() == null) {
			return null;
		}
		return round.publishedAt().plus(Duration.ofDays(reviewWindowDays));
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

	/**
	 * 막힌 이유 해설. 다시 보기 대상이고 근거 문장이 있을 때만 붙는다.
	 *
	 * <p>대상 판정은 {@code isRetryTarget}이 넘겨준 값을 쓴다 — 저장된 {@code decision_code}를 다시
	 * 읽으면 해설이 붙는 개념과 재시험 뱃지가 붙는 개념이 갈린다.
	 */
	private static List<String> explain(ConceptRow row, boolean retryPending, boolean retryTarget) {
		if (!retryTarget || !row.canViewExplanation()) {
			return null;
		}
		// 다시 보기 전에는 해설을 막는다 — 다시 풀 문제의 해설을 먼저 주면 다시 보기가 성립하지 않는다.
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
		 * 그래서 qa 와 같은 기준으로 막아야 한다. 위 retryPending 분기로 이미 걸리지만 조건을 따로 둔다 —
		 * 그 조건이 나중에 완화되면 여기로 답변이 새기 때문이다. 실제로 종전 코드가 그 상태였다:
		 * SUMMARY 에서 qa 는 막으면서 이 줄로 같은 답변의 발췌를 내보내고 있었다.
		 */
		if (!retryPending && row.answerExcerpt() != null && !row.answerExcerpt().isBlank()) {
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
				null, null, null, null, null, null, null, null, null, null);
	}

	private static String iso(Instant instant) {
		return instant == null ? null : instant.toString();
	}
}
