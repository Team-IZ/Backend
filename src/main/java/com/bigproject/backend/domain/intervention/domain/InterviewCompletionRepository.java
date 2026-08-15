package com.bigproject.backend.domain.intervention.domain;

import java.util.List;
import java.util.UUID;

/**
 * 면담 종결 쓰기 (IV-06).
 *
 * <h2>2026-08-14 DDL 개정으로 확정된 절차</h2>
 *
 * <p>화면은 {@code [저장하고 종결]} 하나로 브리프 확정과 면담 종결을 함께 한다.
 * DB도 그에 맞춰 <b>{@code PENDING → COMPLETED} 직행</b>을 허용했다.
 *
 * <blockquote>
 * "화면은 진행 중 단계를 노출하지 않고 [저장하고 종결] 한 번으로 면담을 마치므로
 * PENDING→COMPLETED 직행 전이를 허용합니다. IN_PROGRESS는 건너뛸 수 있는 선택 상태입니다."
 * </blockquote>
 *
 * <h2>종결 후 재저장은 브리프를 건드리지 않는다</h2>
 *
 * <blockquote>
 * "종결된 면담을 다시 열어 수정하는 대상은 브리프(여는 말·확인 질문)가 아니라 면담 기록인
 * InterviewCause와 InterviewActivity이며, 브리프 버전은 종결 시점 그대로 고정됩니다."
 * </blockquote>
 */
public interface InterviewCompletionRepository {

	/**
	 * 원인 분류를 <b>통째로 교체</b>한다. APPEND-ONLY가 아니다 —
	 * 테이블 COMMENT가 "기존 행 삭제와 새 선택 삽입을 한 트랜잭션으로 처리"를 규정한다.
	 *
	 * <p>선택 0건도 허용한다. 그 경우 행이 없는 상태로 표현된다.
	 */
	void replaceCauses(UUID interviewId, List<String> causeCodes, UUID actorUserId);

	/**
	 * 매니저 기록을 <b>덧붙인다</b>. 기존 행을 고치지 않는다 —
	 * "최신 행이 현재 값"이라 재저장 이력이 그대로 남는다.
	 *
	 * <p>⚠️ 조치(라우팅 목적지)는 저장하지 않는다. 원인에서 파생되는 값이라 화면이 계산한다.
	 */
	void appendActivity(UUID interviewId, String why, String nextAction, UUID actorUserId);

	/** 브리프 확정. 항목을 전부 {@code is_selected=TRUE}로 올린다. */
	void confirmBrief(UUID briefId, UUID actorUserId);

	/** 확정 이력. {@code CONFIRM_BRIEF} + {@code SELECTED}. */
	void insertConfirmHistory(UUID briefId, UUID actorUserId, UUID requestId);

	/**
	 * 확정 전 잠금 재검증. <b>CONFIRMED 브리프 정확히 1건 + 선택 항목 1건 이상</b>을 요구한다
	 * (테이블 COMMENT).
	 *
	 * @return 선택된 항목 수. 0이면 확정할 수 없다
	 */
	int countSelectedItems(UUID briefId);

	/**
	 * {@code PENDING → COMPLETED} 직행.
	 *
	 * <p>{@code ck_interview_status_2}가 COMPLETED에 시작 정보를 요구하므로
	 * {@code started_at}·{@code started_by}를 같은 문장에서 채운다 — 브리프를 연 시각을
	 * 알 수 없으면 {@code completed_at}과 같은 값을 쓴다(COMMENT가 그렇게 규정한다).
	 *
	 * @return 갱신된 행 수. 0이면 이미 종결됐거나 다른 요청이 먼저 바꿨다
	 */
	int completeInterview(UUID interviewId, UUID actorUserId);

	/**
	 * 상태 이력 1행. <b>존재하지 않은 IN_PROGRESS 전이를 만들어 넣지 않는다</b> —
	 * 직행 종결은 {@code PENDING→COMPLETED} 한 줄이다.
	 */
	void insertStatusHistory(UUID interviewId, UUID actorUserId, UUID requestId);

	/** 이미 종결된 면담인가. 재저장 경로를 가른다. */
	boolean isCompleted(UUID interviewId);
}
