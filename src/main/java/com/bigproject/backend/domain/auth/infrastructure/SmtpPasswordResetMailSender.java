package com.bigproject.backend.domain.auth.infrastructure;

import com.bigproject.backend.domain.auth.application.PasswordResetLinkFactory;
import com.bigproject.backend.domain.auth.application.PasswordResetMailSender;
import com.bigproject.backend.domain.auth.domain.PasswordResetAccount;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class SmtpPasswordResetMailSender implements PasswordResetMailSender {
	private final JavaMailSender mailSender;
	private final PasswordResetLinkFactory linkFactory;

	@Value("${invitation.mail.host:}")
	private String host;

	@Value("${invitation.mail.from:}")
	private String from;

	@Value("${invitation.mail.time-zone:Asia/Seoul}")
	private String timeZone;

	@Override
	public void sendResetLink(PasswordResetAccount account, String rawToken, Instant expiresAt) {
		String link = linkFactory.create(rawToken);
		send(
				account.email(),
				"[AIVLE] 비밀번호 재설정 안내",
				"""
				<!doctype html>
				<html lang="ko">
				<body>
				<p>아래 링크에서 새 비밀번호를 설정해 주세요.</p>
				<p><a href="%s">비밀번호 재설정하기</a></p>
				<p>링크 만료 시각: %s</p>
				<p>요청하지 않았다면 이 메일을 무시해 주세요.</p>
				</body>
				</html>
				""".formatted(HtmlUtils.htmlEscape(link), format(expiresAt))
		);
	}

	@Override
	public void sendInactiveAccountNotice(PasswordResetAccount account) {
		send(
				account.email(),
				"[AIVLE] 계정 이용 문의 안내",
				"""
				<!doctype html>
				<html lang="ko">
				<body>
				<p>현재 비밀번호를 재설정할 수 없는 계정입니다.</p>
				<p>소속 기관 관리자에게 계정 상태를 문의해 주세요.</p>
				</body>
				</html>
				"""
		);
	}

	private void send(String recipient, String subject, String html) {
		if (host == null || host.isBlank()) {
			throw new MailSendException("비밀번호 재설정 메일 SMTP 호스트가 설정되지 않았습니다.");
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
			throw new MailPreparationException("비밀번호 재설정 메일 생성에 실패했습니다.", exception);
		}
		mailSender.send(message);
	}

	private String format(Instant instant) {
		return DateTimeFormatter.ofPattern("yyyy년 MM월 dd일 HH시 mm분 ss초")
				.withZone(ZoneId.of(timeZone))
				.format(instant);
	}
}
