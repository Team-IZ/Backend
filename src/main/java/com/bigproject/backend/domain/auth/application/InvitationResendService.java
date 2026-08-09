package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.presentation.dto.InvitationResendResponse;
import com.bigproject.backend.domain.auth.infrastructure.PasswordResetAuditLogger;
import com.bigproject.backend.domain.member.application.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvitationResendService {
	private static final String ACCEPTED_MESSAGE = "입력하신 주소로 초대를 보낸 기록이 있으면 초대 메일이 다시 도착합니다.";

	private final InvitationResendDispatcher dispatcher;
	private final PasswordResetAuditLogger auditLogger;

	public InvitationResendResponse request(String email, String requestId) {
		try {
			dispatcher.dispatch(EmailNormalizer.normalize(email), requestId);
		} catch (RuntimeException exception) {
			log.warn("초대 메일 재발송을 완료하지 못했습니다: requestId={}, exceptionType={}",
					requestId, exception.getClass().getSimpleName());
			auditLogger.recordFailure(null, requestId, "INVITE_MAIL_FAILED");
		}
		return new InvitationResendResponse(ACCEPTED_MESSAGE);
	}
}
