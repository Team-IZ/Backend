package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationJob;
import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationRequest;
import com.bigproject.backend.domain.usagemetering.application.AiUsageAttribution;

import java.util.UUID;

/**
 * 리포트 1건을 AI에 만들게 하고 <b>끝날 때까지 기다린다</b>. FastAPI로 나가는 동기 호출 경로다.
 *
 * <h2>🔴 현재 호출자가 없다 — 쓰기 전에 이 절을 읽을 것</h2>
 *
 * <p>리포트 생성은 {@link ReportBatchService}의 <b>dispatch/poll 2단계 비동기</b>로 돈다.
 * 이 동기 경로는 그 앞에 있던 것이고, 지금은 아무도 부르지 않는다. 지우지 않고 남긴 이유는
 * <b>왜 안 쓰는지가 기록으로 남아야</b> 해서다.
 *
 * <ol>
 *   <li><b>되살리려면 {@code ai.report.poll-max-attempts}를 180 이상으로 올려야 한다.</b>
 *       기본값 {@code PT2S × 60 = 120초}는 <b>2026-08-09 실측 {@code latencyMs=129108}</b>
 *       (2분 9초)에 이미 못 미친다 — 그대로 두면 <b>성공한 job을 타임아웃으로 처리한다.</b>
 *       180이라는 숫자도 그 실측에서 나온 것이라 언제든 또 넘을 수 있다. 되살릴 때 다시 재고 정할 것.</li>
 *   <li><b>단건 재생성이 필요하면 이 경로가 아니다.</b> {@link ReportBatchService}의 단건
 *       진입점을 쓴다 — 202만 받고 끊으므로 스레드를 붙들지 않고, 기존 poll이 결과를 회수한다.
 *       "단건이니까 동기로 부르면 되겠네"가 이 클래스를 되살리는 가장 흔한 경로인데,
 *       회차당 75건 규모에서 그렇게 하면 타임아웃이 아니라 <b>스레드 고갈</b>로 나타난다.</li>
 * </ol>
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
 * <b>결과 저장</b>({@code report_snapshot}·{@code report_evidence})은 여기 없다. 호출부가 조립한
 * 요청을 받고 종료된 job을 그대로 돌려줄 뿐이다.
 *
 * <p>이 클래스가 만들어질 당시엔 그 두 가지를 쓸 코드가 없었지만, 지금은
 * {@code JdbcReportPayloadRepository}(조립)와 {@code ReportRunFinalizer}(저장)에 있다.
 * 되살릴 일이 있어도 <b>그쪽을 다시 만들지 말 것.</b>
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
