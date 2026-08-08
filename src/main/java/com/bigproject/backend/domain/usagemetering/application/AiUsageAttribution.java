package com.bigproject.backend.domain.usagemetering.application;

import com.bigproject.backend.domain.usagemetering.domain.AiUsage;

import java.util.UUID;

/**
 * AI가 알 수 없어서 <b>백엔드가 채워야 하는</b> 원장 컬럼들.
 *
 * <p>AI는 담당 경계상 토큰·모델·지연·상태만 보낸다. 그런데 {@code ai_usage}는
 * {@code org_id}·{@code trigger_type}이 NOT NULL이고, 비용 귀속(기수·반·프로젝트)도 AI가
 * 모르는 값이다. 이 레코드가 그 공백을 메운다 — 호출부는 자기가 아는 맥락을 여기 담아
 * {@link AiUsageRecorder}에 넘긴다.
 *
 * @param orgId            NOT NULL. 없으면 원장 행 자체를 만들 수 없다.
 * @param actorUserId      이 호출을 유발한 사용자. 배치·스케줄 실행이면 null이다.
 * @param cohortId         비용 귀속 축. null이면 {@code attribution_status}가 내려간다.
 * @param classId          <b>호출 시점</b>의 반. 이후 반 배정이 바뀌어도 갱신하지 않는다.
 * @param projectId        비용 귀속 축.
 * @param triggerType      NOT NULL. 사용자 요청이면 {@code USER}, 스케줄러면 {@code SCHEDULED}.
 * @param contextIdOverride AI가 준 {@code contextId}를 덮어쓸 실제 PK.
 *                          <b>{@code REPORT_SNAPSHOT}·{@code CURRICULUM_ANALYSIS}에서 반드시 필요하다</b> —
 *                          AI는 그 PK를 받은 적이 없어 자기 내부 jobId를 보내온다. 덮어쓰지 않으면
 *                          원장이 존재하지 않는 엔터티를 가리켜 비용이 영구 미귀속으로 남는다.
 *                          {@code ANALYSIS_JOB}·{@code ASSESSMENT_SESSION}·{@code INTERVIEW_BRIEF}는
 *                          AI가 아는 값을 그대로 주므로 null로 둔다.
 */
public record AiUsageAttribution(
		UUID orgId,
		UUID actorUserId,
		UUID cohortId,
		UUID classId,
		UUID projectId,
		AiUsage.TriggerType triggerType,
		String contextIdOverride
) {

	/** 사용자 요청에서 시작된 기수 단위 호출(리포트 생성 등). 반 축이 없다. */
	public static AiUsageAttribution userTriggered(UUID orgId, UUID actorUserId, UUID cohortId, UUID projectId,
			String contextIdOverride) {
		return new AiUsageAttribution(orgId, actorUserId, cohortId, null, projectId,
				AiUsage.TriggerType.USER, contextIdOverride);
	}
}
