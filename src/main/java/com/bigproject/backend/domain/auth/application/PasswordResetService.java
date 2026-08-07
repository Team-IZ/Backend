package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.domain.auth.domain.PasswordResetRepository;
import com.bigproject.backend.domain.auth.domain.PasswordResetToken;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetConfirmationRequest;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetConfirmationResponse;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetRequestResponse;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetValidationRequest;
import com.bigproject.backend.domain.auth.presentation.dto.PasswordResetValidationResponse;
import com.bigproject.backend.domain.auth.infrastructure.PasswordResetAuditLogger;
import com.bigproject.backend.domain.member.application.EmailNormalizer;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.global.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Slf4j
public class PasswordResetService {
	private static final String ACCEPTED_MESSAGE = "입력하신 주소가 계정에 등록돼 있으면 안내 메일이 도착합니다.";

	private final PasswordResetRequestDispatcher requestDispatcher;
	private final PasswordResetRepository repository;
	private final OneTimeTokenHasher tokenHasher;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;
	private final PasswordResetAuditLogger auditLogger;

	public PasswordResetService(
			PasswordResetRequestDispatcher requestDispatcher,
			PasswordResetRepository repository,
			OneTimeTokenHasher tokenHasher,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			PasswordResetAuditLogger auditLogger
	) {
		this.requestDispatcher = requestDispatcher;
		this.repository = repository;
		this.tokenHasher = tokenHasher;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.auditLogger = auditLogger;
	}

	public PasswordResetRequestResponse request(String email, String requestId) {
		try {
			requestDispatcher.dispatch(EmailNormalizer.normalize(email), requestId);
		} catch (RuntimeException exception) {
			log.warn("비밀번호 재설정 안내 처리를 완료하지 못했습니다: requestId={}, exceptionType={}",
					requestId, exception.getClass().getSimpleName());
			auditLogger.recordFailure(null, requestId, "RESET_MAIL_FAILED");
		}
		return new PasswordResetRequestResponse(ACCEPTED_MESSAGE);
	}

	@Transactional(readOnly = true)
	public PasswordResetValidationResponse validate(PasswordResetValidationRequest request) {
		PasswordResetToken token = repository.findToken(tokenHasher.hash(request.token().trim()))
				.orElseThrow(this::invalidToken);
		validateToken(token);
		return new PasswordResetValidationResponse(token.email(), token.expiresAt());
	}

	@Transactional
	public PasswordResetConfirmationResponse confirm(PasswordResetConfirmationRequest request, String requestId) {
		PasswordResetToken token = null;
		try {
			token = repository.findTokenForUpdate(tokenHasher.hash(request.token().trim()))
					.orElseThrow(this::invalidToken);
			validateToken(token);
			validatePassword(request.newPassword(), token.passwordHash());

			Instant changedAt = Instant.now();
			if (!repository.updatePassword(token.userId(), passwordEncoder.encode(request.newPassword()), changedAt)
					|| !repository.markTokenUsed(token.tokenId(), requestId, changedAt)) {
				throw resetFailed(null);
			}
			repository.invalidateOtherResetTokens(token.userId(), token.tokenId(), changedAt);
			repository.revokeAllRefreshTokens(token.userId(), changedAt);
			auditLogger.recordConfirmationSuccess(token, requestId);
			return new PasswordResetConfirmationResponse(
					"COMPLETED",
					"비밀번호가 바뀌었습니다. 새 비밀번호로 다시 로그인해 주세요."
			);
		} catch (ApiException exception) {
			auditLogger.recordFailure(token, requestId, exception.errorCode().name());
			throw exception;
		} catch (DataAccessException | IllegalStateException exception) {
			ApiException resetFailed = resetFailed(exception);
			auditLogger.recordFailure(token, requestId, resetFailed.errorCode().name());
			throw resetFailed;
		}
	}

	private void validateToken(PasswordResetToken token) {
		if (!"PASSWORD_RESET".equals(token.purpose())
				|| token.userId() == null
				|| !"ACTIVE".equals(token.userStatus())
				|| token.invalidatedAt() != null) {
			throw invalidToken();
		}
		if (token.usedAt() != null) {
			throw new ApiException(AuthErrorCode.RESET_TOKEN_USED);
		}
		if (!token.expiresAt().isAfter(Instant.now())) {
			throw new ApiException(AuthErrorCode.RESET_TOKEN_EXPIRED);
		}
	}

	private void validatePassword(String newPassword, String currentPasswordHash) {
		if (!passwordPolicy.isStrong(newPassword)) {
			throw new ApiException(AuthErrorCode.WEAK_PASSWORD);
		}
		if (currentPasswordHash != null && passwordEncoder.matches(newPassword, currentPasswordHash)) {
			throw new ApiException(AuthErrorCode.SAME_AS_CURRENT);
		}
	}

	private ApiException invalidToken() {
		return new ApiException(AuthErrorCode.RESET_TOKEN_INVALID);
	}

	private ApiException resetFailed(Throwable cause) {
		return new ApiException(AuthErrorCode.RESET_FAILED, cause);
	}
}
