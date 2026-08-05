package com.bigproject.backend.domain.member.application;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
	public InviteManagerResponse inviteSuperAdmin(String email, String actorEmail, String requestId) {
		AuthUser actor = activeActor(actorEmail);
		if (actor.role() != Role.SUPER_ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "슈퍼어드민만 슈퍼어드민을 초대할 수 있습니다.");
		}
		String trimmedEmail = email == null ? "" : email.trim();
		if (!isValidEmail(trimmedEmail)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일 형식이 올바르지 않습니다.");
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
			throw new ResponseStatusException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"초대 정보를 저장할 수 없습니다.",
					exception
			);
		} catch (InvitationDeliveryException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
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
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "활성 기관을 찾을 수 없습니다."));

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
			throw new ResponseStatusException(
					HttpStatus.INTERNAL_SERVER_ERROR,
					"초대 정보를 저장할 수 없습니다.",
					exception
			);
		} catch (InvitationDeliveryException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
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
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "오퍼레이터만 교육생을 초대할 수 있습니다.");
		}
		InvitationContext context = invitationRepository.findInvitableCohort(cohortId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "초대 가능한 기수를 찾을 수 없습니다."));
		if (!context.organizationId().equals(actor.organizationId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 기관의 기수에는 교육생을 초대할 수 없습니다.");
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
				throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
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
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, row.row() + "행의 이름을 입력해야 합니다.");
			}
			if (name.length() > 200) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, row.row() + "행의 이름은 200자 이하여야 합니다.");
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
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 사용자를 찾을 수 없습니다."));
		if (!ACTIVE.equals(actor.status()) || !actor.emailVerified()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 사용자만 초대할 수 있습니다.");
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
					throw new ResponseStatusException(HttpStatus.FORBIDDEN, "슈퍼어드민만 오퍼레이터를 초대할 수 있습니다.");
				}
			}
			case MANAGER -> {
				if (actor.role() != Role.OPERATOR) {
					throw new ResponseStatusException(HttpStatus.FORBIDDEN, "오퍼레이터만 매니저를 초대할 수 있습니다.");
				}
				if (!organizationId.equals(actor.organizationId())) {
					throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 기관의 매니저를 초대할 수 없습니다.");
				}
			}
			default -> throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"이 경로로 초대할 수 없는 역할입니다: " + targetRole
			);
		}
	}

	private void validateManagerInvitationRequest(InviteManagerRequest request, Role targetRole) {
		if (targetRole == Role.OPERATOR && request.cohortId() != null) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"오퍼레이터는 기관 전체를 담당하므로 기수를 배정하지 않습니다."
			);
		}
		if (targetRole == Role.MANAGER && request.cohortId() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "일반 매니저는 하나의 기수를 반드시 지정해야 합니다.");
		}
	}

	private String requestId(String requestId) {
		return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
	}

}
