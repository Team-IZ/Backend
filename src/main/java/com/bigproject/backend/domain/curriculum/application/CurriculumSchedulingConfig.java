package com.bigproject.backend.domain.curriculum.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 교안 분석 결과 회수 스케줄러(CurriculumServiceImpl.pollPendingCurriculumAnalyses)를
 * 위해 스케줄링 기능을 켠다.
 *
 * <p>BackendApplication(메인 클래스, 공용 파일)을 건드리지 않기 위해 curriculum
 * 도메인 내부에 별도 설정 클래스로 분리했다. @EnableScheduling 자체는 애플리케이션
 * 전역에 적용되는 기능이지만(패키지 위치와 무관하게 컴포넌트 스캔됨), 이 파일을 통해
 * "왜 스케줄링이 켜져 있는지"를 curriculum 도메인 코드만 봐도 알 수 있게 한다.
 */
@Configuration
@EnableScheduling
public class CurriculumSchedulingConfig {
}