package com.bigproject.backend.domain.member.infrastructure;

import com.bigproject.backend.domain.member.application.InvitationLinkFactory;
import com.bigproject.backend.domain.member.application.InvitationMailSender;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

	/**
	 * 일괄 발송은 <b>배열 한 번</b>으로 나가야 한다.
	 *
	 * <p>{@code JavaMailSenderImpl}은 배열을 받을 때만 Transport를 한 번 열고 재사용한다.
	 * 한 건씩 부르면 연결·STARTTLS 핸드셰이크·AUTH를 매번 반복해 건당 1초 이상을 쓴다 —
	 * 900명이면 그것만 약 28분이고, 그게 CSV 대량 등록이 게이트웨이 한도를 넘긴 원인이었다.
	 *
	 * <p>이 테스트가 없으면 건당 호출로 되돌아가도 기능은 그대로 동작해 아무도 모른다.
	 */
	@Test
	void sendsWholeBatchOverOneConnection() {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		SmtpInvitationMailSender sender = batchSender(javaMailSender, 100);
		when(javaMailSender.createMimeMessage())
				.thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));

		Map<UUID, String> failures = sender.sendTraineeInvitations(List.of(
				traineeMail("a@example.com"),
				traineeMail("b@example.com"),
				traineeMail("c@example.com")
		));

		assertThat(failures).isEmpty();
		ArgumentCaptor<MimeMessage[]> captor = ArgumentCaptor.forClass(MimeMessage[].class);
		// 3건이 배열 하나로 한 번에 나간다 — send(MimeMessage) 단건 호출은 없어야 한다.
		verify(javaMailSender, times(1)).send(captor.capture());
		assertThat(captor.getValue()).hasSize(3);
		verify(javaMailSender, never()).send(any(MimeMessage.class));
	}

	/** 제공자의 세션당 상한을 넘지 않도록 batch-size 단위로 연결을 다시 연다. */
	@Test
	void splitsBatchIntoChunksOfConfiguredSize() {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		SmtpInvitationMailSender sender = batchSender(javaMailSender, 2);
		when(javaMailSender.createMimeMessage())
				.thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));

		sender.sendTraineeInvitations(List.of(
				traineeMail("a@example.com"),
				traineeMail("b@example.com"),
				traineeMail("c@example.com")
		));

		// 3건 / 청크 2 = 배열 2회(2건 + 1건)
		verify(javaMailSender, times(2)).send(any(MimeMessage[].class));
	}

	/**
	 * 한 수신자가 실패해도 <b>예외를 던지지 않고</b> 그 건만 돌려준다.
	 *
	 * <p>예전에는 발송 실패가 예외로 올라가 요청 전체가 끝났다 — 500번째에서 튕기면 앞의 499건이
	 * 이미 커밋됐는데도 화면에는 전량 실패로 보였다.
	 */
	@Test
	void reportsOnlyFailedRecipientsInsteadOfThrowing() {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		SmtpInvitationMailSender sender = batchSender(javaMailSender, 100);
		List<MimeMessage> created = new ArrayList<>();
		when(javaMailSender.createMimeMessage()).thenAnswer(invocation -> {
			MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
			created.add(message);
			return message;
		});
		InvitationMailSender.TraineeInvitationMail good = traineeMail("good@example.com");
		InvitationMailSender.TraineeInvitationMail bad = traineeMail("bad@example.com");
		doAnswer(invocation -> {
			// 두 번째 메시지만 실패했다고 알린다 — JavaMailSenderImpl의 실제 동작과 같은 모양이다.
			throw new MailSendException(Map.of(created.get(1), new MailSendException("relay denied")));
		}).when(javaMailSender).send(any(MimeMessage[].class));

		Map<UUID, String> failures = sender.sendTraineeInvitations(List.of(good, bad));

		assertThat(failures).containsOnlyKeys(bad.invitation().tokenId());
		assertThat(failures.get(bad.invitation().tokenId())).contains("relay denied");
	}

	/** 연결·인증 단계에서 끊기면 개별 실패가 아니라 청크 전체가 나가지 못한 것이다. */
	@Test
	void marksWholeChunkFailedWhenConnectionNeverOpens() {
		JavaMailSender javaMailSender = mock(JavaMailSender.class);
		SmtpInvitationMailSender sender = batchSender(javaMailSender, 100);
		when(javaMailSender.createMimeMessage())
				.thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
		InvitationMailSender.TraineeInvitationMail first = traineeMail("a@example.com");
		InvitationMailSender.TraineeInvitationMail second = traineeMail("b@example.com");
		// failedMessages가 비어 있는 MailSendException — 연결 자체가 실패한 경우다.
		doThrow(new MailSendException("connection refused"))
				.when(javaMailSender).send(any(MimeMessage[].class));

		Map<UUID, String> failures = sender.sendTraineeInvitations(List.of(first, second));

		assertThat(failures).containsOnlyKeys(
				first.invitation().tokenId(), second.invitation().tokenId());
	}

	private SmtpInvitationMailSender batchSender(JavaMailSender javaMailSender, int batchSize) {
		SmtpInvitationMailSender sender = new SmtpInvitationMailSender(
				javaMailSender, new InvitationLinkFactory("https://frontend.example.com"));
		ReflectionTestUtils.setField(sender, "host", "smtp.gmail.com");
		ReflectionTestUtils.setField(sender, "port", 587);
		ReflectionTestUtils.setField(sender, "starttlsEnabled", true);
		ReflectionTestUtils.setField(sender, "timeZone", "Asia/Seoul");
		ReflectionTestUtils.setField(sender, "batchSize", batchSize);
		return sender;
	}

	private InvitationMailSender.TraineeInvitationMail traineeMail(String email) {
		return new InvitationMailSender.TraineeInvitationMail(
				new PendingInvitation(
						UUID.randomUUID(),
						UUID.randomUUID(),
						UUID.randomUUID(),
						email,
						"token-" + email,
						Role.TRAINEE,
						Instant.parse("2026-07-21T03:04:05Z"),
						Instant.parse("2026-07-22T03:04:05Z"),
						new InvitationContext(UUID.randomUUID(), "AIVLE", UUID.randomUUID(), "8기")
				),
				"교육생"
		);
	}
}
