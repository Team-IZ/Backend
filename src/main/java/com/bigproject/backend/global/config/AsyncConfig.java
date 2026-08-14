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

	/**
	 * CSV 교육생 대량 등록의 초대 메일 발송 풀.
	 *
	 * <p><b>풀을 좁게 잡는다.</b> 잡 하나가 SMTP 연결을 붙들고 수백 통을 보내는 작업이라, 넓히면
	 * 제공자의 동시 연결 상한에 먼저 걸린다. 상한을 확인하기 전까지는 잡 하나가 순서대로 도는 편이
	 * 안전하다 — 병렬화는 {@code INVITATION_MAIL_HOST}의 동시 연결 상한을 확인한 뒤 별도로 판단한다.
	 *
	 * <p>큐가 차면 {@code TaskRejectedException}이 <b>요청 스레드로</b> 올라온다. 호출부는 그것을
	 * 잡아 등록 자체는 성공으로 응답한다 — 자리는 이미 커밋됐고, 발송되지 않은 행은 원장에 PENDING으로
	 * 남아 안전망 스케줄러가 이어받기 때문이다.
	 */
	@Bean(name = "traineeInvitationMailExecutor")
	public Executor traineeInvitationMailExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("trainee-invite-mail-");
		executor.initialize();
		return executor;
	}
}
