package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.application.RiskOutcomeBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link RiskOutcomeBatchService}를 주기 실행한다.
 *
 * <p>서비스에 {@code @Scheduled}를 직접 달지 않은 이유는 {@code AnalysisJobScheduler}와 같다 — 같은
 * 메서드를 운영 도구와 테스트도 부른다. 실행 주기를 바꾸려고 판정 로직 파일을 열게 되면 안 된다.
 *
 * <h2>기본값을 꺼 두는 이유</h2>
 *
 * <p>{@code intervention.risk-outcome.scheduler.enabled}가 <b>기본 false</b>다. 이 배치는
 * {@code measurement_attempt.outcome_*}에 쓰고 면담 후보를 만든다 — {@code @SpringBootTest} 컨텍스트가
 * 뜰 때마다 돌면 테스트 DB의 판정이 임의로 채워지고, 판정은 {@code outcome_judged_at}으로 1회성이라
 * 되돌리려면 컬럼을 직접 지워야 한다. 운영에서 명시적으로 켠다.
 *
 * <h2>주기</h2>
 *
 * <p>판정은 회차 결과가 확정된 뒤 한 번만 필요하고, 매니저가 면담 목록을 여는 시점은 그보다 한참
 * 뒤다. 촘촘히 돌 이유가 없어 10분으로 잡았다. {@code fixedDelay}라 한 번이 오래 걸려도 실행이
 * 겹쳐 쌓이지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "intervention.risk-outcome.scheduler.enabled", havingValue = "true")
public class RiskOutcomeScheduler {

	private final RiskOutcomeBatchService riskOutcomeBatchService;

	@Scheduled(
			fixedDelayString = "${intervention.risk-outcome.scheduler.delay:PT10M}",
			initialDelayString = "${intervention.risk-outcome.scheduler.initial-delay:PT2M}")
	public void judgePendingRounds() {
		try {
			int processed = riskOutcomeBatchService.runPendingRounds();
			if (processed > 0) {
				// 0건이 정상이다. 판정할 회차가 생겼을 때만 남긴다.
				log.info("위험·우수 유형 판정 배치가 회차를 처리했다: rounds={}", processed);
			}
		} catch (RuntimeException exception) {
			// 여기서 예외가 새면 다음 실행은 계속 돌지만 스택트레이스가 묻힌다.
			log.error("위험·우수 유형 판정 배치 실행 실패", exception);
		}
	}
}
