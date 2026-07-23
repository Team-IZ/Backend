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

	public InviteManagerResponse inviteManager(
			UUID organizationId,
			InviteManagerRequest request,
			String actorEmail,
			String requestId
	) {
		AuthUser actor = activeActor(actorEmail);
		validateManagerInvitationRequest(request);
		validateManagerInvitationAuthority(actor, organizationId, request.role().toRole());
		InvitationContext context = invitationRepository.findActiveOrganization(organizationId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "활성 기관을 찾을 수 없습니다."));

		PendingInvitation invitation;
		try {
			invitation = invitationDispatcher.inviteManager(
					context,
					request,
					actor,
					requestId(requestId)
			);
		} catch (DataIntegrityViolationException exception) {
			throw new InvitationConflictException("이미 등록되었거나 초대된 이메일입니다.");
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
		AuthUser actor = activeActor(actorEmail);
		if (actor.role() != Role.LEAD_MANAGER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "총괄 매니저만 교육생을 초대할 수 있습니다.");
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
						TraineeInvitationFailureStatus.DUPLICATE_EMAIL_IN_CSV
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
					email,
					row.classroomId()
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

	private void validateManagerInvitationAuthority(AuthUser actor, UUID organizationId, Role invitedRole) {
		if (actor.role() == Role.SUPER_ADMIN && invitedRole == Role.LEAD_MANAGER) {
			return;
		}
		if (actor.role() == Role.LEAD_MANAGER && invitedRole == Role.MANAGER) {
			if (!organizationId.equals(actor.organizationId())) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 기관의 매니저를 초대할 수 없습니다.");
			}
			return;
		}
		throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 역할의 매니저를 초대할 권한이 없습니다.");
	}

	private void validateManagerInvitationRequest(InviteManagerRequest request) {
		if (request.role().toRole() == Role.LEAD_MANAGER
				&& (request.cohortId() != null || !request.classroomIds().isEmpty())) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST,
					"총괄 매니저는 기관 전체를 담당하므로 기수·반을 배정하지 않습니다."
			);
		}
		if (request.role().toRole() == Role.MANAGER && request.cohortId() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "일반 매니저는 하나의 기수를 반드시 지정해야 합니다.");
		}
	}

	private String requestId(String requestId) {
		return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
	}

}
