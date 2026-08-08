package com.bigproject.backend.domain.member.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Configuration
public class InvitationMailConfig {

	@Bean
	JavaMailSender invitationJavaMailSender(
			@Value("${invitation.mail.host:}") String host,
			@Value("${invitation.mail.port:587}") int port,
			@Value("${invitation.mail.username:}") String username,
			@Value("${invitation.mail.password:}") String password,
			@Value("${invitation.mail.starttls-enabled:true}") boolean starttlsEnabled,
			@Value("${invitation.mail.connection-timeout-ms:5000}") int connectionTimeoutMs,
			@Value("${invitation.mail.read-timeout-ms:10000}") int readTimeoutMs,
			@Value("${invitation.mail.write-timeout-ms:10000}") int writeTimeoutMs
	) {
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost(host);
		sender.setPort(port);
		sender.setUsername(username);
		sender.setPassword(password);
		sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
		Properties properties = sender.getJavaMailProperties();
		properties.put("mail.smtp.auth", Boolean.toString(!username.isBlank()));
		properties.put("mail.smtp.starttls.enable", Boolean.toString(starttlsEnabled));

		/*
		 * 타임아웃을 반드시 건다. JavaMail 기본값은 <b>무한 대기</b>다.
		 *
		 * 초대 메일은 HTTP 요청 스레드에서 동기로 발송된다(자리 확보 → 메일 → 결과 기록).
		 * SMTP 서버에 닿지 못하면 그 스레드가 그대로 묶이고, 그런 요청이 쌓이면 톰캣 워커가 전부 잠겨
		 * <b>초대와 무관한 API까지 응답하지 못한다.</b>
		 *
		 * 실제로 배포 환경(SMTP 아웃바운드 차단)에서 요청이 수십 초 매달렸다가
		 * OS TCP 타임아웃으로 끊긴 사례가 있다. 최악의 경우에도 아래 값 안에서 끝나야 한다.
		 */
		properties.put("mail.smtp.connectiontimeout", Integer.toString(connectionTimeoutMs));
		properties.put("mail.smtp.timeout", Integer.toString(readTimeoutMs));
		properties.put("mail.smtp.writetimeout", Integer.toString(writeTimeoutMs));
		return sender;
	}
}
