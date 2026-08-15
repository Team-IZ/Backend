package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionStage;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
	private final SessionExpirer expirer;

	/** 문제별 정책 시간 상한(분). 정의서 §2의 문제당 상한 20분이 기본값이다. */
	@Value("${session.problem-time-limit-minutes:20}")
	private int problemTimeLimitMinutes;

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
	 * <p>시간 상한을 넘겼으면 <b>여기서 닫는다.</b> 스케줄러를 두지 않는 이유는 세션이 20~60분짜리라
	 * 다음 요청이 반드시 오고, 그때 닫으면 "60분이 지났는데 답이 계속 들어오는" 상태가 남지 않기
	 * 때문이다. 아무도 다시 오지 않는 세션은 리포트 생성 배치가 걷어 간다.
	 *
	 * <p>문제별 상한도 같은 방식으로 다음 요청에서 잡는다. 다만 세션을 닫지 않고 <b>그 문제만 접어</b>
	 * 다음 문제로 넘긴다 — 힌트를 다 쓰고도 미달일 때와 같은 전이다({@link JdbcSessionRepository
	 * #expireCurrentProblem}). 세션 상한 검사를 먼저 하는 이유는 세션이 이미 끝났다면 문제 하나를
	 * 더 접을 이유가 없기 때문이다.
	 *
	 * <p><b>닫기는 {@link SessionExpirer}가 독립 트랜잭션으로 한다.</b> 여기서 곧바로 저장소를 부르면
	 * 뒤이어 던지는 예외에 그 쓰기가 함께 롤백된다 — 그래서 세션이 열린 채 남고 커서도 그대로였다.
	 */
	public SessionHead running(UUID userId, UUID sessionId) {
		SessionHead head = live(userId, sessionId);
		if (!"IN_PROGRESS".equals(head.status())) {
			throw new SessionException(SessionErrorCode.SESSION_NOT_STARTED);
		}
		if (isSessionTimedOut(head)) {
			expirer.closeTimedOutSession(sessionId);
			throw new SessionException(SessionErrorCode.SESSION_TIMEOUT);
		}
		if (isCurrentProblemTimedOut(head)) {
			SessionStage stage = currentStage(head);
			expirer.expireTimedOutProblem(sessionId, head.currentProblemId(), stage.problemNo(), head.isReview());
			throw new SessionException(SessionErrorCode.PROBLEM_TIME_LIMIT_EXCEEDED);
		}
		return head;
	}

	/**
	 * 읽기 경로가 부르는 지연 정리. <b>쓰기 요청이 오기 전에도</b> 상한을 반영하기 위해 있다.
	 *
	 * <p>{@link #running}만으로는 부족했다. 상한을 넘긴 뒤 학생이 아무것도 제출하지 않고 새로고침만 하면
	 * {@code GET /current}가 끝났어야 할 세션을 계속 "진행 중"으로 돌려준다 — 화면은 남은 시간이 음수인
	 * 세션을 그리고, 학생은 이미 닫힌 시험을 계속 붙들고 있게 된다.
	 *
	 * <p>예외를 던지지 않는다. 조회는 "무엇이 남았는가"에 답하는 자리이지 실패를 알리는 자리가 아니다 —
	 * 호출자는 {@code true}를 받으면 커서가 바뀌었으므로 다시 읽으면 된다.
	 *
	 * @return 무언가를 닫았으면 {@code true}. 호출자는 세션 머리를 다시 읽어야 한다
	 */
	public boolean expireIfTimedOut(SessionHead head) {
		if (head == null || head.isEnded()) {
			return false;
		}
		if (isSessionTimedOut(head)) {
			expirer.closeTimedOutSession(head.sessionId());
			return true;
		}
		if (isCurrentProblemTimedOut(head)) {
			// 커서가 비어 있으면 접을 문제를 특정할 수 없다. 조회 경로라 STAGE_NOT_FOUND로 끊지 않고
			// 그대로 둔다 — 세션 준비가 깨진 것은 쓰기 경로에서 드러난다.
			if (head.currentProblemStageId() == null) {
				return false;
			}
			SessionStage stage = currentStage(head);
			expirer.expireTimedOutProblem(head.sessionId(), head.currentProblemId(), stage.problemNo(),
					head.isReview());
			return true;
		}
		return false;
	}

	/** 세션 전체 상한(기본 60분)을 넘겼는가. 상한이 없으면 넘길 수 없다. */
	private boolean isSessionTimedOut(SessionHead head) {
		return head.timeLimitAt() != null && Instant.now().isAfter(head.timeLimitAt());
	}

	/**
	 * 지금 문제의 상한(기본 20분)을 넘겼는가.
	 *
	 * <p>기산점은 지금 문제 L1의 {@code question_presented_at}이다. 시작 전(READY)이면 그 값이 없어
	 * 항상 {@code false}이고, 커서가 다음 문제로 옮겨질 때 새로 찍히므로 문제마다 20분을 새로 센다.
	 */
	private boolean isCurrentProblemTimedOut(SessionHead head) {
		return head.currentProblemStartedAt() != null
				&& Instant.now().isAfter(
						head.currentProblemStartedAt().plus(Duration.ofMinutes(problemTimeLimitMinutes)));
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
