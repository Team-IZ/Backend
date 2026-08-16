package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 시간 상한을 넘긴 세션·문제를 <b>바깥 트랜잭션과 무관하게</b> 닫는다.
 *
 * <h2>왜 별도 빈이고 왜 REQUIRES_NEW인가</h2>
 *
 * <p>{@link SessionGuard}는 상한을 넘긴 것을 발견하면 <b>닫은 뒤 예외를 던진다.</b> 그런데 그 예외는
 * {@code RuntimeException}이라 호출자의 {@code @Transactional}이 롤백된다 — <b>방금 한 닫기까지 함께
 * 되돌아간다.</b> 실제로 그래서 다음 두 가지가 났다(2026-08-15 실측).
 *
 * <ul>
 *   <li>세션 상한 초과: {@code POST /answers}가 409 {@code SESSION_TIMEOUT}을 주는데
 *       {@code assessment_session.status}는 {@code IN_PROGRESS}로 남아, {@code GET /current}가
 *       끝난 세션을 계속 "진행 중"으로 돌려줬다.</li>
 *   <li>문제 상한 초과: 커서가 다음 문제로 옮겨지지 않아 <b>그 문제에 영원히 갇혔다.</b> 요청할 때마다
 *       409 {@code PROBLEM_TIME_LIMIT_EXCEEDED}만 돌아오고 남은 문제를 풀 방법이 없었다.</li>
 * </ul>
 *
 * <p>그래서 닫기를 <b>독립 트랜잭션</b>에서 실행한다. 바깥이 롤백돼도 이쪽은 이미 커밋돼 있다.
 * {@code REQUIRES_NEW}는 프록시를 거쳐야 적용되므로 {@link SessionGuard} 안의 private 메서드로 둘 수
 * 없고 별도 빈이어야 한다.
 *
 * <p>교착 걱정은 없다. 바깥 트랜잭션이 이 시점까지 {@code assessment_session}에 건 것은 잠금 없는
 * SELECT뿐이라({@code findOwned}·{@code findCurrent}) 새 트랜잭션의 UPDATE와 다투지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SessionExpirer {

	private final JdbcSessionRepository repository;

	/**
	 * 정책 시간 상한을 넘긴 세션을 닫는다. 답한 데까지는 남고 세션은 {@code INTERRUPTED}가 된다.
	 *
	 * <p>{@code end}의 UPDATE가 {@code status IN ('READY','IN_PROGRESS','PAUSED')}로 좁혀져 있어
	 * 이미 닫힌 세션에 두 번 불려도 아무것도 바꾸지 않는다 — 동시 요청이 함께 상한을 발견해도 안전하다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void closeTimedOutSession(UUID sessionId) {
		repository.end(sessionId, "POLICY_TIME_LIMIT_EXCEEDED", null);
	}

	/**
	 * 문제별 상한을 넘긴 현재 문제를 접고 <b>다음 문제로 커서를 옮긴다.</b> 마지막 문제였으면 세션이 끝난다.
	 *
	 * <p>세션은 닫지 않는다 — 힌트를 다 쓰고도 미달일 때와 같은 전이다. 남은 축은 {@code NOT_REACHED}로
	 * 닫히고 다음 문제의 L1에 새 시작 시각이 찍히므로, 그 문제의 20분은 거기서 다시 센다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void expireTimedOutProblem(UUID sessionId, UUID problemId, int problemNo, boolean isReview) {
		repository.expireCurrentProblem(sessionId, problemId, problemNo, isReview);
	}
}
