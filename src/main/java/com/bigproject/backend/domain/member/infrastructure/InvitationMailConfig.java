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
			@Value("${invitation.mail.starttls-enabled:true}") boolean starttlsEnabled
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
		return sender;
	}
}
