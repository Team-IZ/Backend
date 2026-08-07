package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.PasswordResetAccount;

import java.time.Instant;

public interface PasswordResetMailSender {
	void sendResetLink(PasswordResetAccount account, String rawToken, Instant expiresAt);

	void sendInactiveAccountNotice(PasswordResetAccount account);
}
