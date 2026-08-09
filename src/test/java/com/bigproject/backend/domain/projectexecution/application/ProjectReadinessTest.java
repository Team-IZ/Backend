package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectReadiness;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9차 Q1 — 준비 상태 판정을 못 박는다.
 *
 * <p>규칙이 목록 정렬({@code sort=READINESS})과 배지({@code readiness})에 <b>따로</b> 있으면
 * 목록의 순서와 배지가 서로 다른 말을 한다. 둘 다 {@link ProjectService.ProjectSummary#unreadyCount()}
 * 하나를 쓰므로, 이 테스트가 규칙 자체를 지킨다.
 */
class ProjectReadinessTest {

	@Test
	void callsAProjectReadyOnlyWhenCurriculumConceptsAndDueDateAreAllThere() {
		assertThat(summary(2, 3, LocalDate.of(2027, 2, 26)).readiness()).isEqualTo(ProjectReadiness.READY);
		assertThat(summary(2, 3, LocalDate.of(2027, 2, 26)).unreadyCount()).isZero();
	}

	@Test
	void callsAProjectPrepWhenAnyOfTheThreeIsMissing() {
		LocalDate due = LocalDate.of(2027, 2, 26);

		assertThat(summary(0, 3, due).readiness()).isEqualTo(ProjectReadiness.PREP);
		assertThat(summary(2, 0, due).readiness()).isEqualTo(ProjectReadiness.PREP);
		assertThat(summary(2, 3, null).readiness()).isEqualTo(ProjectReadiness.PREP);
	}

	/** 정렬은 이 개수의 내림차순이다 — 덜 준비된 회차가 앞에 온다("뭐부터 손대야 하나"). */
	@Test
	void countsHowManyPiecesAreMissingSoTheListCanSortByIt() {
		assertThat(summary(2, 3, LocalDate.of(2027, 2, 26)).unreadyCount()).isZero();
		assertThat(summary(0, 3, LocalDate.of(2027, 2, 26)).unreadyCount()).isEqualTo(1);
		assertThat(summary(0, 0, LocalDate.of(2027, 2, 26)).unreadyCount()).isEqualTo(2);
		assertThat(summary(0, 0, null).unreadyCount()).isEqualTo(3);
	}

	private ProjectService.ProjectSummary summary(int curriculumCount, int conceptCount, LocalDate endDate) {
		// endDate가 null인 프로젝트는 생성자가 막으므로(시작일 > 종료일 검사) 스텁으로 만든다.
		Project project = new StubProject(endDate);
		return new ProjectService.ProjectSummary(project, curriculumCount, conceptCount, 0);
	}

	/** {@code getEndDate()}만 쓰는 판정이라 나머지는 채우지 않는다. */
	private static final class StubProject extends Project {
		private final LocalDate endDate;

		private StubProject(LocalDate endDate) {
			this.endDate = endDate;
		}

		@Override
		public LocalDate getEndDate() {
			return endDate;
		}

		@Override
		public UUID getProjectId() {
			return UUID.randomUUID();
		}
	}
}
