package com.bigproject.backend.domain.reporting.application;

import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationAiClient;
import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationJob;
import com.bigproject.backend.domain.reporting.infrastructure.ai.ReportGenerationRequest;
import com.bigproject.backend.domain.usagemetering.application.AiUsageAttribution;
import com.bigproject.backend.global.ai.AiCallException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 폴링 루프. <b>트랜잭션을 열지 않는다</b> — 이 메서드는 수 분 블로킹될 수 있어서,
 * 트랜잭션 안에 두면 그동안 DB 커넥션이 풀에서 빠져나간 채로 묶인다.
 * 결과를 저장하는 쪽이 자기 트랜잭션을 짧게 여는 것이 맞다.
 */
@Slf4j
@Service
public class ReportGenerationServiceImpl implements ReportGenerationService {

	private final ReportGenerationAiClient aiClient;
	private final Duration pollInterval;
	private final int pollMaxAttempts;
	private final Sleeper sleeper;

	/**
	 * 생성자가 둘이라 {@link Autowired}로 어느 쪽을 쓸지 명시한다 —
	 * 없으면 스프링이 기본 생성자를 찾다가 기동에 실패한다.
	 */
	@Autowired
	public ReportGenerationServiceImpl(
			ReportGenerationAiClient aiClient,
			@Value("${ai.report.poll-interval:PT2S}") Duration pollInterval,
			@Value("${ai.report.poll-max-attempts:60}") int pollMaxAttempts
	) {
		this(aiClient, pollInterval, pollMaxAttempts, Thread::sleep);
	}

	/** 테스트에서 실제로 재우지 않기 위한 생성자. 운영 경로는 위 생성자만 쓴다. */
	ReportGenerationServiceImpl(ReportGenerationAiClient aiClient, Duration pollInterval,
			int pollMaxAttempts, Sleeper sleeper) {
		this.aiClient = aiClient;
		this.pollInterval = pollInterval;
		this.pollMaxAttempts = pollMaxAttempts;
		this.sleeper = sleeper;
	}

	@Override
	public ReportGenerationJob.Status generate(
			ReportGenerationRequest request,
			AiUsageAttribution attribution,
			UUID snapshotId,
			String traceId
	) {
		ReportGenerationJob.Accepted accepted = aiClient.requestGeneration(request, traceId);

		if (accepted.jobId() == null || accepted.jobId().isBlank()) {
			// 202를 받았는데 jobId가 없으면 폴링할 대상이 없다. 재시도해도 같은 응답일 가능성이
			// 높아 retryable=false다 — 계약 위반이지 일시적 장애가 아니다.
			throw new AiCallException(null, "INVALID_JSON", false,
					"AI가 jobId 없이 생성 요청을 접수했습니다: problemId=" + request.problemId());
		}

		for (int attempt = 1; attempt <= pollMaxAttempts; attempt++) {
			sleepBeforePoll();

			// fetchJob이 종료 상태일 때 ai_usage를 적재한다. 여기서 또 부르지 않는다.
			ReportGenerationJob.Status job = aiClient.fetchJob(accepted.jobId(), traceId, attribution, snapshotId);

			if (job.isTerminal()) {
				if (job.isFailed()) {
					// 예외로 바꾸지 않는다. 실패도 저장해야 할 사실이고, 태운 토큰은 이미 원장에 있다.
					log.warn("AI 리포트 생성 실패: jobId={}, problemId={}, reason={}",
							accepted.jobId(), request.problemId(), job.failureReason());
				}
				return job;
			}

			if (attempt % 10 == 0) {
				// 매 폴링마다 남기면 로그가 job 하나당 수십 줄이 된다. 오래 걸리는 것만 드러낸다.
				log.info("AI 리포트 생성 대기 중: jobId={}, {}회차, status={}",
						accepted.jobId(), attempt, job.status());
			}
		}

		/*
		 * 제한 시간을 넘겼다. AI 쪽 job은 아직 살아 있을 수 있으므로 "실패"라고 단정하지 않고
		 * 재시도 가능으로 올린다 — 같은 멱등키로 다시 부르면 AI가 처음 jobId를 그대로 돌려주고
		 * LLM을 다시 부르지 않는다(문제당 1건이라 중복이 곧 비용이다).
		 */
		throw new AiCallException(null, "TIMEOUT", true,
				"AI 리포트 생성이 제한 시간 안에 끝나지 않았습니다: jobId=" + accepted.jobId()
						+ ", problemId=" + request.problemId());
	}

	private void sleepBeforePoll() {
		if (pollInterval.isZero() || pollInterval.isNegative()) {
			return;
		}
		try {
			sleeper.sleep(pollInterval.toMillis());
		} catch (InterruptedException exception) {
			// 인터럽트 플래그를 되살린다. 삼키면 이 스레드를 멈추려는 쪽(배치 종료·셧다운)이
			// 멈출 방법을 잃는다.
			Thread.currentThread().interrupt();
			throw new AiCallException(null, "TIMEOUT", true, "AI 리포트 생성 대기가 중단되었습니다", exception);
		}
	}

	/** {@code Thread.sleep}을 테스트에서 갈아끼우기 위한 최소 추상. */
	@FunctionalInterface
	interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}
}
