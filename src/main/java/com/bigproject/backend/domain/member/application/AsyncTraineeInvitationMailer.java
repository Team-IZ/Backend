package com.bigproject.backend.domain.member.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 확보된 교육생 초대의 메일 발송을 <b>요청 스레드 밖으로</b> 내보낸다(개선 D).
 *
 * <p>발송을 요청 안에서 끝내면 900명 기준 약 3분이 걸려 게이트웨이의 "응답 첫 바이트까지" 상한을 넘는다.
 * 화면에는 502가 뜨는데 뒤에서는 등록이 계속되는 상태가 그것이다. 자리 확보까지만 동기로 하고 여기서
 * 발송을 이어받으면 응답은 1초 안에 나가고, 화면은 진행률을 폴링해서 따라온다.
 *
 * <p><b>왜 별도 빈인가.</b> {@code @Async}는 프록시가 가로채는 것이라 같은 클래스 안에서 부르면
 * 그냥 동기 호출이 된다. {@link TransactionalInvitationDispatcher}에 애너테이션만 달아서는 동작하지 않는다.
 *
 * <p><b>토큰 원문을 메모리로 들고 넘어간다.</b> {@code one_time_token.token_hash}는 SHA-256이라
 * DB만 보고는 초대 링크를 만들 수 없다. 그래서 자리를 확보한 인스턴스가 그대로 발송까지 맡는다.
 * 그 인스턴스가 배포·크래시로 사라지면 원문도 함께 사라지므로, 남은 행은
 * {@link TraineeInvitationOutbox}가 토큰을 새로 발급해 이어받는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncTraineeInvitationMailer {
	private final TransactionalInvitationDispatcher invitationDispatcher;

	/**
	 * @param batchRequestId 로그에서 어느 일괄 등록의 발송인지 짚기 위한 값이다. 잡 상태는 저장하지
	 *                       않으므로(원장 집계로 유도한다) 이 값이 쓰이는 곳은 로그뿐이다
	 */
	@Async("traineeInvitationMailExecutor")
	public void sendTraineeInvitations(
			String batchRequestId,
			List<InvitationMailSender.TraineeInvitationMail> mails
	) {
		try {
			invitationDispatcher.sendTraineeInvitations(mails);
		} catch (RuntimeException exception) {
			/*
			 * 여기까지 오는 예외는 발송기가 아니라 그 바깥이 터진 것이다(발송 실패는 이미 안에서
			 * 행별로 갈라 원장에 기록한다). 잡아 두지 않으면 @Async 기본 핸들러가 "어느 요청이었는지"
			 * 없이 스택트레이스만 남긴다. 남은 행은 PENDING이므로 안전망이 이어받는다.
			 */
			log.error("교육생 초대 메일 비동기 발송이 실패했다: batchRequestId={}, count={}",
					batchRequestId, mails.size(), exception);
		}
	}
}
