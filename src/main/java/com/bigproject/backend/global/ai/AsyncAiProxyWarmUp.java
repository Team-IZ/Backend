package com.bigproject.backend.global.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * AI 프록시를 <b>요청 스레드 밖에서</b> 깨운다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>원본(App Runner)은 평소 {@code PAUSED}이고, 깨우는 첫 호출이 80초쯤 걸린다. 그 시간이 학생
 * 요청 안에 들어가면 응답이 나가기 전에 브라우저·중간 구간이 먼저 끊는다(37차 R1 — 답변 제출이
 * 90초에 끊겼다). 그래서 <b>깨우는 일과 기다리는 일을 분리한다</b> — 답이 올 것이 예상되는 시점에
 * 미리 신호를 보내고, 정작 채점 요청은 이미 깨어 있는 서버로 나간다.
 *
 * <h2>왜 별도 빈인가</h2>
 *
 * <p>{@code @Async}는 프록시가 가로채는 것이라 {@link AiProxyWarmUp} 안에서 자기 메서드를 부르면
 * 그대로 동기 실행된다 — 잡히지 않고 요청 스레드에서 80초를 기다리게 된다. 같은 이유로
 * {@code AsyncTraineeInvitationMailer}도 분리돼 있다.
 *
 * <h2>실패를 삼킨다</h2>
 *
 * <p>이건 학생이 요청한 일이 아니라 뒤에서 미리 해 두는 준비다. 실패해도 알릴 곳이 없고, 알려도
 * 학생이 할 수 있는 일이 없다 — 깨우지 못했으면 다음 채점 호출이 평소처럼 느릴 뿐이다.
 * {@link AiProxyWarmUp#ensureAwake()}가 이미 예외를 삼키므로 여기서는 스레드가 죽지 않게만 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncAiProxyWarmUp {

	private final AiProxyWarmUp warmUp;

	/**
	 * "곧 AI를 부를 것 같다"는 신호를 받아 뒤에서 깨운다.
	 *
	 * <p>부르는 자리는 <b>채점 요청보다 충분히 앞선 사건</b>이어야 한다 — 세션 시작(문제를 읽는
	 * 몇 분이 남아 있다)과 첫 타이핑(답을 쓰는 수십 초가 남아 있다)이 그런 자리다. 최근에 깨운 적이
	 * 있으면 {@code ensureAwake}가 건너뛰므로 신호가 여러 번 와도 헬스체크가 늘지 않는다.
	 */
	@Async("aiWarmUpExecutor")
	public void wakeInBackground(String reason) {
		try {
			warmUp.ensureAwake();
		} catch (RuntimeException exception) {
			// ensureAwake는 예외를 삼키지만, 삼키지 못하는 것이 생겨도 @Async 기본 핸들러가
			// "어느 신호였는지"를 남기지 못한다. 사유를 붙여 여기서 끊는다.
			log.warn("AI 프록시 사전 웜업 실패: 사유={}, 원인={}", reason, exception.toString());
		}
	}
}
