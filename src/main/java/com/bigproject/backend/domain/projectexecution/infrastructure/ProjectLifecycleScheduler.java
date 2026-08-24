package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 시작일이 된 프로젝트를 진행 중으로 올리고 그 회차를 연다.
 *
 * <p>서비스에 {@code @Scheduled}를 직접 달지 않은 이유는 {@code AnalysisJobScheduler}와 같다 —
 * 같은 메서드를 테스트도 부르고, 나중에 운영 트리거가 생길 수도 있어 실행 주기는 바깥에 둔다.
 *
 * <h2>여태 이 전환을 사람이 했다</h2>
 *
 * <p>{@code Project.start()}는 호출부가 없었고 회차의 {@code PLANNED → OPEN}은 코드 자체가 없어
 * 수동 SQL 로 옮기고 있었다({@code docs/미프3차_4차_회차상태_전환.sql}). 회차가 열리지 않으면
 * 교육생 홈에서 그 회차가 「예정」에만 머물고 「지금 할 일」로 올라오지 못한다 —
 * {@code AssessmentRoundQueryService.pickCurrent}가 {@code isOpen()}만 보기 때문이다.
 *
 * <h2>하루에 한 번이면 충분하다</h2>
 *
 * <p>판정 기준이 {@code start_date}(날짜)라 더 촘촘히 돌아도 같은 답이 나온다. 다만 편성이
 * 시작일 이후에 끝나는 경우가 있어 — 시작일 아침에 팀을 확정하는 운영이 실제로 있다 —
 * 하루 한 번은 "시작일이 지났는데 아직 편성 중"인 프로젝트를 계속 다시 본다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectLifecycleScheduler {

	private final ProjectService projectService;

	/**
	 * {@code fixedDelay}다. 한 번이 오래 걸려도 실행이 겹쳐 쌓이지 않는다.
	 *
	 * <p>0건이 정상이다 — 시작할 것이 없는 날이 대부분이라 0을 로그로 남기면 소음만 는다.
	 */
	@Scheduled(
			fixedDelayString = "${project.lifecycle.scheduler.delay:PT1H}",
			initialDelayString = "${project.lifecycle.scheduler.initial-delay:PT2M}")
	public void startDueProjects() {
		try {
			int started = projectService.startDueProjects();
			if (started > 0) {
				log.info("시작일이 된 프로젝트를 진행 중으로 올리고 회차를 열었다: projects={}", started);
			}
		} catch (RuntimeException exception) {
			// 여기서 예외가 새면 다음 실행은 계속 돌지만 스택트레이스가 묻힌다.
			log.error("프로젝트 시작 전환 실패", exception);
		}
	}
}
