package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.application.InvitationLinkFactory;
import com.bigproject.backend.domain.member.application.InvitationMailSender;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SmtpInvitationMailSender implements InvitationMailSender {
	private final JavaMailSender mailSender;
	private final InvitationLinkFactory linkFactory;

	@Value("${invitation.mail.host:}")
	private String host;

	@Value("${invitation.mail.from:}")
	private String from;

	@Value("${invitation.mail.port:587}")
	private int port;

	@Value("${invitation.mail.username:}")
	private String username;

	@Value("${invitation.mail.password:}")
	private String password;

	@Value("${invitation.mail.starttls-enabled:true}")
	private boolean starttlsEnabled;

	@Value("${invitation.mail.time-zone:Asia/Seoul}")
	private String timeZone;

	/**
	 * 연결 하나로 보낼 최대 건수. 제공자마다 세션당 발송 상한이 있어(Gmail 계열은 통상 100건 안팎)
	 * 통째로 보내지 않고 끊는다. 상한을 확인하지 못한 환경에서도 안전한 값을 기본값으로 둔다.
	 */
	@Value("${invitation.mail.batch-size:100}")
	private int batchSize;

	@Override
	public void sendSuperAdminInvitation(PendingInvitation invitation) {
		String invitationLink = linkFactory.superAdminLink(invitation);
		send(
				invitation.email(),
				"[AIVLE] 슈퍼어드민 초대",
				"""
				<!doctype html>
				<html lang="ko">
				<body>
				<p>IZ-Get 플랫폼 콘솔의 슈퍼어드민으로 초대되었습니다.</p>
				<p>아래 링크에서 계정을 등록해 주세요.</p>
				<p><a href="%s">계정 등록하기</a></p>
				<p>초대 만료 시각: %s</p>
				</body>
				</html>
				""".formatted(
						HtmlUtils.htmlEscape(invitationLink),
						formatExpiration(invitation)
				)
		);
	}

	@Override
	public void sendManagerInvitation(PendingInvitation invitation) {
		String roleName = invitation.role() == Role.OPERATOR ? "오퍼레이터" : "일반 매니저";
		String invitationLink = linkFactory.managerLink(invitation);
		send(
				invitation.email(),
				"[AIVLE] " + roleName + " 초대",
				"""
				<!doctype html>
				<html lang="ko">
				<body>
				<p>%s 기관의 %s로 초대되었습니다.</p>
				<p>아래 링크에서 계정을 등록해 주세요.</p>
				<p><a href="%s">계정 등록하기</a></p>
				<p>초대 만료 시각: %s</p>
				</body>
				</html>
				""".formatted(
						HtmlUtils.htmlEscape(invitation.context().organizationName()),
						HtmlUtils.htmlEscape(roleName),
						HtmlUtils.htmlEscape(invitationLink),
						formatExpiration(invitation)
				)
		);
	}

	@Override
	public void sendTraineeInvitation(PendingInvitation invitation, String traineeName) {
		send(
				invitation.email(),
				traineeSubject(invitation),
				traineeHtml(invitation, traineeName)
		);
	}

	/**
	 * 청크마다 연결을 <b>한 번만</b> 연다.
	 *
	 * <p>{@code JavaMailSenderImpl.send(MimeMessage[])}는 Transport를 한 번 열고 배열을 순회하며 보낸다.
	 * 한 건씩 부르면 그 연결·핸드셰이크·AUTH를 매번 반복하게 되고, 그 고정비가 대량 등록 소요의 대부분이었다.
	 *
	 * <p>제공자마다 세션당 발송 상한이 있어 통째로 보내지 않고 {@code invitation.mail.batch-size}
	 * 단위로 끊는다. 상한을 모르는 환경에서도 안전한 값을 기본값으로 둔다.
	 */
	@Override
	public Map<UUID, String> sendTraineeInvitations(List<TraineeInvitationMail> mails) {
		requireHost();
		Map<UUID, String> failures = new LinkedHashMap<>();
		for (int start = 0; start < mails.size(); start += batchSize) {
			List<TraineeInvitationMail> chunk = mails.subList(start, Math.min(start + batchSize, mails.size()));
			sendChunk(chunk, failures);
		}
		return failures;
	}

	private void sendChunk(List<TraineeInvitationMail> chunk, Map<UUID, String> failures) {
		// MimeMessage는 equals를 재정의하지 않아 동일성 비교가 된다 — MailSendException이
		// 돌려주는 실패 메시지 객체로 어느 초대였는지 되찾을 수 있다.
		Map<MimeMessage, UUID> owners = new LinkedHashMap<>();
		for (TraineeInvitationMail mail : chunk) {
			PendingInvitation invitation = mail.invitation();
			try {
				owners.put(
						buildMessage(invitation.email(), traineeSubject(invitation), traineeHtml(invitation, mail.traineeName())),
						invitation.tokenId()
				);
			} catch (RuntimeException exception) {
				// 본문 생성 실패는 발송 전에 걸린다. 이 건만 실패로 두고 나머지는 그대로 보낸다.
				failures.put(invitation.tokenId(), reason(exception));
			}
		}
		if (owners.isEmpty()) {
			return;
		}

		log.info(
				"SMTP 일괄 발송: host={}, port={}, starttlsEnabled={}, usernameConfigured={}, count={}",
				host, port, starttlsEnabled, username != null && !username.isBlank(), owners.size()
		);
		try {
			mailSender.send(owners.keySet().toArray(new MimeMessage[0]));
		} catch (MailSendException exception) {
			Map<Object, Exception> failedMessages = exception.getFailedMessages();
			if (failedMessages.isEmpty()) {
				// 연결·인증 단계에서 끊기면 개별 메시지 실패가 아니라 청크 전체가 나가지 못한다.
				String reason = reason(exception);
				owners.values().forEach(tokenId -> failures.put(tokenId, reason));
				return;
			}
			failedMessages.forEach((message, cause) -> {
				UUID tokenId = owners.get(message);
				if (tokenId != null) {
					failures.put(tokenId, reason(cause));
				}
			});
		} catch (MailException exception) {
			String reason = reason(exception);
			owners.values().forEach(tokenId -> failures.put(tokenId, reason));
		}
	}

	private String traineeSubject(PendingInvitation invitation) {
		return "[AIVLE] " + invitation.context().cohortName() + " 교육생 초대";
	}

	private String traineeHtml(PendingInvitation invitation, String traineeName) {
		return """
				<!doctype html>
				<html lang="ko">
				<body>
				<p>%s님, %s 기관의 %s 교육생으로 초대되었습니다.</p>
				<p>아래 링크에서 계정을 활성화해 주세요.</p>
				<p><a href="%s">계정 활성화하기</a></p>
				<p>초대 만료 시각: %s</p>
				</body>
				</html>
				""".formatted(
				HtmlUtils.htmlEscape(traineeName),
				HtmlUtils.htmlEscape(invitation.context().organizationName()),
				HtmlUtils.htmlEscape(invitation.context().cohortName()),
				HtmlUtils.htmlEscape(linkFactory.traineeLink(invitation)),
				formatExpiration(invitation)
		);
	}

	private void send(String recipient, String subject, String html) {
		log.info(
				"SMTP 발송 설정 확인: host={}, port={}, starttlsEnabled={}, usernameConfigured={}, passwordConfigured={}, fromConfigured={}, recipientDomain={}",
				host,
				port,
				starttlsEnabled,
				username != null && !username.isBlank(),
				password != null && !password.isBlank(),
				from != null && !from.isBlank(),
				recipientDomain(recipient)
		);
		requireHost();
		mailSender.send(buildMessage(recipient, subject, html));
	}

	private void requireHost() {
		if (host == null || host.isBlank()) {
			throw new MailSendException("초대 메일 SMTP 호스트가 설정되지 않았습니다.");
		}
	}

	private MimeMessage buildMessage(String recipient, String subject, String html) {
		MimeMessage message = mailSender.createMimeMessage();
		try {
			MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
			if (from != null && !from.isBlank()) {
				helper.setFrom(from);
			}
			helper.setTo(recipient);
			helper.setSubject(subject);
			helper.setText(html, true);
		} catch (MessagingException exception) {
			throw new MailPreparationException("초대 메일 HTML 본문 생성에 실패했습니다.", exception);
		}
		return message;
	}

	/** 원장의 failure_reason에 남길 짧은 사유. 로그성 문자열이 무한정 길어지지 않도록 자른다. */
	private String reason(Throwable exception) {
		Throwable rootCause = exception;
		while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
			rootCause = rootCause.getCause();
		}
		String message = rootCause.getMessage();
		String reason = rootCause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
		return reason.length() > 1000 ? reason.substring(0, 1000) : reason;
	}

	private String formatExpiration(PendingInvitation invitation) {
		return DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 HH시 mm분 ss초")
				.withZone(ZoneId.of(timeZone))
				.format(invitation.expiresAt());
	}

	private String recipientDomain(String recipient) {
		int separatorIndex = recipient == null ? -1 : recipient.lastIndexOf('@');
		return separatorIndex < 0 ? "unknown" : recipient.substring(separatorIndex + 1);
	}
}
