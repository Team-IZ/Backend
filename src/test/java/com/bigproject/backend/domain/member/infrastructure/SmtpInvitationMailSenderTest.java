package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.application.InvitationLinkFactory;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpInvitationMailSenderTest {

	@Test
	void sendsManagerInvitationAsHtmlWithClickableLinkAndFormattedExpiration() throws Exception {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		InvitationLinkFactory linkFactory = new InvitationLinkFactory("https://frontend.example.com");
		SmtpInvitationMailSender sender = new SmtpInvitationMailSender(javaMailSender, linkFactory);
		ReflectionTestUtils.setField(sender, "host", "smtp.gmail.com");
		ReflectionTestUtils.setField(sender, "port", 587);
		ReflectionTestUtils.setField(sender, "starttlsEnabled", true);
		ReflectionTestUtils.setField(sender, "timeZone", "Asia/Seoul");
		MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		when(javaMailSender.createMimeMessage()).thenReturn(message);
		PendingInvitation invitation = new PendingInvitation(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"lead@example.com",
				"raw-token",
				Role.OPERATOR,
				Instant.parse("2026-07-21T03:04:05Z"),
				Instant.parse("2026-07-22T03:04:05Z"),
				InvitationContext.organization(UUID.randomUUID(), "AIVLE")
		);

		sender.sendManagerInvitation(invitation);

		verify(javaMailSender).send(message);
		message.saveChanges();
		assertThat(message.getContentType()).startsWith("text/html");
		assertThat(message.getContent().toString())
				.contains("<a href=\"https://frontend.example.com/invite/op-raw-token\">계정 등록하기</a>")
				.contains("초대 만료 시각: 2026년 07월 22일 12시 04분 05초");
	}

	@Test
	void sendsTraineeInvitationWithStudentPrefixedPath() throws Exception {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		InvitationLinkFactory linkFactory = new InvitationLinkFactory("https://frontend.example.com/");
		SmtpInvitationMailSender sender = new SmtpInvitationMailSender(javaMailSender, linkFactory);
		ReflectionTestUtils.setField(sender, "host", "smtp.gmail.com");
		ReflectionTestUtils.setField(sender, "port", 587);
		ReflectionTestUtils.setField(sender, "starttlsEnabled", true);
		ReflectionTestUtils.setField(sender, "timeZone", "Asia/Seoul");
		MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		when(javaMailSender.createMimeMessage()).thenReturn(message);
		PendingInvitation invitation = new PendingInvitation(
				UUID.randomUUID(),
				UUID.randomUUID(),
				UUID.randomUUID(),
				"trainee@example.com",
				"student-token",
				Role.TRAINEE,
				Instant.parse("2026-07-21T03:04:05Z"),
				Instant.parse("2026-07-22T03:04:05Z"),
				new InvitationContext(UUID.randomUUID(), "AIVLE", UUID.randomUUID(), "8기")
		);

		sender.sendTraineeInvitation(invitation, "교육생");

		verify(javaMailSender).send(message);
		message.saveChanges();
		assertThat(message.getContent().toString())
				.contains("<a href=\"https://frontend.example.com/invite/stu-student-token\">계정 활성화하기</a>");
	}
}
