package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.application.InvitationLinkFactory;
import com.bigproject.backend.domain.member.application.InvitationMailSender;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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

	@Override
	public void sendManagerInvitation(PendingInvitation invitation) {
		String roleName = invitation.role().name().equals("LEAD_MANAGER") ? "총괄 매니저" : "일반 매니저";
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
		String invitationLink = linkFactory.traineeLink(invitation);
		send(
				invitation.email(),
				"[AIVLE] " + invitation.context().cohortName() + " 교육생 초대",
				"""
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
						HtmlUtils.htmlEscape(invitationLink),
						formatExpiration(invitation)
				)
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
		if (host == null || host.isBlank()) {
			throw new MailSendException("초대 메일 SMTP 호스트가 설정되지 않았습니다.");
		}
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
		mailSender.send(message);
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
