package com.bigproject.backend.domain.intervention.application.dto;

import com.bigproject.backend.global.ai.AiUsageEnvelope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/**
 * {@code POST {AI}/api/v0/interview-briefs} 성공 응답(200).
 *
 * <p><b>이 응답이 곧 결과다</b> — jobId도 폴링도 없다. 리포트({@code POST /reports})가
 * 202 + job 폴링인 것과 달리 동기 200인 이유는 하나, <i>매니저가 화면 앞에서 기다리기</i>
 * 때문이다(AI README).
 *
 * <p>{@link JsonIgnoreProperties}로 모르는 필드를 흘려보낸다 — AI가 필드를 먼저 늘려도
 * 역직렬화에서 깨지지 않아야 한다.
 *
 * @param openingRemark 1~3문장 구어체. 교육생 이름은 부르되 점수·단계·위험 유형은 직접 언급하지 않는다
 * @param items         4~8개(첫 면담이면 6~8개). {@code suggestedOrder}는 1부터 중복 없는 연속 정수다
 * @param aiUsage       이 요청이 태운 LLM 호출 기록. 브리프는 호출 1회라 보통 1행이다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterviewBriefAiResponse(
		String openingRemark,
		List<Item> items,
		List<AiUsageEnvelope> aiUsage) {

	/**
	 * @param interviewSourceId ★ <b>요청에서 받은 값 중 하나여야 한다.</b> 모델이 새 UUID를
	 *                          지어내면 {@code interview_brief_item.interview_source_id}가
	 *                          {@code UUID NOT NULL}이라 그 행이 통째로 저장 불가다 —
	 *                          저장 전에 반드시 대조한다
	 * @param questionRationale 매니저만 보는 근거. 어떤 데이터에서 나온 질문인지 밝힌다
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Item(
			String questionText,
			String questionRationale,
			Integer suggestedOrder,
			UUID interviewSourceId) {
	}
}
