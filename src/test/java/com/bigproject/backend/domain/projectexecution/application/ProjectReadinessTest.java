package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectReadiness;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9차 Q1 — 준비 상태 판정을 못 박는다.
 *
 * <p>규칙이 목록 정렬({@code sort=READINESS})과 배지({@code readiness})에 <b>따로</b> 있으면
 * 목록의 순서와 배지가 서로 다른 말을 한다. 둘 다 {@link ProjectService.ProjectSummary#unreadyCount()}
 * 하나를 쓰므로, 이 테스트가 규칙 자체를 지킨다.
 *
 * <p>10차 R4 — 판정 항목이 <b>셋에서 둘로</b> 줄었다. 예전에는 {@code endDate == null}도 한 몫으로
 * 셌는데 {@code project.end_date}가 DB에서 NOT NULL이고 생성·수정 요청도 둘 다 필수라
 * 절대 성립하지 않는 조건이었다. 그 항목을 검사하던 케이스는 <b>있을 수 없는 상태</b>를
 * 고정하고 있었으므로 함께 걷어냈다. 실제 판정 결과는 달라지지 않는다.
 */
class ProjectReadinessTest {

	@Test
	void callsAProjectReadyOnlyWhenCurriculumAndConceptsAreBothThere() {
		assertThat(summary(2, 3).readiness()).isEqualTo(ProjectReadiness.READY);
		assertThat(summary(2, 3).unreadyCount()).isZero();
	}

	@Test
	void callsAProjectPrepWhenEitherIsMissing() {
		assertThat(summary(0, 3).readiness()).isEqualTo(ProjectReadiness.PREP);
		assertThat(summary(2, 0).readiness()).isEqualTo(ProjectReadiness.PREP);
		assertThat(summary(0, 0).readiness()).isEqualTo(ProjectReadiness.PREP);
	}

	/** 정렬은 이 개수의 내림차순이다 — 덜 준비된 회차가 앞에 온다("뭐부터 손대야 하나"). */
	@Test
	void countsHowManyPiecesAreMissingSoTheListCanSortByIt() {
		assertThat(summary(2, 3).unreadyCount()).isZero();
		assertThat(summary(0, 3).unreadyCount()).isEqualTo(1);
		assertThat(summary(2, 0).unreadyCount()).isEqualTo(1);
		assertThat(summary(0, 0).unreadyCount()).isEqualTo(2);
	}

	private ProjectService.ProjectSummary summary(int curriculumCount, int conceptCount) {
		return new ProjectService.ProjectSummary(new StubProject(), curriculumCount, conceptCount, 0);
	}

	/** 판정이 개수 두 개만 보므로 프로젝트 자체에서 읽는 값은 없다. */
	private static final class StubProject extends Project {

		@Override
		public UUID getProjectId() {
			return UUID.randomUUID();
		}
	}
}
