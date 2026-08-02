package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AccountActivationRepository;
import com.bigproject.backend.domain.auth.domain.AccountActivationTarget;
import com.bigproject.backend.domain.auth.domain.ConsentCode;
import com.bigproject.backend.domain.auth.domain.ConsentRecord;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.ActivateAccountResponse;
import com.bigproject.backend.domain.auth.presentation.dto.ManagerSignupRequest;
import com.bigproject.backend.domain.auth.presentation.dto.TraineeActivationRequest;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AccountActivationService {
	private static final String INVALID_INVITATION_MESSAGE = "유효하지 않거나 만료된 초대입니다.";
	private static final String CAPTURE_CHANNEL = "INVITE_LINK";

	private final AccountActivationRepository accountActivationRepository;
	private final OneTimeTokenHasher tokenHasher;
	private final PasswordEncoder passwordEncoder;
	private final int consentPolicyVersion;
	private final PasswordPolicy passwordPolicy = new PasswordPolicy();

	public AccountActivationService(
			AccountActivationRepository accountActivationRepository,
			OneTimeTokenHasher tokenHasher,
			PasswordEncoder passwordEncoder,
			@Value("${consent.policy-version:1}") int consentPolicyVersion
	) {
		this.accountActivationRepository = accountActivationRepository;
		this.tokenHasher = tokenHasher;
		this.passwordEncoder = passwordEncoder;
		this.consentPolicyVersion = consentPolicyVersion;
	}

	@Transactional
	public ActivateAccountResponse activateManager(
			ManagerSignupRequest request,
			TokenRequestMetadata metadata,
			String requestId,
			String locale
	) {
		validatePassword(request.password(), request.passwordConfirmation());
		validateRequiredConsents(request.serviceTermsAgreed(), request.privacyCollectionAgreed());
		AccountActivationTarget target = findTarget(
				request.invitationToken(),
				request.userId(),
				InvitationPurpose.INVITE_OPERATOR_MANAGER
		);
		if (target.role() != Role.OPERATOR && target.role() != Role.MANAGER) {
			throw invalidInvitation();
		}
		return activate(
				target,
				request.name().trim(),
				request.password(),
				List.of(
						new ConsentChoice(ConsentCode.TERMS_OF_SERVICE, true),
						new ConsentChoice(ConsentCode.PRIVACY_POLICY, true)
				),
				false,
				metadata,
				requestId,
				locale
		);
	}

	@Transactional
	public ActivateAccountResponse activateTrainee(
			TraineeActivationRequest request,
			TokenRequestMetadata metadata,
			String requestId,
			String locale
	) {
		validatePassword(request.password(), request.passwordConfirmation());
		validateRequiredConsents(
				request.serviceTermsAgreed(),
				request.privacyCollectionAgreed(),
				request.aiAnalysisAgreed(),
				request.organizationSharingAgreed()
		);
		AccountActivationTarget target = findTarget(
				request.invitationToken(),
				request.userId(),
				InvitationPurpose.INVITE_TRAINEE
		);
		if (target.role() != Role.TRAINEE) {
			throw invalidInvitation();
		}
		return activate(
				target,
				target.name(),
				request.password(),
				List.of(
						new ConsentChoice(ConsentCode.TERMS_OF_SERVICE, true),
						new ConsentChoice(ConsentCode.PRIVACY_POLICY, true),
						new ConsentChoice(ConsentCode.AI_ANALYSIS, true),
						new ConsentChoice(ConsentCode.ORGANIZATION_SHARING, true),
						new ConsentChoice(ConsentCode.ANONYMIZED_DATA_USAGE, request.anonymousImprovementAgreed())
				),
				true,
				metadata,
				requestId,
				locale
		);
	}

	private AccountActivationTarget findTarget(
			String invitationToken,
			UUID userId,
			InvitationPurpose purpose
	) {
		String tokenHash = tokenHasher.hash(invitationToken.trim());
		return accountActivationRepository.findTargetForUpdate(tokenHash, userId, purpose, Instant.now())
				.orElseThrow(this::invalidInvitation);
	}

	private ActivateAccountResponse activate(
			AccountActivationTarget target,
			String name,
			String password,
			List<ConsentChoice> consentChoices,
			boolean activateTraineeMembership,
			TokenRequestMetadata metadata,
			String requestId,
			String locale
	) {
		Instant activatedAt = Instant.now();
		boolean activated = accountActivationRepository.activateUser(
				target.userId(),
				target.rowVersion(),
				name,
				passwordEncoder.encode(password),
				activatedAt
		);
		if (!activated) {
			throw activationConflict();
		}

		accountActivationRepository.saveConsentRecords(consentRecords(
				target,
				consentChoices,
				activatedAt,
				metadata,
				requestId,
				locale
		));

		if (activateTraineeMembership && !accountActivationRepository.activateTraineeMembership(
				target.userId(),
				target.tokenId(),
				activatedAt
		)) {
			throw activationConflict();
		}

		if (!accountActivationRepository.markInvitationUsed(target.tokenId(), requestId, activatedAt)) {
			throw activationConflict();
		}

		return new ActivateAccountResponse(target.userId(), target.email(), name, target.role(), true);
	}

	private List<ConsentRecord> consentRecords(
			AccountActivationTarget target,
			List<ConsentChoice> choices,
			Instant capturedAt,
			TokenRequestMetadata metadata,
			String requestId,
			String locale
	) {
		List<ConsentRecord> records = new ArrayList<>(choices.size());
		for (ConsentChoice choice : choices) {
			String evidence = String.join(
					"|",
					target.userId().toString(),
					target.tokenId().toString(),
					choice.code().name(),
					Integer.toString(consentPolicyVersion),
					Boolean.toString(choice.agreed()),
					capturedAt.toString(),
					CAPTURE_CHANNEL,
					requestId
			);
			records.add(new ConsentRecord(
					UUID.randomUUID(),
					target.organizationId(),
					target.userId(),
					choice.code(),
					consentPolicyVersion,
					choice.agreed(),
					capturedAt,
					CAPTURE_CHANNEL,
					metadata.ipAddress(),
					metadata.userAgent(),
					locale,
					tokenHasher.hash(evidence)
			));
		}
		return List.copyOf(records);
	}

	private void validatePassword(String password, String passwordConfirmation) {
		if (password == null || !password.equals(passwordConfirmation)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호 확인이 일치하지 않습니다.");
		}
		if (!passwordPolicy.isStrong(password)) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"비밀번호는 8~64자이며 영문, 숫자, 특수문자를 포함해야 합니다."
			);
		}
	}

	private void validateRequiredConsents(boolean... requiredConsents) {
		for (boolean agreed : requiredConsents) {
			if (!agreed) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "필수 동의 항목에 모두 동의해야 합니다.");
			}
		}
	}

	private ResponseStatusException invalidInvitation() {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_INVITATION_MESSAGE);
	}

	private ResponseStatusException activationConflict() {
		return new ResponseStatusException(HttpStatus.CONFLICT, "계정 활성화 상태가 변경되었습니다. 다시 시도해 주세요.");
	}

	private record ConsentChoice(ConsentCode code, boolean agreed) {
	}
}
