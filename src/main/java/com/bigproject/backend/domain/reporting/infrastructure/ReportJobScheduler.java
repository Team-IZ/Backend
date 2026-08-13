package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.application.ReportBatchService;
import com.bigproject.backend.domain.reporting.application.ReportPublishService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link ReportBatchService}의 두 진입점을 주기 실행한다.
 *
 * <p>서비스에 {@code @Scheduled}를 직접 달지 않은 이유: 같은 메서드를 테스트도 부르고, 나중에
 * 운영자 수동 재생성 API가 붙으면 그쪽도 부른다. 서비스가 자기 실행 주기까지 알면 "언제 도는가"를
 * 바꾸려고 도메인 로직 파일을 열게 된다({@code AnalysisJobScheduler}와 같은 판단이다).
 *
 * <h2>기본값을 꺼 두는 이유</h2>
 *
 * <p>{@code ai.report.scheduler.enabled}가 <b>기본 false</b>다. 켜져 있으면 {@code @SpringBootTest}
 * 컨텍스트가 뜰 때마다 배치가 돌면서 실제 AI 서버로 요청이 나간다. 로컬 개발도 마찬가지다 —
 * 앱을 띄워 둔 것만으로 종료된 회차에 LLM 비용이 발생한다. 리포트는 교육생 1명당 문제 수만큼
 * 호출이라 코드 분석보다 낭비가 크다. 운영에서 명시적으로 켠다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.report.scheduler.enabled", havingValue = "true")
public class ReportJobScheduler {

	private final ReportBatchService reportBatchService;
	private final ReportPublishService reportPublishService;

	/**
	 * 회차 종료 시각이 지난 대상을 찾아 요청한다.
	 *
	 * <p>대상 조건이 시간 기반이라({@code now() >= COALESCE(report_publish_not_before_at,
	 * assessment_due_at)}) 이벤트 트리거가 없다 — 아무도 아무 행동을 하지 않아도 시각이 지나면
	 * 대상이 된다. 그래서 이 배치가 유일한 진입점이고, 5분은 "회차 종료 후 늦어도 5분 안에
	 * 요청이 나간다"는 뜻이다.
	 *
	 * <p>{@code fixedDelay}다({@code fixedRate}가 아니다). 이전 실행이 끝난 뒤부터 간격을 세므로,
	 * 대상이 많아 한 번이 오래 걸려도 실행이 겹쳐 쌓이지 않는다.
	 */
	@Scheduled(
			fixedDelayString = "${ai.report.scheduler.dispatch-delay:PT1M}",
			initialDelayString = "${ai.report.scheduler.initial-delay:PT1M}")
	public void dispatchDueProblems() {
		try {
			int dispatched = reportBatchService.dispatchDueProblems();
			if (dispatched > 0) {
				log.info("리포트 생성을 요청했다: problems={}", dispatched);
			}
		} catch (RuntimeException exception) {
			// 여기서 예외가 새면 스케줄러는 다음 실행을 계속 돌지만 스택트레이스가 묻힌다.
			log.error("리포트 생성 요청 배치 실패", exception);
		}
	}

	/**
	 * 회차 마감이 지났는데 아직 리포트가 없는 세션을 줍는다. <b>안전망</b>이다.
	 *
	 * <h2>왜 문제 단위 dispatch만으로 부족한가</h2>
	 *
	 * <p>{@link #dispatchDueProblems}는 <b>{@code problem_stage}가 종료 상태로 정리된 문제</b>만
	 * 집는다. 세션이 비정상적으로 끝나 단계가 {@code PREPARED}·{@code IN_PROGRESS}로 남으면
	 * 그 문제는 영영 안 잡히고, 학생은 리포트를 못 받는다.
	 *
	 * <p>이 배치는 세션 단위 조회({@code findDueSessions})를 쓰므로 <b>회차 마감 후</b>에
	 * 남은 것을 한 번 더 훑는다. 자주 돌 이유가 없어 주기를 길게 둔다.
	 */
	@Scheduled(
			fixedDelayString = "${ai.report.scheduler.sweep-delay:PT10M}",
			initialDelayString = "${ai.report.scheduler.initial-delay:PT1M}")
	public void sweepDueSessions() {
		try {
			int dispatched = reportBatchService.dispatchDueSessions();
			if (dispatched > 0) {
				log.info("마감 후 남은 세션을 요청했다: sessions={}", dispatched);
			}
		} catch (RuntimeException exception) {
			log.error("리포트 생성 안전망 배치 실패", exception);
		}
	}

	/**
	 * 발행 예정 시각 때문에 보류된 리포트를 발행한다.
	 *
	 * <p>확정은 끝났고 스냅샷·근거도 다 들어가 있는데 {@code published_at}만 비어 있는 것들이다.
	 * 문제 단위로 즉시 만들다 보니 <b>운영자가 정한 발행 시각보다 먼저 완성되는</b> 경우가 생겨
	 * 필요해졌다({@link com.bigproject.backend.domain.reporting.application.ReportPublishService}).
	 *
	 * <p>시각 단위 판정이라 촘촘할 이유가 없다. 5분이면 "예정 시각 후 최대 5분 안에 발행"이다.
	 */
	@Scheduled(
			fixedDelayString = "${ai.report.scheduler.publish-delay:PT5M}",
			initialDelayString = "${ai.report.scheduler.initial-delay:PT1M}")
	public void publishDueReports() {
		try {
			reportPublishService.publishDueReports();
		} catch (RuntimeException exception) {
			log.error("보류 리포트 발행 배치 실패", exception);
		}
	}

	/**
	 * 진행 중인 생성의 상태를 갱신하고, 끝난 실행을 확정한다.
	 *
	 * <p>AI 실측이 1건당 129초다. 폴링을 그보다 촘촘히 돌 이유는 없지만, 교육생 화면이
	 * `발행 전`에서 넘어가는 지연이 곧 이 간격이라 1분으로 잡았다.
	 */
	@Scheduled(
			fixedDelayString = "${ai.report.scheduler.poll-delay:PT1M}",
			initialDelayString = "${ai.report.scheduler.initial-delay:PT1M}")
	public void pollActiveItems() {
		try {
			int updated = reportBatchService.pollActiveItems();
			if (updated > 0) {
				log.info("리포트 생성 상태를 갱신했다: items={}", updated);
			}
		} catch (RuntimeException exception) {
			log.error("리포트 상태 폴링 실패", exception);
		}
	}
}
