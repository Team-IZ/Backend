package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 세션 접근 검사. 조회 서비스와 답변 저장소가 <b>같은 규칙</b>을 쓰게 하려고 따로 뺐다 —
 * 두 벌로 두면 한쪽만 고쳐 "조회는 막는데 쓰기는 통과하는" 구멍이 생긴다.
 *
 * <p>트랜잭션 경계를 갖지 않는다. 호출하는 쪽의 트랜잭션 안에서 실행돼야 검사와 쓰기가 같은 스냅샷을
 * 보기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class SessionGuard {

	private final JdbcSessionRepository repository;

	/** 남의 세션은 "없음"으로 보인다 — 존재 여부를 알려줄 이유가 없다. */
	public SessionHead owned(UUID userId, UUID sessionId) {
		return repository.findOwned(sessionId, userId)
				.orElseThrow(() -> new SessionException(SessionErrorCode.SESSION_NOT_ACCESSIBLE));
	}

	public SessionHead live(UUID userId, UUID sessionId) {
		SessionHead head = owned(userId, sessionId);
		if (head.isEnded()) {
			throw new SessionException(SessionErrorCode.SESSION_ALREADY_ENDED);
		}
		return head;
	}

	/**
	 * 쓰기를 받을 수 있는 세션인지 본다.
	 *
	 * <p>시간 상한을 넘겼으면 <b>여기서 닫는다.</b> 스케줄러를 두지 않는 이유는 세션이 30~70분짜리라
	 * 다음 요청이 반드시 오고, 그때 닫으면 "70분이 지났는데 답이 계속 들어오는" 상태가 남지 않기
	 * 때문이다. 아무도 다시 오지 않는 세션은 리포트 생성 배치가 걷어 간다.
	 */
	public SessionHead running(UUID userId, UUID sessionId) {
		SessionHead head = live(userId, sessionId);
		if (!"IN_PROGRESS".equals(head.status())) {
			throw new SessionException(SessionErrorCode.SESSION_NOT_STARTED);
		}
		if (head.timeLimitAt() != null && Instant.now().isAfter(head.timeLimitAt())) {
			repository.end(sessionId, "POLICY_TIME_LIMIT_EXCEEDED", null);
			throw new SessionException(SessionErrorCode.SESSION_TIMEOUT);
		}
		return head;
	}

	/** 커서가 가리키는 단계. 커서가 비었으면 세션 준비가 깨진 것이라 조용히 넘기지 않는다. */
	public SessionStage currentStage(SessionHead head) {
		if (head.currentProblemStageId() == null) {
			throw new SessionException(SessionErrorCode.STAGE_NOT_FOUND);
		}
		return repository.findStage(head.currentProblemStageId())
				.orElseThrow(() -> new SessionException(SessionErrorCode.STAGE_NOT_FOUND));
	}
}
