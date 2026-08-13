package com.bigproject.backend.domain.codeanalysis.application;

import com.bigproject.backend.domain.submission.domain.SubmissionAcceptedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 제출 접수 이벤트를 받아 분석을 백그라운드로 트리거한다.
 *
 * <p>{@code AFTER_COMMIT}에서만 듣는다. 제출 트랜잭션이 롤백되면(예: 동시 재제출 경합) 애초에 없던
 * 제출을 분석하려 들면 안 되기 때문이다.
 *
 * <p>{@code @Async}로 별도 스레드에서 돈다. 커밋 이후라도 같은 스레드에서 동기로 처리하면 AI 서버
 * HTTP 호출이 끝날 때까지 교육생의 제출 요청·응답 스레드가 묶인다 — 응답은 이미 반환됐어야 할
 * 시점인데 서블릿 스레드가 붙들려 있으면 다른 요청을 못 받는다.
 */
@Component
@RequiredArgsConstructor
public class SubmissionAcceptedEventListener {

	private final AnalysisBatchService analysisBatchService;

	@Async("analysisDispatchExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onSubmissionAccepted(SubmissionAcceptedEvent event) {
		analysisBatchService.dispatchSubmission(event.submissionId());
	}
}
