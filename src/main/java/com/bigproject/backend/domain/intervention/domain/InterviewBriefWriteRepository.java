package com.bigproject.backend.domain.intervention.domain;

import com.bigproject.backend.domain.intervention.application.dto.InterviewBriefAiResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 브리프 생성·저장 쓰기. */
public interface InterviewBriefWriteRepository {

	/**
	 * 면담 행을 만든다. 후보 상태를 {@code INTERVIEW_CREATED}로 올리는 것과
	 * <b>원자적으로</b> 커밋해야 한다(테이블 COMMENT).
	 */
	UUID insertInterview(UUID candidateId, UUID assigneeUserId);

	/** 후보 → {@code INTERVIEW_CREATED}. */
	void markInterviewCreated(UUID candidateId);

	/**
	 * 브리프 행을 <b>AI 호출 전에</b> 만든다.
	 *
	 * <p>AI 스키마 주석: <i>"백엔드가 AI 호출 전에 브리프 행을 만들고 그 id를 실어 보낸다.
	 * last_request_id/fingerprint 쌍이 중복 호출 방지 장치라, 호출 후에 만들면 대조할 대상이
	 * 없어 장치가 무의미해진다."</i>
	 *
	 * @param briefType {@code STANDARD} 또는 {@code INVALID_ATTEMPT}. 무효 확인 결과로 갈린다
	 */
	UUID insertBriefDraft(UUID interviewId, UUID candidateId, String briefType,
			int versionNo, boolean firstInterview, UUID actorUserId);

	/** 이 면담의 다음 브리프 버전 번호. 첫 생성이면 1이다. */
	int nextVersionNo(UUID interviewId);

	/**
	 * 생성이 끊겨 <b>내용 없이 남은 DRAFT</b>를 찾는다.
	 *
	 * <p>{@code uq_interview_brief_current_draft}가 면담당 DRAFT를 1건으로 강제하므로
	 * 재시도할 때 새로 만들 수 없다 — 그 행을 <b>그대로 재사용</b>해야 한다.
	 *
	 * <p>내용이 있는 DRAFT는 제외한다. 그건 정상 생성돼 매니저가 아직 저장하지 않은
	 * 브리프라, 재시도 대상이 아니라 그대로 보여줄 대상이다.
	 */
	Optional<ExistingDraft> findReusableDraft(UUID interviewId);

	/** 재사용할 실패 DRAFT. 붙어 있던 근거·항목을 정리해야 하므로 id를 함께 준다. */
	record ExistingDraft(UUID briefId, int versionNo) {
	}

	/**
	 * 재시도 전 청소. 앞선 시도가 만들다 만 근거와 항목을 지운다.
	 *
	 * <p>지우지 않으면 {@code interview_source}가 시도할 때마다 쌓여, AI가 어느 근거를
	 * 골라도 <b>이미 버려진 시도의 id</b>일 수 있다.
	 */
	void clearDraftArtifacts(UUID briefId, UUID interviewId);

	/**
	 * 생성 결과 저장.
	 *
	 * <p>{@code opening_remark_text}와 {@code opening_remark_generated_at}은
	 * CHECK가 짝을 강제하므로 함께 채운다. {@code last_request_id}/{@code fingerprint}도 한 쌍이다.
	 */
	void saveGeneratedBrief(UUID briefId, String openingRemark, UUID requestId, String requestFingerprint);

	/**
	 * 질문 항목 저장.
	 *
	 * <p>{@code source_type}은 근거의 종류를 복사한다 — 시드 실측값이 {@code CANDIDATE_REASON}이다.
	 * {@code is_selected}는 생성 시 {@code FALSE}이고 확정(IV-06) 때 {@code TRUE}가 된다.
	 */
	void insertBriefItems(UUID briefId, List<InterviewBriefAiResponse.Item> items);

	/** 생성 이력. {@code INITIALIZE_BRIEF} + {@code CREATED}. */
	void insertItemHistory(UUID briefId, UUID actorUserId, UUID requestId);

	/** 이 교육생에게 종결된 면담이 있는가. {@code is_first_interview} 판정에 쓴다. */
	boolean hasPriorCompletedInterview(UUID traineeUserId);
}
