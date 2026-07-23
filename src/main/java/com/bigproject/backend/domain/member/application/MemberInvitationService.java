package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.InvitationContext;
import com.bigproject.backend.domain.member.domain.MemberInvitationRepository;
import com.bigproject.backend.domain.member.domain.PendingInvitation;
import com.bigproject.backend.domain.member.domain.Role;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemberInvitationService {
	private static final String ACTIVE = "ACTIVE";

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

	public RegisterTraineesResponse inviteTrainees(
			UUID cohortId,
			RegisterTraineesRequest request,
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
		Set<String> requestedEmails = new HashSet<>();
		String baseRequestId = requestId(requestId);

		for (int index = 0; index < request.trainees().size(); index++) {
			int row = index + 1;
			RegisterTraineesRequest.Trainee trainee = request.trainees().get(index);
			String normalizedEmail = EmailNormalizer.normalize(trainee.email());
			if (!requestedEmails.add(normalizedEmail)) {
				failures.add(new RegisterTraineesResponse.Failure(row, trainee.email(), "요청에 중복된 이메일입니다."));
				continue;
			}

			try {
				invitationDispatcher.inviteTrainee(
						context,
						trainee,
						actor,
						baseRequestId + ":" + row
				);
				registeredCount++;
			} catch (RuntimeException exception) {
				failures.add(new RegisterTraineesResponse.Failure(row, trainee.email(), failureReason(exception)));
				continue;
			}

			invitationSentCount++;
		}

		return new RegisterTraineesResponse(
				request.trainees().size(),
				registeredCount,
				invitationSentCount,
				List.copyOf(failures)
		);
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

	private String failureReason(RuntimeException exception) {
		if (exception instanceof InvitationConflictException) {
			return exception.getMessage();
		}
		if (exception instanceof InvitationDeliveryException) {
			return exception.getMessage();
		}
		if (exception instanceof ResponseStatusException responseStatusException
				&& responseStatusException.getReason() != null) {
			return responseStatusException.getReason();
		}
		if (exception instanceof DataIntegrityViolationException) {
			return "이미 등록되었거나 초대된 이메일입니다.";
		}
		return "교육생 초대 정보를 저장할 수 없습니다.";
	}
}
