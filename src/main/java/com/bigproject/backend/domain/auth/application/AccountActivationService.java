package com.bigproject.backend.domain.auth.application;

import com.bigproject.backend.domain.auth.domain.AccountActivationRepository;
import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.domain.auth.domain.AccountActivationTarget;
import com.bigproject.backend.domain.auth.domain.ConsentCode;
import com.bigproject.backend.domain.auth.domain.ConsentRecord;
import com.bigproject.backend.domain.auth.domain.InvitationState;
import com.bigproject.backend.domain.auth.domain.InvitationStateClassifier;
import com.bigproject.backend.domain.auth.domain.TokenRequestMetadata;
import com.bigproject.backend.domain.auth.presentation.dto.ActivateAccountResponse;
import com.bigproject.backend.domain.auth.presentation.dto.ManagerSignupRequest;
import com.bigproject.backend.domain.auth.presentation.dto.TraineeActivationRequest;
import com.bigproject.backend.domain.member.application.OneTimeTokenHasher;
import com.bigproject.backend.domain.member.domain.InvitationPurpose;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.global.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountActivationService {
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
		AccountActivationTarget target = findInvitedStaff(request.invitationToken(), request.userId());
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
		AccountActivationTarget target = findInvitedTrainee(request.invitationToken(), request.userId());
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

	/**
	 * 초대받은 운영 계정(슈퍼어드민·오퍼레이터·매니저)의 활성화 대상을 찾는다.
	 *
	 * <p><b>토큰 목적으로 허용 역할을 가른다.</b> INVITE_SUPER_ADMIN 토큰은 SUPER_ADMIN만,
	 * INVITE_OPERATOR_MANAGER 토큰은 OPERATOR·MANAGER만 통과시킨다. 목적과 역할을 교차 허용하면
	 * 오퍼레이터 초대 토큰으로 슈퍼어드민이 되는 권한 상승 경로가 열린다.
	 */
	private AccountActivationTarget findInvitedStaff(String invitationToken, UUID userId) {
		InvitationState state = findState(invitationToken, userId);
		Set<Role> allowedRoles;
		if (InvitationPurpose.INVITE_SUPER_ADMIN.name().equals(state.purpose())) {
			allowedRoles = Set.of(Role.SUPER_ADMIN);
		} else if (InvitationPurpose.INVITE_OPERATOR_MANAGER.name().equals(state.purpose())) {
			allowedRoles = Set.of(Role.OPERATOR, Role.MANAGER);
		} else {
			throw InvitationStateClassifier.invalid();
		}
		InvitationStateClassifier.verify(state, Instant.now());
		if (!allowedRoles.contains(state.role())) {
			throw InvitationStateClassifier.invalid();
		}
		return state.toActivationTarget();
	}

	private AccountActivationTarget findInvitedTrainee(String invitationToken, UUID userId) {
		InvitationState state = findState(invitationToken, userId);
		if (!InvitationPurpose.INVITE_TRAINEE.name().equals(state.purpose())) {
			throw InvitationStateClassifier.invalid();
		}
		InvitationStateClassifier.verify(state, Instant.now());
		if (state.role() != Role.TRAINEE) {
			throw InvitationStateClassifier.invalid();
		}
		return state.toActivationTarget();
	}

	/**
	 * 목적·역할 검사보다 {@link InvitationStateClassifier#verify}를 <b>먼저</b> 돌리지 않는다.
	 * 목적이 다른 토큰은 애초에 이 흐름의 링크가 아니므로 만료·명단외 같은 안내를 붙이면 안 된다.
	 * 반대로 역할 검사는 판정 뒤에 둔다 — 만료된 교육생 토큰은 "역할이 다름"이 아니라
	 * "만료됨"으로 안내해야 사용자가 재발송을 요청할 수 있다.
	 */
	private InvitationState findState(String invitationToken, UUID userId) {
		String tokenHash = tokenHasher.hash(invitationToken.trim());
		InvitationState state = accountActivationRepository.findStateForUpdate(tokenHash)
				.orElseThrow(InvitationStateClassifier::invalid);
		if (!state.userId().equals(userId)) {
			throw InvitationStateClassifier.invalid();
		}
		return state;
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

	/**
	 * 비밀번호 입력을 검사한다.
	 *
	 * <p>"확인 값 불일치"와 "정책 미충족"은 화면이 다른 칸에 다른 문구를 붙여야 하므로 코드를 가른다.
	 * 정책 미충족은 비밀번호 재설정 흐름과 <b>같은</b> {@code WEAK_PASSWORD}를 쓴다 — 같은 개념에
	 * 흐름별로 다른 코드를 주면 프론트가 규칙 안내 문구를 두 벌 관리하게 된다.
	 */
	private void validatePassword(String password, String passwordConfirmation) {
		if (password == null || !password.equals(passwordConfirmation)) {
			throw new ApiException(AuthErrorCode.PASSWORD_CONFIRMATION_MISMATCH);
		}
		if (!passwordPolicy.isStrong(password)) {
			throw new ApiException(AuthErrorCode.WEAK_PASSWORD);
		}
	}

	private void validateRequiredConsents(boolean... requiredConsents) {
		for (boolean agreed : requiredConsents) {
			if (!agreed) {
				throw new ApiException(AuthErrorCode.REQUIRED_CONSENT_MISSING);
			}
		}
	}

	/**
	 * 동시 요청으로 계정·초대 상태가 먼저 바뀐 경우. <b>입력이 틀린 것이 아니므로</b> 화면은
	 * 입력칸에 오류를 붙이지 말고 새로고침 후 재시도를 안내해야 한다 — 코드를 갈라 두는 이유다.
	 */
	private ApiException activationConflict() {
		return new ApiException(AuthErrorCode.ACTIVATION_STATE_CHANGED);
	}

	private record ConsentChoice(ConsentCode code, boolean agreed) {
	}
}
