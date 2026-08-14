package com.bigproject.backend.domain.intervention.application;

import com.bigproject.backend.domain.intervention.domain.InterventionErrorCode;
import com.bigproject.backend.domain.intervention.domain.InterviewExclusionRepository;
import com.bigproject.backend.domain.intervention.domain.InterviewListRepository;
import com.bigproject.backend.domain.intervention.domain.InterviewListRepository.InterviewCountRow;
import com.bigproject.backend.domain.intervention.domain.InterviewListRepository.InterviewListRow;
import com.bigproject.backend.domain.intervention.domain.InterviewRoundRepository;
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

	@Override
	@Transactional(readOnly = true)
	public InterviewListResult findCases(InterviewListCriteria criteria) {
		List<InterviewListRow> rows = interviewListRepository.findCases(
				new InterviewListRepository.InterviewListQuery(
						criteria.managerUserId(),
						criteria.orgId(),
						criteria.assessmentRoundId(),
						criteria.search(),
						criteria.status(),
						criteria.riskType(),
						criteria.classId()));

		List<InterviewCountRow> countRows = interviewListRepository.countByRound(
				criteria.managerUserId(), criteria.orgId(), criteria.assessmentRoundId());

		List<InterviewCaseView> items = rows.stream().map(InterviewServiceImpl::toView).toList();

		// 회차를 못 찾는 경우는 담당 밖 회차 ID를 넣어 호출한 것이다. 목록은 이미 뷰가 걸러
		// 비어 있으므로 404를 내지 않고 round만 null로 둔다 — 화면이 빈 목록을 그리면 된다.
		RoundView round = roundRepository
				.findRoundMeta(criteria.managerUserId(), criteria.orgId(), criteria.assessmentRoundId())
				.map(InterviewServiceImpl::toRoundView)
				.orElse(null);

		List<ClassOptionView> classes = interviewListRepository
				.findManagedClasses(criteria.managerUserId(), criteria.orgId()).stream()
				.map(option -> new ClassOptionView(option.classId(), option.className()))
				.toList();

		return new InterviewListResult(
				items, items.size(), toCounts(countRows), toRiskCounts(countRows), classes, round);
	}

	@Override
	@Transactional(readOnly = true)
	public List<RoundOptionView> findRoundOptions(UUID managerUserId, UUID orgId) {
		return roundRepository.findRoundOptions(managerUserId, orgId).stream()
				.map(option -> new RoundOptionView(
						option.assessmentRoundId(), option.label(), option.roundNo(), option.status()))
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
		return row.reasonSummaries().isEmpty() ? null : row.reasonSummaries().get(0);
	}

	/**
	 * 브리프 상태 4종. 화면 버튼 문구가 여기서 갈린다.
	 *
	 * <pre>
	 * NONE       브리프 생성   POST  → AI 호출, 수 초 대기
	 * FAILED     다시 생성     POST  재시도(같은 멱등키)
	 * DRAFT      브리프 열기   GET   즉시
	 * CONFIRMED  브리프 수정   GET   즉시
	 * </pre>
	 *
	 * <p>첫 클릭만 동기 LLM 호출이라 수 초 걸리고 그다음부터는 즉시 뜬다. 문구를 하나로 두면
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
