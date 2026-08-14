package com.bigproject.backend.domain.intervention.application;

import com.bigproject.backend.domain.intervention.application.dto.InterviewBriefAiResponse;
import com.bigproject.backend.domain.intervention.domain.InterventionErrorCode;
import com.bigproject.backend.domain.intervention.domain.InterviewBriefRepository;
import com.bigproject.backend.domain.intervention.domain.InterviewBriefRepository.BriefHeader;
import com.bigproject.backend.domain.intervention.domain.InterviewCaseLookupRepository;
import com.bigproject.backend.domain.intervention.domain.InterviewCaseLookupRepository.CaseSummary;
import com.bigproject.backend.domain.usagemetering.application.AiUsageAttribution;
import com.bigproject.backend.domain.usagemetering.application.AiUsageRecorder;
import com.bigproject.backend.domain.usagemetering.domain.AiUsage;
import com.bigproject.backend.global.ai.AiCallException;
import com.bigproject.backend.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewBriefServiceImpl implements InterviewBriefService {

	private final InterviewCaseLookupRepository caseLookupRepository;
	private final InterviewBriefRepository briefRepository;
	private final InterviewBriefWriter briefWriter;
	private final com.bigproject.backend.domain.intervention.domain.InterviewCompletionRepository completionRepository;
	private final AiInterviewBriefClient aiClient;
	private final AiUsageRecorder aiUsageRecorder;

	/**
	 * 저장하고 종결. <b>2026-08-14 DDL 개정으로 확정된 6단계</b>를 한 트랜잭션으로 돌린다.
	 *
	 * <p>순서가 중요하다 — 브리프 확정이 면담 종결보다 <b>먼저</b>다.
	 * "PENDING 면담에서만 초기화·저장·확정을 허용하고 IN_PROGRESS·COMPLETED에서는 읽기
	 * 전용입니다. 단 PENDING→COMPLETED 직행 종결 트랜잭션 안에서는 상태 전이 직전에 마지막
	 * 확정을 허용합니다."(테이블 COMMENT)
	 */
	@Override
	@Transactional
	public BriefView saveAndComplete(UUID managerUserId, UUID orgId, UUID caseId,
			List<String> causes, String why, String nextAction) {
		CaseSummary summary = caseLookupRepository.findCase(managerUserId, orgId, caseId)
				.orElseThrow(() -> new ApiException(InterventionErrorCode.INTERVIEW_CASE_NOT_FOUND));

		if (summary.interviewId() == null) {
			throw new ApiException(InterventionErrorCode.INTERVIEW_BRIEF_NOT_CREATED);
		}

		UUID interviewId = summary.interviewId();
		UUID requestId = UUID.randomUUID();

		// ① 원인 — 선택 집합 전체를 대체한다(APPEND-ONLY가 아니다).
		completionRepository.replaceCauses(interviewId, causes, managerUserId);

		// ② 매니저 기록 — 덧붙인다. 재저장해도 기존 행을 고치지 않고 최신 행이 현재 값이 된다.
		completionRepository.appendActivity(interviewId, why, nextAction, managerUserId);

		/*
		 * 이미 종결된 면담을 다시 저장하는 경우 여기서 끝난다.
		 * 브리프는 종결 시점 그대로 고정이고, 수정 대상은 위 둘뿐이다(테이블 COMMENT).
		 */
		if (completionRepository.isCompleted(interviewId)) {
			return findBrief(managerUserId, orgId, caseId);
		}

		BriefHeader header = briefRepository.findHeader(managerUserId, orgId, interviewId)
				.orElseThrow(() -> new ApiException(InterventionErrorCode.INTERVIEW_BRIEF_NOT_CREATED));
		if (header.briefId() == null || header.openingRemark() == null) {
			// 생성되지 않았거나 실패한 브리프로는 종결할 수 없다 — 확정할 내용이 없다.
			throw new ApiException(InterventionErrorCode.INTERVIEW_BRIEF_NOT_CREATED);
		}

		// ③ 브리프 확정 — 항목을 전부 is_selected=TRUE로 올린다.
		if ("DRAFT".equals(header.briefStatus())) {
			completionRepository.confirmBrief(header.briefId(), managerUserId);
			completionRepository.insertConfirmHistory(header.briefId(), managerUserId, requestId);
		}

		// ④ 잠금 재검증 — CONFIRMED 브리프의 선택 항목이 1건 이상이어야 한다.
		if (completionRepository.countSelectedItems(header.briefId()) == 0) {
			throw new ApiException(InterventionErrorCode.BRIEF_HAS_NO_SELECTED_ITEM);
		}

		// ⑤ 면담 종결 — PENDING에서 COMPLETED로 직행한다.
		if (completionRepository.completeInterview(interviewId, managerUserId) == 0) {
			throw new ApiException(InterventionErrorCode.INTERVIEW_ROW_VERSION_CONFLICT);
		}

		// ⑥ 상태 이력 — PENDING→COMPLETED 1행. 없던 IN_PROGRESS 전이를 지어내지 않는다.
		completionRepository.insertStatusHistory(interviewId, managerUserId, requestId);

		return findBrief(managerUserId, orgId, caseId);
	}

	@Override
	public BriefView createBrief(UUID managerUserId, UUID orgId, UUID caseId, String traceId) {
		CaseSummary summary = caseLookupRepository.findCase(managerUserId, orgId, caseId)
				.orElseThrow(() -> new ApiException(InterventionErrorCode.INTERVIEW_CASE_NOT_FOUND));

		// 무효 확인이 브리프보다 먼저다(정의서 §5). 판정 전에는 briefType(STANDARD/
		// INVALID_ATTEMPT)을 정할 수 없어 여는 말과 질문이 통째로 어긋난다.
		if ("INVALID".equals(summary.riskType()) && !validityReviewSettled(managerUserId, orgId, caseId)) {
			throw new ApiException(InterventionErrorCode.VALIDITY_REVIEW_REQUIRED);
		}

		// 이미 완성된 브리프가 있으면 AI를 부르지 않는다. 재생성은 IV-07이 갖는다.
		if (summary.interviewId() != null) {
			var existing = briefRepository.findHeader(managerUserId, orgId, summary.interviewId());
			if (existing.isPresent() && existing.get().openingRemark() != null) {
				return findBrief(managerUserId, orgId, caseId);
			}
		}

		// TX1 — 행을 만들고 요청을 조립한다. 커밋된 뒤에 AI를 부른다.
		return runGeneration(briefWriter.prepare(summary, managerUserId),
				managerUserId, orgId, caseId, traceId);
	}

	@Override
	public BriefView regenerateBrief(UUID managerUserId, UUID orgId, UUID caseId, String traceId) {
		CaseSummary summary = caseLookupRepository.findCase(managerUserId, orgId, caseId)
				.orElseThrow(() -> new ApiException(InterventionErrorCode.INTERVIEW_CASE_NOT_FOUND));

		if (summary.interviewId() == null) {
			throw new ApiException(InterventionErrorCode.INTERVIEW_BRIEF_NOT_CREATED);
		}
		/*
		 * 종결된 면담의 브리프는 읽기 전용이다 — "브리프 버전은 종결 시점 그대로 고정됩니다"
		 * (테이블 COMMENT). 지난 면담에서 실제로 무엇을 물었는지가 다음 회차 브리프의
		 * askedQuestions로 이어지므로 사후에 바꾸면 그 기록이 사실과 달라진다.
		 */
		if (completionRepository.isCompleted(summary.interviewId())) {
			throw new ApiException(InterventionErrorCode.BRIEF_NOT_EDITABLE);
		}

		return runGeneration(briefWriter.prepareRegeneration(summary, managerUserId),
				managerUserId, orgId, caseId, traceId);
	}

	/**
	 * AI 호출 → 검증 → 저장 → 원장. 생성과 재생성이 공유한다.
	 *
	 * <p>이 구간은 <b>트랜잭션 밖</b>이다. TX1은 이미 커밋됐고 TX2는 {@code saveResult}가 연다.
	 */
	private BriefView runGeneration(InterviewBriefWriter.BriefDraft draft,
			UUID managerUserId, UUID orgId, UUID caseId, String traceId) {
		InterviewBriefAiResponse response;
		try {
			response = aiClient.generate(draft.request(), draft.briefId(), draft.versionNo(), traceId);
		} catch (AiCallException exception) {
			/*
			 * 실패해도 브리프 행을 지우지 않는다 — 태운 토큰을 원장에 남겨야 하고
			 * last_request_id/fingerprint가 중복 호출 방지 장치라 행이 있어야 동작한다.
			 * 화면은 이 상태(briefState=FAILED)에서 [다시 생성]을 그린다.
			 *
			 * ⚠️ AI가 503 봉투에 실어 보낸 aiUsage[]는 현재 AiClient가 파싱하지 않아
			 * 여기까지 오지 않는다 — 실패분 토큰이 원장에서 누락된다(제안서 ⑬).
			 */
			throw new ApiException(exception.retryable()
					? InterventionErrorCode.BRIEF_GENERATION_FAILED_RETRYABLE
					: InterventionErrorCode.BRIEF_GENERATION_FAILED);
		}

		validateSourceIds(response, draft.sourceIds());

		UUID requestId = UUID.randomUUID();
		// TX2 — 결과 저장.
		briefWriter.saveResult(draft.briefId(), response, requestId, fingerprint(draft), managerUserId);

		// 원장은 자체 REQUIRES_NEW라 업무 트랜잭션과 독립이다. 실패해도 예외를 던지지 않는다.
		aiUsageRecorder.record(response.aiUsage(), new AiUsageAttribution(
				orgId, managerUserId, null, null, null, AiUsage.TriggerType.USER, null, null));

		return findBrief(managerUserId, orgId, caseId);
	}

	/**
	 * AI가 지어낸 근거 ID를 걸러낸다.
	 *
	 * <p>{@code interview_brief_item.interview_source_id}가 {@code UUID NOT NULL}이라
	 * 없는 값을 넣으면 그 행이 통째로 저장 불가다. AI 엔진도 자체 검증하지만
	 * <b>모델 출력을 무검증으로 믿지 않는 것</b>이 이 계약의 전제다(AI 스키마 §5.1).
	 */
	private static void validateSourceIds(InterviewBriefAiResponse response, java.util.Set<UUID> allowed) {
		if (response.items() == null || response.items().isEmpty()) {
			// AI 계약은 4~8개를 요구한다. 비어 있으면 역직렬화가 어긋났을 가능성이 크다 —
			// 필드 이름이 다르면 Jackson이 조용히 빈 목록을 만든다.
			log.error("브리프 생성 응답에 items가 없습니다. openingRemark={}",
					response.openingRemark() == null ? "(없음)" : "있음");
			throw new ApiException(InterventionErrorCode.BRIEF_GENERATION_FAILED);
		}

		List<UUID> rejected = response.items().stream()
				.map(InterviewBriefAiResponse.Item::interviewSourceId)
				.filter(id -> id == null || !allowed.contains(id))
				.toList();

		if (!rejected.isEmpty()) {
			/*
			 * 어느 쪽이 어긋났는지 로그에 남긴다. 둘 중 하나다.
			 *   · AI가 없는 UUID를 지어냈다        → 모델·프롬프트 문제
			 *   · 우리가 보낸 id를 AI가 못 읽었다  → 필드 이름·직렬화 문제(전부 null로 온다)
			 */
			log.error("브리프 항목의 interviewSourceId가 요청 집합에 없습니다. "
					+ "거부={} (허용 {}건: {})", rejected, allowed.size(), allowed);
			throw new ApiException(InterventionErrorCode.BRIEF_GENERATION_FAILED);
		}
	}

	/**
	 * 요청 지문. 같은 멱등키로 다른 본문이 오는 것을 백엔드도 잡는다 —
	 * {@code last_request_id}와 {@code last_request_fingerprint}가 CHECK로 묶인 한 쌍인 이유다.
	 */
	private static String fingerprint(InterviewBriefWriter.BriefDraft draft) {
		return Integer.toHexString(draft.request().hashCode());
	}

	/** 무효 확인이 끝났는가. {@code PENDING}이면 아직 사람이 판정하지 않았다. */
	private boolean validityReviewSettled(UUID managerUserId, UUID orgId, UUID caseId) {
		return caseLookupRepository.findValidityReviewStatus(managerUserId, orgId, caseId)
				.map(status -> !"PENDING".equals(status))
				.orElse(true);
	}

	@Override
	@Transactional(readOnly = true)
	public BriefView findBrief(UUID managerUserId, UUID orgId, UUID caseId) {
		// 화면은 caseId(candidate_id)만 들고 있는데 브리프 뷰는 interview_id 기준이다.
		// 목록 뷰에서 한 번 짚어 담당 스코프까지 같이 확인한다.
		CaseSummary summary = caseLookupRepository.findCase(managerUserId, orgId, caseId)
				.orElseThrow(() -> new ApiException(InterventionErrorCode.INTERVIEW_CASE_NOT_FOUND));

		if (summary.interviewId() == null) {
			// 아직 브리프를 만들지 않았다. 생성은 POST(IV-04)가 한다 — GET이 만들지 않는다.
			throw new ApiException(InterventionErrorCode.INTERVIEW_BRIEF_NOT_CREATED);
		}

		BriefHeader header = briefRepository.findHeader(managerUserId, orgId, summary.interviewId())
				.orElseThrow(() -> new ApiException(InterventionErrorCode.INTERVIEW_BRIEF_NOT_CREATED));

		if (header.briefId() == null) {
			throw new ApiException(InterventionErrorCode.INTERVIEW_BRIEF_NOT_CREATED);
		}

		List<BriefItemView> items = briefRepository.findItems(header.briefId()).stream()
				.map(item -> new BriefItemView(
						item.briefItemId(), item.questionText(), item.questionRationale(), item.suggestedOrder()))
				.toList();

		return new BriefView(
				caseId,
				summary.traineeUserId(),
				summary.traineeName(),
				summary.className(),
				summary.riskType(),
				summary.riskSummary(),
				"INVALID_ATTEMPT".equals(header.briefType()),
				header.firstInterview(),
				briefState(header),
				header.openingRemark(),
				items,
				briefRepository.findPriorInterview(summary.traineeUserId(), summary.interviewId())
						.map(prior -> new PriorInterviewView(prior.completedAt(), prior.nextAction()))
						.orElse(null),
				savedRecord(summary.interviewId()),
				// ⚠️ concepts는 아직 DB 회신 대기다(제안서 B-3 — GROUP_UNDERPERFORMANCE 지표가
				// 회차 스냅샷에도 생성되는지 RPT 확인 중). 지어낸 값을 넣으면 매니저가 그것을
				// 근거로 면담하므로 빈 배열로 둔다.
				List.of(),
				// 무효 응시 브리프에서만 "시스템이 본 것"을 보여준다(정의서 §6-2).
				// 일반 브리프에 띄우면 면담이 추궁이 된다 — 이 화면이 하려는 일은
				// "다음 한 주를 어디에 쓸지"를 정하는 것이다.
				"INVALID_ATTEMPT".equals(header.briefType())
						? briefRepository.findVoidEvidence(caseId)
								.map(evidence -> new VoidEvidenceView(
										evidence.unanswered(), evidence.totalQuestions(),
										evidence.copied(), evidence.durationMin()))
								.orElse(null)
						: null);
	}

	/**
	 * 매니저 입력 복원. 원인은 {@code interview_cause}, 서술은 {@code interview_activity}에서 온다.
	 *
	 * <p>둘 다 비어 있으면 아직 저장한 적이 없는 브리프라 null을 준다 — 화면이 빈 입력으로 시작한다.
	 */
	private SavedRecordView savedRecord(UUID interviewId) {
		List<String> causes = briefRepository.findCauseCodes(interviewId);
		var record = briefRepository.findLatestRecord(interviewId);

		if (causes.isEmpty() && record.isEmpty()) {
			return null;
		}
		return new SavedRecordView(
				causes,
				record.map(InterviewBriefRepository.ManagerRecord::why).orElse(null),
				record.map(InterviewBriefRepository.ManagerRecord::nextAction).orElse(null));
	}

	/**
	 * 목록과 같은 4종으로 접는다. 뷰의 {@code brief_persistence_status}는 <b>생성 실패를
	 * 구분하지 않아</b>(내용 없는 DRAFT도 {@code DRAFT_SAVED}다) 여는 말 유무를 함께 본다.
	 */
	private static String briefState(BriefHeader header) {
		if (header.briefId() == null) {
			return "NONE";
		}
		if (header.openingRemark() == null) {
			return "FAILED";
		}
		return "CONFIRMED".equals(header.briefStatus()) ? "CONFIRMED" : "DRAFT";
	}
}
