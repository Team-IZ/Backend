package com.bigproject.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 제출 직후 코드 분석 트리거를 위한 비동기 실행기.
 *
 * <p>기본 executor({@code SimpleAsyncTaskExecutor})는 호출마다 새 스레드를 만들어 상한이 없다.
 * 마감 직전 재제출이 몰리면 그만큼 스레드가 늘어나 AI 서버로 나가는 동시 요청도 함께 폭증한다.
 * 풀을 좁혀 두면 초과분은 큐에서 기다리고, AI 서버가 느려도 이쪽 스레드가 무한정 늘지 않는다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

	/**
	 * 이름을 {@code analysisDispatchExecutor}로 고정한다. {@code @Async}에 이름을 명시하지 않으면
	 * Spring이 "가장 유력한" {@code Executor} 빈 하나를 고르는데, 이 프로젝트에 다른 비동기 작업이
	 * 생기면 그 선택이 모호해진다. 지금 미리 이름을 박아 두면 나중에 두 번째 executor를 추가해도
	 * 기존 호출부가 조용히 다른 풀로 옮겨가지 않는다.
	 */
	@Bean(name = "analysisDispatchExecutor")
	public Executor analysisDispatchExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(8);
		executor.setQueueCapacity(200);
		executor.setThreadNamePrefix("analysis-dispatch-");
		executor.initialize();
		return executor;
	}
}
