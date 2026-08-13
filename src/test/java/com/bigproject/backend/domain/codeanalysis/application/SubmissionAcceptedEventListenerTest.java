package com.bigproject.backend.domain.codeanalysis.application;

import com.bigproject.backend.domain.submission.domain.SubmissionAcceptedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 리스너 자체는 이벤트를 그대로 넘기는 얇은 어댑터다. {@code AFTER_COMMIT}·{@code @Async} 배선은
 * 애노테이션으로 선언돼 있어 단위 테스트로 검증할 수 없고, 실제로 커밋 후에만·별도 스레드에서
 * 도는지는 통합 테스트 영역이다. 여기서는 위임 자체만 고정한다.
 */
class SubmissionAcceptedEventListenerTest {

	@Test
	void delegatesTheSubmissionIdToTheBatchService() {
		AnalysisBatchService batchService = mock(AnalysisBatchService.class);
		SubmissionAcceptedEventListener listener = new SubmissionAcceptedEventListener(batchService);
		UUID submissionId = UUID.randomUUID();

		listener.onSubmissionAccepted(new SubmissionAcceptedEvent(submissionId));

		verify(batchService).dispatchSubmission(submissionId);
	}
}
