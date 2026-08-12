package com.bigproject.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled}를 켠다.
 *
 * <p>{@link AsyncConfig}와 분리한 이유: 비동기 실행({@code @Async})과 주기 실행({@code @Scheduled})은
 * 스프링에서 서로 다른 기반이고 스레드 풀도 따로 관리된다. 한 클래스에 두 애너테이션을 얹으면
 * 나중에 한쪽 풀을 손볼 때 다른 쪽까지 영향을 검토해야 한다.
 *
 * <p>이 애너테이션만으로는 아무것도 돌지 않는다. 실제 작업인
 * {@code AnalysisJobScheduler}는 {@code ai.analysis.scheduler.enabled=true}일 때만 빈이 되므로,
 * 스케줄러를 끄고 켜는 스위치는 여기가 아니라 그 프로퍼티다.
 *
 * <p>기본 스케줄러 스레드 풀은 <b>1개</b>다. 지금 등록된 작업 둘(요청 안전망·상태 폴링)은 서로
 * 기다려도 문제가 없어 그대로 둔다. 작업이 늘거나 하나가 길어져 다른 하나를 굶기기 시작하면
 * {@code spring.task.scheduling.pool.size}를 올린다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
