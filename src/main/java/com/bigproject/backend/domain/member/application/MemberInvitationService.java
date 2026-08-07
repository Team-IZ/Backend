package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.domain.TraineeInvitationFailureStatus;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerRequest;
import com.bigproject.backend.domain.member.presentation.dto.InviteManagerResponse;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesRequest;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MemberInvitationService {
	private static final String ACTIVE = "ACTIVE";
	private static final Pattern EMAIL_PATTERN = Pattern.compile(
			"^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
					+ "@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
					+ "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"
	);

	private final AuthUserRepository authUserRepository;
	private final MemberInvitationRepository invitationRepository;
	private final TransactionalInvitationDispatcher invitationDispatcher;

	/**
	 * 오퍼레이터 또는 매니저를 초대한다.
	 *
	 * <p>대상 역할은 <b>호출부가 명시</b>한다. 예전에는 액터 역할로 대상을 추측했지만,
	 * 그러면 같은 진입점이 호출자에 따라 다른 역할을 만들어 초대 경로가 뒤섞였다.
	 * 지금은 경로마다 역할이 고정된다 — 오퍼레이터 초대(SA-02 ②)는 {@link Role#OPERATOR},
	 * 매니저 초대(OP-06)는 {@link Role#MANAGER}다.
	 */
	/**
	 * 슈퍼어드민을 초대한다(목업 SA-03 ②).
	 *
	 * <p>{@link #inviteManager}와 별도 진입점인 이유는 <b>기관이 없기 때문</b>이다. 그쪽은
	 * {@code findActiveOrganization}으로 기관을 확정하고 시작하는데, 슈퍼어드민은 어느 기관에도
	 * 속하지 않아 그 단계를 통과할 수 없다. 대상 역할도 SUPER_ADMIN으로 고정이라 인자로 받지 않는다.
	 *
	 * <p>초대 주체는 슈퍼어드민뿐이다 — 오퍼레이터·매니저가 자기보다 상위 권한을 만들 수 없어야 한다.
	 *
	 * <p>이메일 도메인 제한을 두지 않는다. 플랫폼 도메인을 저장하는 곳이 없고, 오퍼레이터 초대와 같은
	 * 이유(초대 시점에는 그 도메인 메일함을 아직 가질 수 없다)가 슈퍼어드민에도 적용된다.
	 */
	/**
	 * 초대 메일을 다시 보낸다(목업 SA-02 ② [재발송]).
	 *
	 * <p>발송 실패(DELIVERY_FAILED)뿐 아니라 <b>만료된 초대</b>도 대상이다 — 만료야말로 재발송이 필요한
	 * 상황이다. 이미 수락·취소된 초대는 조회 단계에서 걸러진다.
	 *
	 * <p>권한은 <b>최초 초대와 같은 규칙</b>을 쓴다. 재발송이 초대보다 헐거우면 그쪽이 우회로가 된다 —
	 * 오퍼레이터 초대는 슈퍼어드민만, 매니저 초대는 그 기관 오퍼레이터만 다시 보낼 수 있다.
	 *
	 * <p>기관 범위 검증(이 토큰이 그 기관 것인가)은 호출부가 한다. 여기서는 역할 축만 본다.
	 */
	public InviteManagerResponse resendInvitation(UUID tokenId, String actorEmail, String requestId) {
		AuthUser actor = activeActor(actorEmail);
		MemberInvitationRepository.ResendableInvitation target = invitationRepository
				.findResendableInvitation(tokenId)
				.orElseThrow(() -> new ApiException(MemberErrorCode.INVITATION_NOT_RESENDABLE));
		validateResendAuthority(actor, target);

		PendingInvitation invitation;
		try {
			invitation = invitationDispatcher.resend(target, actor, requestId(requestId));
		} catch (InvitationDeliveryException exception) {
			throw new ApiException(MemberErrorCode.INVITE_MAIL_FAILED, exception.getMessage(), exception);
		}

		return new InviteManagerResponse(
				invitation.memberId(),
				invitation.email(),
				invitation.role(),
				AccountStatus.INVITED,
				invitation.invitedAt()
		);
	}

	/** 재발송 권한. 최초 초대({@link #validateManagerInvitationAuthority})와 같은 규칙을 역할별로 적용한다. */
	private void validateResendAuthority(AuthUser actor, MemberInvitationRepository.ResendableInvitation target) {
		switch (target.targetRole()) {
			case SUPER_ADMIN, OPERATOR -> {
				if (actor.role() != Role.SUPER_ADMIN) {
					throw new ApiException(MemberErrorCode.INVITE_ROLE_NOT_ALLOWED, "슈퍼어드민만 이 초대를 재발송할 수 있습니다.");
				}
			}
			case MANAGER, TRAINEE -> {
				if (actor.role() != Role.OPERATOR) {
					throw new ApiException(MemberErrorCode.INVITE_ROLE_NOT_ALLOWED, "오퍼레이터만 이 초대를 재발송할 수 있습니다.");
				}
				if (!java.util.Objects.equals(target.organizationId(), actor.organizationId())) {
					throw new ApiException(MemberErrorCode.INVITE_CROSS_ORGANIZATION, "다른 기관의 초대는 재발송할 수 없습니다.");
				}
			}
		}
	}

	public InviteManagerResponse inviteSuperAdmin(String email, String actorEmail, String requestId) {
		AuthUser actor = activeActor(actorEmail);
		if (actor.role() != Role.SUPER_ADMIN) {
			throw new ApiException(MemberErrorCode.INVITE_ROLE_NOT_ALLOWED, "슈퍼어드민만 슈퍼어드민을 초대할 수 있습니다.");
		}
		String trimmedEmail = email == null ? "" : email.trim();
		if (!isValidEmail(trimmedEmail)) {
			throw new ApiException(MemberErrorCode.EMAIL_FORMAT_INVALID);
		}

		PendingInvitation invitation;
		try {
			invitation = invitationDispatcher.inviteSuperAdmin(trimmedEmail, actor, requestId(requestId));
		} catch (DataIntegrityViolationException exception) {
			String normalizedEmail = EmailNormalizer.normalize(trimmedEmail);
			if (invitationRepository.existsUserByNormalizedEmail(normalizedEmail)
					|| invitationRepository.existsIncompleteInvitationByNormalizedEmail(normalizedEmail)) {
				throw new InvitationConflictException("이미 등록되었거나 초대된 이메일입니다.", exception);
			}
			throw new ApiException(
					MemberErrorCode.INVITATION_SAVE_FAILED,
					"초대 정보를 저장할 수 없습니다.",
					exception
			);
		} catch (InvitationDeliveryException exception) {
			throw new ApiException(MemberErrorCode.INVITE_MAIL_FAILED, exception.getMessage(), exception);
		}

		return new InviteManagerResponse(
				invitation.memberId(),
				invitation.email(),
				invitation.role(),
				AccountStatus.INVITED,
				invitation.invitedAt()
		);
	}

	public InviteManagerResponse inviteManager(
			UUID organizationId,
			InviteManagerRequest request,
			Role targetRole,
			String actorEmail,
			String requestId
	) {
		AuthUser actor = activeActor(actorEmail);
		validateManagerInvitationRequest(request, targetRole);
		validateManagerInvitationAuthority(actor, targetRole, organizationId);
		InvitationContext context = invitationRepository.findActiveOrganization(organizationId)
				.orElseThrow(() -> new ApiException(MemberErrorCode.ORGANIZATION_NOT_FOUND));

		PendingInvitation invitation;
		try {
			invitation = invitationDispatcher.inviteManager(
					context,
					request,
					targetRole,
					actor,
					requestId(requestId)
			);
		} catch (DataIntegrityViolationException exception) {
			String normalizedEmail = EmailNormalizer.normalize(request.email());
			if (invitationRepository.existsUserByNormalizedEmail(normalizedEmail)
					|| invitationRepository.existsIncompleteInvitationByNormalizedEmail(normalizedEmail)) {
				throw new InvitationConflictException("이미 등록되었거나 초대된 이메일입니다.", exception);
			}
			throw new ApiException(
					MemberErrorCode.INVITATION_SAVE_FAILED,
					"초대 정보를 저장할 수 없습니다.",
					exception
			);
		} catch (InvitationDeliveryException exception) {
			throw new ApiException(MemberErrorCode.INVITE_MAIL_FAILED, exception.getMessage(), exception);
		}

		return new InviteManagerResponse(
				invitation.memberId(),
				invitation.email(),
				invitation.role(),
				AccountStatus.INVITED,
				invitation.invitedAt()
		);
	}

	public RegisterTraineesResponse inviteTraineesFromCsv(
			UUID cohortId,
			List<TraineeCsvRow> rows,
			String actorEmail,
			String requestId
	) {
		return inviteTrainees(cohortId, rows, actorEmail, requestId);
	}

	public RegisterTraineesResponse inviteTrainees(
			UUID cohortId,
			RegisterTraineesRequest request,
			String actorEmail,
			String requestId
	) {
		List<TraineeCsvRow> rows = new ArrayList<>(request.trainees().size());
		for (int index = 0; index < request.trainees().size(); index++) {
			RegisterTraineesRequest.Trainee trainee = request.trainees().get(index);
			rows.add(new TraineeCsvRow(
					index + 1,
					trainee.name(),
					trainee.email()
			));
		}
		return inviteTrainees(cohortId, rows, actorEmail, requestId);
	}

	private RegisterTraineesResponse inviteTrainees(
			UUID cohortId,
			List<TraineeCsvRow> rows,
			String actorEmail,
			String requestId
	) {
		AuthUser actor = activeActor(actorEmail);
		if (actor.role() != Role.OPERATOR) {
			throw new ApiException(MemberErrorCode.INVITE_ROLE_NOT_ALLOWED, "오퍼레이터만 교육생을 초대할 수 있습니다.");
		}
		InvitationContext context = invitationRepository.findInvitableCohort(cohortId)
				.orElseThrow(() -> new ApiException(MemberErrorCode.COHORT_NOT_INVITABLE));
		if (!context.organizationId().equals(actor.organizationId())) {
			throw new ApiException(MemberErrorCode.INVITE_CROSS_ORGANIZATION, "다른 기관의 기수에는 교육생을 초대할 수 없습니다.");
		}

		int registeredCount = 0;
		int invitationSentCount = 0;
		List<RegisterTraineesResponse.Failure> failures = new ArrayList<>();
		Set<String> duplicateEmails = findDuplicateNormalizedEmails(rows);
		String baseRequestId = requestId(requestId);
		validateTraineeNames(rows);

		for (TraineeCsvRow row : rows) {
			String name = row.name() == null ? "" : row.name().trim();
			String email = row.email() == null ? "" : row.email().trim();
			if (!isValidEmail(email)) {
				failures.add(failure(
						row,
						email,
						TraineeInvitationFailureStatus.INVALID_EMAIL_FORMAT
				));
				continue;
			}

			String normalizedEmail = EmailNormalizer.normalize(email);
			if (duplicateEmails.contains(normalizedEmail)) {
				failures.add(failure(
						row,
						email,
						TraineeInvitationFailureStatus.DUPLICATE_EMAIL_IN_REQUEST
				));
				continue;
			}
			if (invitationRepository.existsOrganizationTraineeByNormalizedEmail(
					context.organizationId(),
					normalizedEmail
			)) {
				failures.add(failure(
						row,
						email,
						TraineeInvitationFailureStatus.EXISTING_ORGANIZATION_TRAINEE_EMAIL
				));
				continue;
			}

			RegisterTraineesRequest.Trainee trainee = new RegisterTraineesRequest.Trainee(
					name,
					email
			);
			try {
				invitationDispatcher.inviteTrainee(
						context,
						trainee,
						actor,
						baseRequestId + ":" + row.row()
				);
				registeredCount++;
			} catch (InvitationConflictException | DataIntegrityViolationException exception) {
				if (invitationRepository.existsOrganizationTraineeByNormalizedEmail(
						context.organizationId(),
						normalizedEmail
				)) {
					failures.add(failure(
							row,
							email,
							TraineeInvitationFailureStatus.EXISTING_ORGANIZATION_TRAINEE_EMAIL
					));
					continue;
				}
				throw exception;
			} catch (InvitationDeliveryException exception) {
				throw new ApiException(MemberErrorCode.INVITE_MAIL_FAILED, exception.getMessage(), exception);
			}

			invitationSentCount++;
		}

		return new RegisterTraineesResponse(
				rows.size(),
				registeredCount,
				invitationSentCount,
				List.copyOf(failures)
		);
	}

	private Set<String> findDuplicateNormalizedEmails(List<TraineeCsvRow> rows) {
		Map<String, Integer> emailCounts = new HashMap<>();
		for (TraineeCsvRow row : rows) {
			String email = row.email() == null ? "" : row.email().trim();
			if (isValidEmail(email)) {
				emailCounts.merge(EmailNormalizer.normalize(email), 1, Integer::sum);
			}
		}

		Set<String> duplicateEmails = new HashSet<>();
		emailCounts.forEach((email, count) -> {
			if (count > 1) {
				duplicateEmails.add(email);
			}
		});
		return duplicateEmails;
	}

	private boolean isValidEmail(String email) {
		return !email.isBlank()
				&& email.length() <= 320
				&& EMAIL_PATTERN.matcher(email).matches();
	}

	private void validateTraineeNames(List<TraineeCsvRow> rows) {
		for (TraineeCsvRow row : rows) {
			String name = row.name() == null ? "" : row.name().trim();
			if (name.isBlank()) {
				throw new ApiException(MemberErrorCode.TRAINEE_NAME_INVALID, row.row() + "행의 이름을 입력해야 합니다.");
			}
			if (name.length() > 200) {
				throw new ApiException(MemberErrorCode.TRAINEE_NAME_INVALID, row.row() + "행의 이름은 200자 이하여야 합니다.");
			}
		}
	}

	private RegisterTraineesResponse.Failure failure(
			TraineeCsvRow row,
			String email,
			TraineeInvitationFailureStatus status
	) {
		return new RegisterTraineesResponse.Failure(row.row(), email, status.code());
	}

	private AuthUser activeActor(String email) {
		AuthUser actor = authUserRepository.findByNormalizedEmail(EmailNormalizer.normalize(email))
				.orElseThrow(() -> new ApiException(MemberErrorCode.INVITER_NOT_FOUND));
		if (!ACTIVE.equals(actor.status()) || !actor.emailVerified()) {
			throw new ApiException(MemberErrorCode.INVITER_NOT_ACTIVE);
		}
		return actor;
	}

	/**
	 * 액터가 <b>그 역할을</b> 초대할 수 있는지 본다. 역할별로 초대 주체가 하나씩 정해져 있다 —
	 * 오퍼레이터는 슈퍼어드민만(기관 부트스트랩), 매니저는 그 기관 오퍼레이터만 만든다.
	 */
	private void validateManagerInvitationAuthority(AuthUser actor, Role targetRole, UUID organizationId) {
		switch (targetRole) {
			case OPERATOR -> {
				if (actor.role() != Role.SUPER_ADMIN) {
					throw new ApiException(MemberErrorCode.INVITE_ROLE_NOT_ALLOWED, "슈퍼어드민만 오퍼레이터를 초대할 수 있습니다.");
				}
			}
			case MANAGER -> {
				if (actor.role() != Role.OPERATOR) {
					throw new ApiException(MemberErrorCode.INVITE_ROLE_NOT_ALLOWED, "오퍼레이터만 매니저를 초대할 수 있습니다.");
				}
				if (!organizationId.equals(actor.organizationId())) {
					throw new ApiException(MemberErrorCode.INVITE_CROSS_ORGANIZATION, "다른 기관의 매니저를 초대할 수 없습니다.");
				}
			}
			default -> throw new ApiException(
					MemberErrorCode.INVITE_ROLE_NOT_ALLOWED,
					"이 경로로 초대할 수 없는 역할입니다: " + targetRole
			);
		}
	}

	private void validateManagerInvitationRequest(InviteManagerRequest request, Role targetRole) {
		if (targetRole == Role.OPERATOR && request.cohortId() != null) {
			throw new ApiException(
					MemberErrorCode.MANAGER_COHORT_REQUIRED,
					"오퍼레이터는 기관 전체를 담당하므로 기수를 배정하지 않습니다."
			);
		}
		if (targetRole == Role.MANAGER && request.cohortId() == null) {
			throw new ApiException(MemberErrorCode.MANAGER_COHORT_REQUIRED);
		}
	}

	private String requestId(String requestId) {
		return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
	}

}
