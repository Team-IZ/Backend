package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.application.TraineeInvitationOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link TraineeInvitationOutbox}를 주기 실행한다.
 *
 * <p>서비스에 {@code @Scheduled}를 직접 달지 않은 이유는 {@code AnalysisJobScheduler}와 같다 —
 * 같은 메서드를 테스트도 부르는데, 서비스가 자기 실행 주기까지 알면 "언제 도는가"를 바꾸려고
 * 도메인 로직 파일을 열게 된다.
 *
 * <h2>🔴 기본이 꺼져 있다 — 운영에서 켜야 한다</h2>
 *
 * <p>{@code invitation.mail.outbox.enabled}가 <b>기본 false</b>다. 켜져 있으면 컨텍스트가 뜨는 것만으로
 * 초대 메일이 실제로 나갈 수 있어(로컬·테스트 포함) 기본값으로 두지 않았다. 프로젝트의 다른 스케줄러도
 * 같은 이유로 꺼져 있다.
 *
 * <p><b>꺼 두면 안전망이 없다는 뜻이다.</b> 정상 경로(자리를 확보한 인스턴스가 곧바로 발송)는 그대로
 * 동작하지만, 배포·크래시로 발송이 끊긴 초대는 {@code PENDING}에 남아 아무도 이어받지 않는다.
 * 그 행들은 명단 화면의 [초대 재발송]으로만 복구된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "invitation.mail.outbox.enabled", havingValue = "true")
public class TraineeInvitationOutboxScheduler {

	private final TraineeInvitationOutbox traineeInvitationOutbox;

	/**
	 * {@code fixedDelay}다({@code fixedRate}가 아니다). 발송이 느려 한 번이 오래 걸려도 실행이 겹쳐
	 * 쌓이지 않는다 — 겹치면 같은 인스턴스가 자기 클레임을 다시 집는 일이 생긴다.
	 */
	@Scheduled(
			fixedDelayString = "${invitation.mail.outbox.delay:PT2M}",
			initialDelayString = "${invitation.mail.outbox.initial-delay:PT1M}")
	public void dispatchStalledInvitations() {
		try {
			int dispatched = traineeInvitationOutbox.dispatchStalledInvitations();
			if (dispatched > 0) {
				// 0건이 정상이라 0을 남기면 소음만 는다. 걸렸다는 것 자체가 점검 신호다.
				log.info("안전망이 멈춰 있던 교육생 초대를 발송했다. 비동기 발송 경로 점검이 필요할 수 있다: dispatched={}",
						dispatched);
			}
		} catch (RuntimeException exception) {
			// 여기서 예외가 새면 다음 실행은 계속 돌지만 스택트레이스가 묻힌다.
			log.error("교육생 초대 발송 안전망 실행 실패", exception);
		}
	}
}
