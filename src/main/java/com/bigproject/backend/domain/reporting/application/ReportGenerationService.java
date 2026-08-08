package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationJob;
import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationRequest;
import com.bigproject.backend.domain.usagemetering.application.AiUsageAttribution;

import java.util.UUID;

/**
 * 리포트 1건을 AI에 만들게 하고 <b>끝날 때까지 기다린다</b>. FastAPI로 나가는 실제 호출 지점이다.
 *
 * <h2>왜 기다려야 하나</h2>
 *
 * <p>AI의 {@code POST /reports}는 202 + jobId만 주고 생성은 백그라운드에서 돈다. 결과를 얻으려면
 * {@code GET /reports/{jobId}}를 종료 상태가 될 때까지 폴링해야 한다 — 면담 브리프가 동기 계약인
 * 것과 다르다. 그 폴링 루프가 이 서비스다.
 *
 * <p>⚠️ <b>사용자 요청 스레드에서 부르면 안 된다.</b> 최악의 경우 2분 넘게 블로킹된다
 * (AI가 LLM 호출 1회에 20초 × 최대 6회 재시도). 배치나 비동기 작업에서 불러야 한다.
 *
 * <h2>이 서비스가 하지 않는 일</h2>
 *
 * <p><b>요청 본문 조립</b>({@code transcript}·{@code analysisDocuments}·{@code teaches})과
 * <b>결과 저장</b>({@code report_snapshot})은 여기 없다. 둘 다 이 저장소에 아직 코드가 없는
 * 도메인({@code assessment}·{@code codeanalysis}·{@code curriculum})의 데이터를 읽거나
 * 매핑되지 않은 테이블({@code report_generation_run})을 써야 해서, 추측으로 만들면 틀린 SQL이
 * 남는다. 호출부가 조립한 요청을 받고 종료된 job을 그대로 돌려준다.
 */
public interface ReportGenerationService {

	/**
	 * 생성을 요청하고 종료 상태가 될 때까지 폴링한다.
	 *
	 * <p>태운 토큰은 종료 시점에 {@code ai_usage}에 적재된다 — <b>실패한 job도 마찬가지다.</b>
	 *
	 * @param snapshotId {@code report_snapshot.snapshot_id}. {@code aiUsage.contextId}를 이 값으로 덮는다.
	 *                   🔴 <b>AI를 부르기 전에 스냅샷 행을 먼저 만들어야</b> 이 값이 있다.
	 *                   null이면 AI 내부 jobId가 그대로 원장에 남아 비용이 영구 미귀속이 된다.
	 * @return 종료 상태의 job. {@code SUCCEEDED}·{@code PARTIAL}·{@code FAILED} 중 하나다 —
	 *         <b>FAILED도 예외가 아니라 반환값이다.</b> 호출부가 실패 사실을 저장해야 하고,
	 *         예외로 던지면 그 정보가 스택트레이스로만 남는다.
	 * @throws com.bigproject.backend.global.ai.AiCallException 전송 실패, 또는 제한 시간 안에
	 *         종료 상태에 도달하지 못한 경우
	 */
	ReportGenerationJob.Status generate(
			ReportGenerationRequest request,
			AiUsageAttribution attribution,
			UUID snapshotId,
			String traceId
	);
}
