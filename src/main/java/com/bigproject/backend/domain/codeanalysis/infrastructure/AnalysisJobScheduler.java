package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link AnalysisBatchService}의 두 진입점을 주기 실행한다.
 *
 * <p>서비스에 {@code @Scheduled}를 직접 달지 않은 이유: 같은 메서드를 제출 이벤트도 부르고 테스트도
 * 부른다. 서비스가 자기 실행 주기까지 알면 "언제 도는가"를 바꾸려고 도메인 로직 파일을 열게 된다.
 *
 * <h2>기본값을 꺼 두는 이유</h2>
 *
 * <p>{@code ai.analysis.scheduler.enabled}가 <b>기본 false</b>다. 켜져 있으면 {@code @SpringBootTest}
 * 컨텍스트가 뜰 때마다 배치가 돌면서 실제 AI 서버로 요청이 나간다. 로컬 개발에서도 마찬가지다 —
 * 앱을 띄워 둔 것만으로 마감 지난 제출에 LLM 비용이 발생한다. 운영에서 명시적으로 켠다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.analysis.scheduler.enabled", havingValue = "true")
public class AnalysisJobScheduler {

	private final AnalysisBatchService analysisBatchService;

	/**
	 * 안전망 겸 재시도. 이벤트 유실로 트리거를 놓친 제출과, 일시적 실패로 끝난 제출을 다시 집는다.
	 *
	 * <p>정상 경로에서는 제출 즉시 {@code SubmissionAcceptedEvent}가 처리하므로 0건이 정상이다.
	 * 꾸준히 걸린다면 이벤트 경로가 고장 났거나 AI 서버가 불안정한 것이다.
	 *
	 * <p>{@code fixedDelay}다({@code fixedRate}가 아니다). 이전 실행이 끝난 뒤부터 간격을 세므로,
	 * AI 응답이 느려 한 번이 오래 걸려도 실행이 겹쳐 쌓이지 않는다.
	 */
	@Scheduled(
			fixedDelayString = "${ai.analysis.scheduler.dispatch-delay:PT5M}",
			initialDelayString = "${ai.analysis.scheduler.initial-delay:PT1M}")
	public void retryPendingSubmissions() {
		try {
			int dispatched = analysisBatchService.retryPendingSubmissions();
			if (dispatched > 0) {
				// 0건이 정상이라 0을 로그로 남기면 소음만 는다. 걸렸다는 것 자체가 점검 신호다.
				log.info("안전망이 분석을 재요청했다. 이벤트 경로나 AI 서버 상태 점검이 필요할 수 있다: dispatched={}",
						dispatched);
			}
		} catch (RuntimeException exception) {
			// 여기서 예외가 새면 스케줄러가 다음 실행을 계속 돌긴 하지만 스택트레이스가 묻힌다.
			log.error("분석 요청 안전망 실행 실패", exception);
		}
	}

	/**
	 * 진행 중인 실행의 상태를 갱신한다.
	 *
	 * <p>분석 1건이 5분 안팎 걸린다(실측 06:33→06:39). 폴링을 그보다 촘촘히 돌 이유는 없지만,
	 * 교육생 화면이 "분석 중"에서 넘어가는 지연이 곧 이 간격이라 1분으로 잡았다.
	 */
	@Scheduled(
			fixedDelayString = "${ai.analysis.scheduler.poll-delay:PT1M}",
			initialDelayString = "${ai.analysis.scheduler.initial-delay:PT1M}")
	public void pollActiveJobs() {
		try {
			int updated = analysisBatchService.pollActiveJobs();
			if (updated > 0) {
				log.info("분석 상태를 갱신했다: updated={}", updated);
			}
		} catch (RuntimeException exception) {
			log.error("분석 상태 폴링 실패", exception);
		}
	}
}
