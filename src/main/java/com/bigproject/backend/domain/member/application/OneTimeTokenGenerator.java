package com.bigproject.backend.domain.member.application;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class OneTimeTokenGenerator {
	private static final int TOKEN_BYTES = 32;
	private final SecureRandom secureRandom = new SecureRandom();

	public String generate() {
		byte[] bytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
