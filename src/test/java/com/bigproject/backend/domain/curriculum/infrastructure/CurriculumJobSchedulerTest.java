package com.bigproject.backend.domain.curriculum.infrastructure;

import com.bigproject.backend.domain.curriculum.application.CurriculumServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 여태 {@code pollPendingCurriculumAnalyses}가 {@code CurriculumServiceImpl}에 {@code @Scheduled}로
 * 직접 걸려 있어 on/off 스위치 자체가 없었다 — 로컬 개발·{@code @SpringBootTest}·운영 전부에서
 * 무조건 돌았다(2026-08-20). 이 테스트는 분리된 스위치가 실제로 막는지 못 박는다.
 */
class CurriculumJobSchedulerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withBean(CurriculumServiceImpl.class, () -> mock(CurriculumServiceImpl.class))
			.withUserConfiguration(CurriculumJobScheduler.class);

	@Test
	void isAbsentByDefault() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(CurriculumJobScheduler.class));
	}

	@Test
	void isAbsentWhenExplicitlyDisabled() {
		contextRunner.withPropertyValues("ai.curriculum.scheduler.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(CurriculumJobScheduler.class));
	}

	@Test
	void isPresentWhenEnabled() {
		contextRunner.withPropertyValues("ai.curriculum.scheduler.enabled=true")
				.run(context -> assertThat(context).hasSingleBean(CurriculumJobScheduler.class));
	}
}
