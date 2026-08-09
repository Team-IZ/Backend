package com.bigproject.backend.domain.reporting.infrastructure.ai;

import com.bigproject.backend.domain.usagemetering.application.AiUsageAttribution;
import com.bigproject.backend.domain.usagemetering.application.AiUsageRecorder;
import com.bigproject.backend.global.ai.AiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 리포트 생성용 AI 호출. {@link AiClient}(전송)와 {@link AiUsageRecorder}(원장)를 묶어
 * <b>리포트 도메인이 알아야 할 형태</b>로 만든다.
 *
 * <h2>이 클래스가 하는 일과 하지 않는 일</h2>
 *
 * <p><b>한다</b> — AI에 생성을 요청하고, job 상태를 읽고, 태운 토큰을 원장에 남긴다.
 *
 * <p><b>하지 않는다</b> — 요청 본문 조립(transcript·analysisDocuments·teaches)과
 * 결과를 {@code report_snapshot}으로 쓰는 일. 둘 다 Assessment·Curriculum 도메인의 데이터를
 * 읽어야 해서 이 계층의 책임이 아니고, 파이프라인을 붙일 때 그 도메인이 채운다.
 *
 * <h2>폴링은 호출부가 한다</h2>
 *
 * <p>{@link #fetchJob}을 반복해 부르는 주체를 여기 두지 않았다. 리포트 생성은 문제 수만큼
 * 도는 배치라 폴링 간격·동시성·중단 조건이 배치의 관심사인데, 그것을 이 클래스가 정하면
 * 호출부마다 다른 정책이 필요할 때 갈아끼울 수 없다.
 *
 * <p>⚠️ 사용자 요청 스레드에서 폴링하면 안 된다. AI의 LLM 호출이 최악 2분이라
 * 톰캣 스레드가 그동안 묶인다 — 비동기 작업으로 빼야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportGenerationAiClient {

	private static final String GENERATE_PATH = "/reports";

	private final AiClient aiClient;
	private final AiUsageRecorder aiUsageRecorder;

	/**
	 * 생성을 요청하고 jobId를 받는다. 202라 결과는 아직 없다.
	 *
	 * <p>이 응답에는 {@code aiUsage}가 없다 — LLM이 아직 안 돌았기 때문이다.
	 * 원장은 {@link #fetchJob}이 종료 상태를 읽을 때 남는다.
	 */
	public ReportGenerationJob.Accepted requestGeneration(ReportGenerationRequest request, String traceId) {
		ReportGenerationJob.Accepted accepted = aiClient.post(
				GENERATE_PATH, request, ReportGenerationJob.Accepted.class, request.idempotencyKey(), traceId);

		log.info("AI 리포트 생성 접수: problemId={}, jobId={}", request.problemId(), accepted.jobId());
		return accepted;
	}

	/**
	 * job 상태를 읽고, 종료 상태면 태운 토큰을 원장에 남긴다.
	 *
	 * <p>🔴 {@code contextEntityId}를 반드시 넘겨야 한다. AI는 우리 PK를 받은 적이 없어
	 * {@code aiUsage.contextId}에 <b>자기 내부 jobId</b>를 넣어 보낸다. 그대로 저장하면 원장이
	 * 존재하지 않는 엔터티를 가리켜 비용이 영구 미귀속으로 남는다.
	 *
	 * <p><b>무엇의 PK인가는 {@code attribution}이 정한다.</b> 생성 배치는
	 * {@link AiUsageAttribution#reportGenerationItem}으로 {@code REPORT_GENERATION_ITEM}을 지정하고
	 * {@code generation_item_id}를 넘긴다 — 리포트 1건에 AI 호출이 문제 수만큼 나가므로 스냅샷
	 * 단위로 묶으면 문제별 원가가 사라진다. 유형을 지정하지 않으면 AI가 준 {@code REPORT_SNAPSHOT}이
	 * 그대로 쓰이고, 그때 이 인자는 {@code report_snapshot.snapshot_id}여야 한다.
	 *
	 * @param attribution     기관·기수·프로젝트 등 AI가 모르는 귀속 정보. 컨텍스트 유형 override도 여기 있다.
	 * @param contextEntityId {@code aiUsage.contextId}를 덮을 실제 PK. {@code attribution}에 이미
	 *                        {@code contextIdOverride}가 있으면 그쪽이 우선한다.
	 */
	public ReportGenerationJob.Status fetchJob(String jobId, String traceId,
			AiUsageAttribution attribution, UUID contextEntityId) {

		ReportGenerationJob.Status job = aiClient.get(
				GENERATE_PATH + "/" + jobId, ReportGenerationJob.Status.class, traceId);

		if (job.isTerminal()) {
			AiUsageAttribution resolved = new AiUsageAttribution(
					attribution.orgId(),
					attribution.actorUserId(),
					attribution.cohortId(),
					attribution.classId(),
					attribution.projectId(),
					attribution.triggerType(),
					// 호출부가 이미 채워 뒀으면 그것을 쓴다. 인자는 아직 안 채운 경로를 위한 보조다.
					attribution.contextIdOverride() != null ? attribution.contextIdOverride()
							: (contextEntityId == null ? null : contextEntityId.toString()),
					attribution.contextTypeOverride()
			);

			int recorded = aiUsageRecorder.record(job.aiUsage(), resolved);
			log.info("AI 리포트 job 종료: jobId={}, status={}, 원장 {}행 적재", jobId, job.status(), recorded);
		}

		return job;
	}
}
