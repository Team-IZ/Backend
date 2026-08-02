package com.bigproject.backend.domain.auth.application;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Component
public class PasswordPolicy {
	private static final int MIN_LENGTH = 8;
	private static final int MAX_LENGTH = 64;
	private static final int BCRYPT_MAX_BYTES = 72;
	private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
	private static final Pattern DIGIT = Pattern.compile("\\d");
	private static final Pattern SPECIAL = Pattern.compile("[^A-Za-z0-9]");

	public boolean isStrong(String password) {
		return password != null
				&& password.length() >= MIN_LENGTH
				&& password.length() <= MAX_LENGTH
				&& password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES
				&& LETTER.matcher(password).find()
				&& DIGIT.matcher(password).find()
				&& SPECIAL.matcher(password).find();
	}
}
