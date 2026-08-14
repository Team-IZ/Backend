package com.bigproject.backend.domain.intervention.domain;

import com.bigproject.backend.domain.intervention.application.dto.InterviewBriefAiResponse;

import java.util.List;
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
