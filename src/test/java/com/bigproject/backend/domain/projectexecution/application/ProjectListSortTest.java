package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectLifecycleStatus;
import com.bigproject.backend.domain.projectexecution.domain.ProjectListSort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 18차 R4 — 정렬 세 가지가 서로 다른 질문에 답하는지 못 박는다.
 *
 * <p>종전에는 셋 다 <b>날짜 오름차순</b>으로만 동작해 결과가 거의 같았고, 셋 다 5개월 전에
 * 끝난 회차를 맨 위에 놓았다. 목록의 목적이 "손댈 것이 남은 회차 찾기"인데 가장 위 다섯 줄이
 * 전부 "할 일 없음"이었다.
 *
 * <p>원인은 <b>오늘을 안 봤다</b>는 것이다. 날짜만 비교하면 지난 것과 남은 것이 한 줄에 섞이고
 * 지난 것이 항상 더 작으므로 먼저 온다. 이 테스트가 그 회귀를 막는다.
 */
class ProjectListSortTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);

	@Test
	@DisplayName("세 정렬 모두 끝난 회차를 마지막 그룹으로 보낸다")
	void pushesClosedRoundsToTheBackInEverySort() {
		for (ProjectListSort sort : ProjectListSort.values()) {
			List<String> sorted = sort(sort,
					closed("1차", "2026-03-09", "2026-03-20"),
					closed("5차", "2026-07-20", "2026-07-31"),
					running("6차", "2026-08-03", "2026-08-21"),
					planned("투투투", "2026-08-11", "2026-08-18", 1, 3));

			assertThat(sorted)
					.as("%s — 끝난 회차가 뒤에 있어야 한다", sort)
					.containsSubsequence("6차", "1차")
					.containsSubsequence("투투투", "1차");
			assertThat(sorted.subList(2, 4))
					.as("%s — 마지막 두 자리는 CLOSED 차지다", sort)
					.containsExactlyInAnyOrder("1차", "5차");
		}
	}

	@Test
	@DisplayName("준비 필요 순 — 빈 것이 있는 예정 회차가 가장 앞이다")
	void readinessPutsUnpreparedPlannedRoundsFirst() {
		List<String> sorted = sort(ProjectListSort.READINESS,
				running("진행중", "2026-08-03", "2026-08-21"),
				planned("준비됨", "2026-08-11", "2026-08-18", 1, 3),
				planned("교안없음", "2026-08-13", "2026-08-20", 0, 3),
				closed("종료", "2026-03-09", "2026-03-20"));

		assertThat(sorted).containsExactly("교안없음", "준비됨", "진행중", "종료");
	}

	@Test
	@DisplayName("마감 임박 순 — 안 지난 마감이 가까운 순으로 앞, 지난 마감은 최근 순으로 뒤")
	void dueSoonSeparatesUpcomingDeadlinesFromPastOnes() {
		List<String> sorted = sort(ProjectListSort.DUE_SOON,
				planned("멀다", "2026-08-20", "2026-09-30", 1, 3),
				planned("가깝다", "2026-08-11", "2026-08-18", 1, 3),
				running("지났다-최근", "2026-07-01", "2026-08-01"),
				running("지났다-오래", "2026-03-01", "2026-04-01"));

		assertThat(sorted).containsExactly("가깝다", "멀다", "지났다-최근", "지났다-오래");
	}

	@Test
	@DisplayName("시작 임박 순 — 아직 시작 안 한 것이 가까운 순으로 앞이다")
	void startDateFavoursRoundsThatHaveNotOpenedYet() {
		List<String> sorted = sort(ProjectListSort.START_DATE,
				running("이미시작", "2026-08-03", "2026-08-21"),
				planned("곧시작", "2026-08-13", "2026-08-20", 1, 3),
				planned("나중시작", "2026-09-01", "2026-09-20", 1, 3));

		assertThat(sorted).containsExactly("곧시작", "나중시작", "이미시작");
	}

	/**
	 * 오늘 시작·오늘 마감은 <b>지나지 않은 것</b>이다.
	 *
	 * <p>경계를 {@code isBefore}로 잡은 이유가 이것이다 — 오늘 마감인 회차를 "지난 것"으로
	 * 밀면 정작 오늘 손봐야 할 회차가 목록 아래로 사라진다.
	 */
	@Test
	@DisplayName("오늘 마감은 지난 것이 아니다")
	void treatsTodayAsStillUpcoming() {
		List<String> sorted = sort(ProjectListSort.DUE_SOON,
				running("어제마감", "2026-07-01", "2026-08-11"),
				running("오늘마감", "2026-07-01", "2026-08-12"));

		assertThat(sorted).containsExactly("오늘마감", "어제마감");
	}

	/** 날짜가 없으면 지난 것으로 취급하지 않는다 — 미정인 회차를 지난 일 뒤로 밀지 않는다. */
	@Test
	@DisplayName("마감일이 없는 회차는 지난 일보다 앞에 둔다")
	void keepsRoundsWithoutDatesAheadOfPastOnes() {
		List<String> sorted = sort(ProjectListSort.DUE_SOON,
				running("지났다", "2026-03-01", "2026-04-01"),
				running("미정", null, null));

		assertThat(sorted).containsExactly("미정", "지났다");
	}

	private List<String> sort(ProjectListSort sort, ProjectService.ProjectSummary... summaries) {
		List<ProjectService.ProjectSummary> list = new ArrayList<>(List.of(summaries));
		list.sort(ProjectServiceImpl.comparatorFor(sort, TODAY));
		return list.stream().map(summary -> summary.project().getName()).toList();
	}

	private static ProjectService.ProjectSummary planned(
			String name, String start, String end, int curriculumCount, int conceptCount) {
		return new ProjectService.ProjectSummary(
				new StubProject(name, ProjectLifecycleStatus.PLANNED, start, end),
				curriculumCount, conceptCount, 0);
	}

	private static ProjectService.ProjectSummary running(String name, String start, String end) {
		return new ProjectService.ProjectSummary(
				new StubProject(name, ProjectLifecycleStatus.RUNNING, start, end), 1, 3, 0);
	}

	private static ProjectService.ProjectSummary closed(String name, String start, String end) {
		return new ProjectService.ProjectSummary(
				new StubProject(name, ProjectLifecycleStatus.CLOSED, start, end), 1, 3, 0);
	}

	private static final class StubProject extends Project {

		private final String name;
		private final ProjectLifecycleStatus status;
		private final LocalDate startDate;
		private final LocalDate endDate;

		private StubProject(String name, ProjectLifecycleStatus status, String start, String end) {
			this.name = name;
			this.status = status;
			this.startDate = start == null ? null : LocalDate.parse(start);
			this.endDate = end == null ? null : LocalDate.parse(end);
		}

		@Override
		public UUID getProjectId() {
			return UUID.randomUUID();
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public ProjectLifecycleStatus getLifecycleStatus() {
			return status;
		}

		@Override
		public LocalDate getStartDate() {
			return startDate;
		}

		@Override
		public LocalDate getEndDate() {
			return endDate;
		}

		@Override
		public Integer getSequenceNo() {
			return null;
		}
	}
}
