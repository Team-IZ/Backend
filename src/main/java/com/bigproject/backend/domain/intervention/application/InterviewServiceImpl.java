package com.bigproject.backend.domain.intervention.application;

import com.bigproject.backend.domain.intervention.domain.InterventionErrorCode;
import com.bigproject.backend.domain.intervention.domain.InterviewExclusionRepository;
import com.bigproject.backend.domain.intervention.domain.InterviewListRepository;
import com.bigproject.backend.domain.intervention.domain.InterviewListRepository.InterviewCountRow;
import com.bigproject.backend.domain.intervention.domain.InterviewListRepository.InterviewListRow;
import com.bigproject.backend.domain.intervention.domain.InterviewRoundRepository;
import com.bigproject.backend.domain.intervention.domain.RiskSummaryText;
// 32차 R2 — 「이번 회차」 판정을 빌려 온다. 규칙은 projectexecution이 갖는다(15차 R1) —
// 여기서 다시 쓰면 명부와 면담이 서로 다른 회차를 말하게 된다.
import com.bigproject.backend.domain.projectexecution.application.ProjectService;
// 32차 R3 — 담당 밖 접근의 공용 코드. class-progress·evaluations가 쓰는 것과 같다.
import com.bigproject.backend.global.security.ManagerViewAccessErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MG-03 면담 목록 조립.
 *
 * <p>여기서 하는 일은 <b>DB가 준 값을 화면 계약으로 옮기는 것뿐</b>이다. 상태 병합·위험 유형
 * 판정은 SQL이 이미 했고(한 곳에서만 정한다), 담당 반 스코프는 뷰가 강제한다.
 * 이 클래스가 새로 계산하는 것은 {@code briefState} 하나다.
 */
@Service
@RequiredArgsConstructor
public class InterviewServiceImpl implements InterviewService {

	/** 상태별 개수는 화면이 세 칸을 항상 그리므로 0이어도 키가 있어야 한다. */
	private static final List<String> STATUSES = List.of("PLANNED", "DONE", "EXCLUDED");

	/** 위험 유형도 마찬가지 — 필터 옵션이 4종을 늘 그린다. */
	private static final List<String> RISK_TYPES = List.of("INVALID", "LOW_PERSISTENT", "DECLINE", "OBSERVE");

	/** 회차 경과일을 세는 기준 시간대. 매니저가 보는 달력이 기준이다. */
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final InterviewListRepository interviewListRepository;
	private final InterviewExclusionRepository exclusionRepository;
	private final InterviewRoundRepository roundRepository;
	/** 「이번 회차」 판정을 빌려 온다(32차 R2). 명부가 같은 것을 쓴다. */
	private final ProjectService projectService;

	@Override
	@Transactional(readOnly = true)
	public InterviewListResult findCases(InterviewListCriteria criteria) {
		UUID roundId = resolveRound(criteria);

		// 32차 R3 — 담당 밖 회차는 404다.
		//
		// 종전에는 200 + 빈 목록 + round: null이었다. 그러면 "권한이 없다"와 "대상이 없다"가
		// 같은 응답이 되어, 화면이 「이번 회차 면담 대상이 없습니다」라는 사실 아닌 안내를 그렸다.
		// 매니저는 기다리면 채워진다고 읽는다.
		//
		// 30차 R3에서 class-progress에 넣어 둔 것과 같은 판정·같은 코드다.
		// 회차를 아예 고를 수 없는 경우(담당 기수에 회차가 없다)는 그 아래에서 빈 결과로 답한다.
		RoundView round = null;
		if (roundId != null) {
			round = roundRepository
					.findRoundMeta(criteria.managerUserId(), criteria.orgId(), roundId)
					.map(InterviewServiceImpl::toRoundView)
					.orElseThrow(() -> new ApiException(ManagerViewAccessErrorCode.MANAGER_SCOPE_NOT_FOUND));
		}

		List<InterviewListRow> rows = roundId == null ? List.of() : interviewListRepository.findCases(
				new InterviewListRepository.InterviewListQuery(
						criteria.managerUserId(),
						criteria.orgId(),
						roundId,
						criteria.search(),
						criteria.status(),
						criteria.riskType(),
						criteria.classId()));

		List<InterviewCountRow> countRows = roundId == null ? List.of()
				: interviewListRepository.countByRound(criteria.managerUserId(), criteria.orgId(), roundId);

		List<InterviewCaseView> items = rows.stream().map(InterviewServiceImpl::toView).toList();

		List<ClassOptionView> classes = interviewListRepository
				.findManagedClasses(criteria.managerUserId(), criteria.orgId()).stream()
				.map(option -> new ClassOptionView(option.classId(), option.className()))
				.toList();

		return new InterviewListResult(
				items, items.size(), toCounts(countRows), toRiskCounts(countRows), classes, round);
	}

	/**
	 * 32차 R2 — 회차를 생략하면 서버가 「이번 회차」를 고른다.
	 *
	 * <p>종전에는 {@code assessmentRoundId}가 필수라 화면이 회차 목록을 먼저 받아야 면담 목록을
	 * 부를 수 있었다. 첫 진입이 <b>직렬 2왕복</b>(실측 2.5~3.1초)이 되는데, 명부
	 * ({@code GET /cohorts/{id}/trainees})는 이미 생략을 허용하고 응답에 고른 회차를 실어 준다.
	 * 같은 자리에서 두 API가 다른 규칙인 것이 걸린다는 지적이 맞다.
	 *
	 * <p><b>판정은 명부와 같은 것을 쓴다.</b> 회차 목록의 마지막 기수를 잡아
	 * {@code ProjectService.resolveCurrentProject}에 넘긴다 — 규칙을 여기서 다시 쓰면 두 화면이
	 * 서로 다른 「이번 회차」를 말하게 된다(15차 R1이 그 규칙을 projectexecution에 둔 이유다).
	 *
	 * <p>고른 회차는 응답의 {@code round.assessmentRoundId}로 나가므로 화면이 드롭다운을 맞출 수 있다.
	 *
	 * @return 담당 기수에 회차가 하나도 없으면 {@code null}. 그때는 빈 목록으로 답한다
	 */
	private UUID resolveRound(InterviewListCriteria criteria) {
		if (criteria.assessmentRoundId() != null) {
			return criteria.assessmentRoundId();
		}

		List<InterviewRoundRepository.RoundOption> options =
				roundRepository.findRoundOptions(criteria.managerUserId(), criteria.orgId());
		if (options.isEmpty()) {
			return null;
		}

		// 목록은 프로젝트 순서·회차 번호 오름차순이라 마지막이 가장 최근이다.
		InterviewRoundRepository.RoundOption last = options.get(options.size() - 1);
		return projectService.resolveCurrentProject(last.cohortId(), criteria.orgId())
				.map(project -> options.stream()
						.filter(option -> option.projectId().equals(project.getProjectId()))
						.reduce((first, second) -> second)
						.map(InterviewRoundRepository.RoundOption::assessmentRoundId)
						.orElse(last.assessmentRoundId()))
				// 이번 회차 프로젝트를 못 고르면 목록의 마지막으로 물러선다 — 명부와 같은 처리다.
				.orElse(last.assessmentRoundId());
	}

	@Override
	@Transactional(readOnly = true)
	public List<RoundOptionView> findRoundOptions(UUID managerUserId, UUID orgId) {
		return roundRepository.findRoundOptions(managerUserId, orgId).stream()
				.map(option -> new RoundOptionView(
						option.assessmentRoundId(), option.projectId(), option.label(),
						option.roundNo(), option.status()))
				.toList();
	}

	private static RoundView toRoundView(InterviewRoundRepository.RoundMeta meta) {
		return new RoundView(
				meta.assessmentRoundId(),
				meta.label(),
				meta.resultStatus(),
				meta.firstRound(),
				meta.publishedAt(),
				daysSince(meta.publishedAt()));
	}

	/**
	 * 발행 후 경과일. 발행 전이면 null이다 — 0으로 두면 화면이 "0일째 안 끝났습니다"를 그린다.
	 *
	 * <p>날짜 경계로 센다. 시각 차를 24로 나누면 어제 오후 발행분이 오늘 오전에 "0일"이 되어
	 * 매니저가 보는 달력 감각과 어긋난다.
	 */
	private static Integer daysSince(Instant publishedAt) {
		if (publishedAt == null) {
			return null;
		}
		LocalDate published = publishedAt.atZone(SEOUL).toLocalDate();
		long days = ChronoUnit.DAYS.between(published, LocalDate.now(SEOUL));
		return (int) Math.max(days, 0);
	}

	/**
	 * 화면이 사유를 보내지 않으므로 서버가 채우는 기본 코드.
	 *
	 * <p>{@code ck_interview_candidate_status_2}가 EXCLUDED에 {@code exclusion_reason_code}
	 * NOT NULL을 강제한다. 이 컬럼은 TEXT이고 CHECK가 없어 값 집합이 열려 있다 —
	 * 팀에서 코드를 확정하면 이 상수만 바꾸면 된다.
	 */
	private static final String DEFAULT_EXCLUSION_REASON = "MANAGER_DISCRETION";

	@Override
	@Transactional
	public void exclude(UUID managerUserId, UUID orgId, UUID caseId) {
		InterviewExclusionRepository.CandidateState state = loadState(managerUserId, orgId, caseId);

		if ("EXCLUDED".equals(state.status())) {
			throw new ApiException(InterventionErrorCode.INTERVIEW_EXCLUSION_STATE_CONFLICT);
		}
		// 이미 만난 사람을 큐에서 빼는 것은 상태 모델상 의미가 없다(테이블 COMMENT:
		// "연결된 면담이 PENDING인 후보로 한정").
		if (!state.interviewNotStarted()) {
			throw new ApiException(InterventionErrorCode.INTERVIEW_ALREADY_STARTED);
		}

		int updated = exclusionRepository.exclude(
				caseId, state.rowVersion(), DEFAULT_EXCLUSION_REASON, managerUserId);
		if (updated == 0) {
			throw new ApiException(InterventionErrorCode.INTERVIEW_ROW_VERSION_CONFLICT);
		}

		exclusionRepository.insertStatusHistory(caseId, state.status(), "EXCLUDED",
				managerUserId, DEFAULT_EXCLUSION_REASON, state.rowVersion(), UUID.randomUUID());
	}

	@Override
	@Transactional
	public void reinclude(UUID managerUserId, UUID orgId, UUID caseId) {
		InterviewExclusionRepository.CandidateState state = loadState(managerUserId, orgId, caseId);

		if (!"EXCLUDED".equals(state.status())) {
			throw new ApiException(InterventionErrorCode.INTERVIEW_EXCLUSION_STATE_CONFLICT);
		}

		// 브리프를 만들어 둔 뒤 제외한 케이스는 면담 행이 그대로 남아 있다(제외가 삭제하지
		// 않는다). 그런 후보를 ELIGIBLE로 되돌리면 면담이 있는데 후보는 "아직 안 만든" 상태가
		// 되어 어긋난다.
		String toStatus = state.interviewId() == null ? "ELIGIBLE" : "INTERVIEW_CREATED";

		int updated = exclusionRepository.reinclude(caseId, state.rowVersion(), toStatus);
		if (updated == 0) {
			throw new ApiException(InterventionErrorCode.INTERVIEW_ROW_VERSION_CONFLICT);
		}

		exclusionRepository.insertStatusHistory(caseId, "EXCLUDED", toStatus,
				managerUserId, null, state.rowVersion(), UUID.randomUUID());
	}

	/** 담당 반이 아니거나 없는 케이스는 404로 접는다 — 존재 자체를 노출하지 않는다. */
	private InterviewExclusionRepository.CandidateState loadState(
			UUID managerUserId, UUID orgId, UUID caseId) {
		return exclusionRepository.findState(managerUserId, orgId, caseId)
				.orElseThrow(() -> new ApiException(InterventionErrorCode.INTERVIEW_CASE_NOT_FOUND));
	}

	private static InterviewCaseView toView(InterviewListRow row) {
		boolean invalid = "INVALID".equals(row.screenRiskType());
		return new InterviewCaseView(
				row.candidateId(),
				row.traineeUserId(),
				row.traineeName(),
				row.classId(),
				row.className(),
				row.screenStatus(),
				row.screenRiskType(),
				firstSummary(row),
				briefState(row),
				row.attemptId(),
				// 무효 확인을 "그대로 두기"로 마친 상태. 화면은 이 값으로 [무효 확인] 버튼을 감추고
				// 판정 근거를 "확인 완료 · 그대로 유지"로 바꾼다.
				"RESTORED_VALID".equals(row.validityReviewStatus()),
				// 아직 사람이 판정하지 않았다. 이 케이스는 브리프보다 무효 확인이 먼저다(정의서 §5)
				// — 브리프를 먼저 만들면 briefType(STANDARD/INVALID_ATTEMPT)을 정할 수 없다.
				invalid && "PENDING".equals(row.validityReviewStatus()),
				row.excludedAt(),
				row.excludedByName(),
				row.completedAt(),
				row.latestNextAction());
	}

	/**
	 * 판정 근거 문구. 사유가 여럿이면 첫 번째만 쓴다 — 화면의 `판정 근거` 열이 한 줄이다.
	 *
	 * <p>사유를 합치지 않는 이유: 한 교육생에게 복수 사유가 동시에 성립할 수 있지만
	 * (`interview_candidate_reason`이 1:N인 이유) 화면은 위험 유형 배지 하나와 근거 한 줄만
	 * 그린다. 여러 줄을 이어 붙이면 열 폭을 넘겨 표가 깨진다.
	 */
	private static String firstSummary(InterviewListRow row) {
		// 32차 R4 — 시드에 남은 [SEVERE]·[WARN]을 걷어낸다. 생성 코드는 30차 R8에서 이미
		// 고쳤고, 이미 적재된 문장만 남아 있다.
		return row.reasonSummaries().isEmpty() ? null
				: RiskSummaryText.stripSeverityTag(row.reasonSummaries().get(0));
	}

	/**
	 * 브리프 상태 4종. 화면 버튼 문구가 여기서 갈린다.
	 *
	 * <pre>
	 * NONE       브리프 생성   POST  → AI 호출, 20~30초 대기
	 * FAILED     다시 생성     POST  재시도(같은 멱등키)
	 * DRAFT      브리프 열기   GET   즉시
	 * CONFIRMED  브리프 수정   GET   즉시
	 * </pre>
	 *
	 * <p>첫 클릭만 동기 LLM 호출이라 20~30초 걸리고 그다음부터는 즉시 뜬다. 문구를 하나로 두면
	 * 매니저가 앱이 멈췄다고 느낀다.
	 *
	 * <p>{@code FAILED}는 생성이 503으로 끊긴 자리다 — 행을 지우지 않는 이유는 태운 토큰을
	 * 원장에 남겨야 하고 {@code last_request_id}/{@code fingerprint}가 중복 호출 방지 장치라서다.
	 */
	private static String briefState(InterviewListRow row) {
		if (row.briefStatus() == null) {
			return "NONE";
		}
		if (!row.briefHasContent()) {
			return "FAILED";
		}
		return "CONFIRMED".equals(row.briefStatus()) ? "CONFIRMED" : "DRAFT";
	}

	private static Map<String, Long> toCounts(List<InterviewCountRow> rows) {
		Map<String, Long> counts = emptyCounts(STATUSES);
		for (InterviewCountRow row : rows) {
			counts.merge(row.status(), row.count(), Long::sum);
		}
		return counts;
	}

	private static Map<String, Long> toRiskCounts(List<InterviewCountRow> rows) {
		Map<String, Long> counts = emptyCounts(RISK_TYPES);
		for (InterviewCountRow row : rows) {
			counts.merge(row.riskType(), row.count(), Long::sum);
		}
		return counts;
	}

	/** 0인 칸도 키를 남긴다 — 화면이 `제외 0`처럼 0을 직접 그린다. */
	private static Map<String, Long> emptyCounts(List<String> keys) {
		Map<String, Long> counts = new LinkedHashMap<>();
		keys.forEach(key -> counts.put(key, 0L));
		return counts;
	}
}
