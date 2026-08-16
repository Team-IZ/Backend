package com.bigproject.backend.domain.intervention.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MG-04 면담 브리프 조회.
 *
 * <h2>{@code manager_interview_brief_view}는 {@code interview} 행이 있어야 나온다</h2>
 *
 * <p>뷰가 {@code FROM public.interview i}로 시작하므로 <b>아직 브리프를 만들지 않은 케이스는
 * 이 뷰에 없다.</b> 조회 경로가 {@code caseId}(=candidate_id)로 들어오므로 면담 유무를 먼저
 * 확인한 뒤(=목록 뷰) 이 뷰를 읽는다.
 *
 * <h2>브리프 본문을 뷰에서 읽지 않는 이유</h2>
 *
 * <p>뷰는 상태·개수·쓰기 가능 여부만 준다({@code brief_persistence_status}·{@code can_*}·
 * {@code persisted_selected_item_count}). 여는 말 원문과 질문 목록은 원장에서 직접 읽는다.
 */
public interface InterviewBriefRepository {

	/** 브리프 헤더. 뷰의 상태·플래그를 그대로 옮긴다. */
	Optional<BriefHeader> findHeader(UUID managerUserId, UUID orgId, UUID interviewId);

	/**
	 * 질문 목록.
	 *
	 * <p>🔴 <b>{@code is_selected}로 거르지 않는다.</b> 화면에는 질문을 고르는 UI가 없어
	 * (정의서 §7 "② 질문 고르기 — 하지 않는다") 전 항목을 그대로 그린다. 시드에는 4개 중
	 * 1개가 {@code FALSE}인 데이터가 있어, 걸러 내면 질문 하나가 조용히 사라져 보인다.
	 */
	List<BriefItem> findItems(UUID briefId);

	/** 매니저가 저장해 둔 원인 분류. 재오픈 시 체크 상태를 복원한다. */
	List<String> findCauseCodes(UUID interviewId);

	/**
	 * 매니저가 타이핑한 기록의 <b>최신 1건</b>.
	 *
	 * <p>{@code interview_activity}는 APPEND-ONLY라 재저장할 때마다 행이 쌓인다 —
	 * 테이블 COMMENT가 "기존 행을 수정하지 않고 새 행을 덧붙이며 <b>최신 행이 현재 값</b>"이라
	 * 규정했다.
	 */
	Optional<ManagerRecord> findLatestRecord(UUID interviewId);

	/**
	 * 이 교육생의 직전 종결 면담. 브리프 ①칸의 "지난 면담에서 정한 것"에 쓴다.
	 *
	 * <p>약속 이행을 추적하지는 않는다(정의서 §7) — 다음 회차 브리프가 인용하는 것이 전부다.
	 */
	Optional<PriorInterview> findPriorInterview(UUID traineeUserId, UUID currentInterviewId);

	/**
	 * @param briefPersistenceStatus {@code NOT_INITIALIZED} / {@code DRAFT_SAVED} /
	 *                               {@code CONFIRMED} / {@code SUPERSEDED}
	 * @param briefType              {@code STANDARD} / {@code INVALID_ATTEMPT}.
	 *                               후자면 여는 말과 질문이 통째로 다르다(정의서 §6-2)
	 */
	record BriefHeader(
			UUID briefId,
			UUID interviewId,
			UUID traineeUserId,
			String traineeName,
			String briefPersistenceStatus,
			String briefStatus,
			String briefType,
			Integer versionNo,
			boolean firstInterview,
			String openingRemark,
			Instant openingRemarkGeneratedAt,
			boolean canInitialize,
			boolean canEdit,
			boolean canConfirm,
			String actionUnavailableReasonCode) {
	}

	record BriefItem(
			UUID briefItemId,
			String questionText,
			String questionRationale,
			Integer suggestedOrder,
			boolean selected) {
	}

	record ManagerRecord(String why, String nextAction, Instant occurredAt) {
	}

	record PriorInterview(Instant completedAt, String nextAction) {
	}

	/**
	 * 무효 응시 브리프의 "시스템이 본 것".
	 *
	 * <p>화면이 <b>"시스템이 본 것 · 판단은 하지 않습니다"</b>로 감싸 보여준다 — 관찰이지
	 * 판정이 아니다. 무효로 처리할지는 사람이 정한다(9-4).
	 *
	 * <p>저장하지 않고 조회 시점에 계산한다(제안서 B-2 회신). {@code question_text}·
	 * {@code question_answer_text}가 사후 변경되지 않으므로 매번 계산해도 결과가 같다.
	 */
	Optional<VoidEvidence> findVoidEvidence(UUID candidateId);

	/**
	 * @param unanswered     <b>문제 단위</b> 무응답 수. 그 문제의 단계가 전부 답 없이 끝난 경우다
	 * @param totalQuestions 그 회차 문제 수(최대 3)
	 * @param copied         답변이 질문 문장과 같은 단계가 하나라도 있는가
	 * @param durationMin    세션 시작~종료 분
	 */
	record VoidEvidence(int unanswered, int totalQuestions, boolean copied, int durationMin) {
	}
}
