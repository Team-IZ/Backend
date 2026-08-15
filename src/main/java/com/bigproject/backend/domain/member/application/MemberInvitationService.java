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
import com.bigproject.backend.domain.member.presentation.dto.PreviewTraineesResponse;
import com.bigproject.backend.domain.member.presentation.dto.RegisterTraineesResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
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
@Slf4j
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
	private final AsyncTraineeInvitationMailer asyncMailer;

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
			String actorEmail
	) {
		return inviteTrainees(cohortId, rows, actorEmail);
	}

	public RegisterTraineesResponse inviteTrainees(
			UUID cohortId,
			RegisterTraineesRequest request,
			String actorEmail
	) {
		return inviteTrainees(cohortId, toRows(request), actorEmail);
	}

	/** 직접 입력 본문을 행 목록으로. 순번은 1부터이며 CSV와 달리 헤더가 없다. */
	private static List<TraineeCsvRow> toRows(RegisterTraineesRequest request) {
		List<TraineeCsvRow> rows = new ArrayList<>(request.trainees().size());
		for (int index = 0; index < request.trainees().size(); index++) {
			RegisterTraineesRequest.Trainee trainee = request.trainees().get(index);
			rows.add(new TraineeCsvRow(index + 1, trainee.name(), trainee.email()));
		}
		return rows;
	}

	/**
	 * <b>배치 식별자는 서버가 만든다.</b> 예전에는 {@code X-Request-Id} 헤더를 받아 그대로 썼는데,
	 * 이 값이 개선 D에서 폴링의 잡 ID가 되면서 클라이언트가 정할 값이 아니게 됐다 —
	 * 같은 값으로 두 번 등록하면 두 등록의 진행률이 하나로 합쳐지고, {@code /}가 들어가면 폴링 경로가
	 * 조각나 404가 된다. 서버가 UUID를 만들어 응답에 담으면 두 함정이 다 사라진다.
	 *
	 * <p>추적을 잃지는 않는다 — 이 값은 그대로 {@code one_time_token.issued_request_id}와
	 * {@code user_invitation.batch_request_id}에 남고, 화면은 응답의 {@code batchRequestId}로 되짚는다.
	 */
	private RegisterTraineesResponse inviteTrainees(
			UUID cohortId,
			List<TraineeCsvRow> rows,
			String actorEmail
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

		List<RegisterTraineesResponse.Failure> failures = new ArrayList<>();
		Set<String> duplicateEmails = findDuplicateNormalizedEmails(rows);
		Set<String> existingEmails = findExistingTraineeEmails(context.organizationId(), rows);
		String baseRequestId = UUID.randomUUID().toString();
		validateTraineeNames(rows);

		/*
		 * 1단계 — 자리를 전량 확보한다. 메일은 여기서 보내지 않는다.
		 *
		 * 예전에는 행마다 "자리 확보 → SMTP 발송 → 결과 기록"을 다 끝내고 다음 행으로 넘어갔다.
		 * SMTP 연결 고정비(연결·핸드셰이크·AUTH)가 건당 1초를 넘어 900명이면 그것만 약 28분이었고,
		 * 게이트웨이 응답 한도를 넘겨 화면에는 502가 뜨는데 뒤에서는 등록이 계속되는 상태가 됐다.
		 * 발송을 2단계로 미루면 그 고정비를 연결당 한 번만 낸다.
		 */
		Map<Integer, TraineeCsvRow> rowsByNumber = new HashMap<>();
		List<InvitationPersistenceService.TraineeSlotRequest> slotRequests = new ArrayList<>();
		for (TraineeCsvRow row : rows) {
			String name = row.name() == null ? "" : row.name().trim();
			String email = row.email() == null ? "" : row.email().trim();

			// 행별 판정은 미리보기(previewTrainees)와 같은 메서드를 쓴다 —
			// 규칙이 두 벌이면 "미리보기는 통과했는데 등록이 실패"하는 상태가 생긴다(9차 Q3-③).
			TraineeInvitationFailureStatus rejection =
					rejectionFor(email, duplicateEmails, existingEmails);
			if (rejection != null) {
				failures.add(failure(row, email, rejection));
				continue;
			}
			rowsByNumber.put(row.row(), row);
			slotRequests.add(new InvitationPersistenceService.TraineeSlotRequest(row.row(), name, email));
		}

		// 확보할 행이 하나도 없으면(전부 사전 판정에서 걸림) 확보·발송 단계를 건너뛴다.
		// 폴링할 대상도 없으므로 batchRequestId를 돌려주지 않는다 — 조회해 봐야 404다.
		if (slotRequests.isEmpty()) {
			return new RegisterTraineesResponse(rows.size(), 0, 0, null, List.copyOf(failures));
		}

		List<ReservedTrainee> reserved = new ArrayList<>();
		for (InvitationPersistenceService.TraineeSlot slot : invitationDispatcher.reserveTrainees(
				context, slotRequests, actor, baseRequestId)) {
			TraineeCsvRow row = rowsByNumber.get(slot.request().row());
			if (slot.isConflict()) {
				/*
				 * 사전 판정을 통과했는데 확보 단계에서 걸린 행이다. 두 호출 사이에 다른 운영자가 같은
				 * 주소를 등록했거나, 교육생이 아닌 계정(다른 역할·다른 기관)이 그 이메일을 쓰고 있다.
				 * 어느 쪽인지 확인해 앞의 것만 행별 실패로 돌려주고, 뒤는 명단 문제가 아니라 예외로 올린다.
				 */
				if (invitationRepository.existsOrganizationTraineeByNormalizedEmail(
						context.organizationId(),
						EmailNormalizer.normalize(slot.request().email())
				)) {
					failures.add(failure(
							row,
							slot.request().email(),
							TraineeInvitationFailureStatus.EXISTING_ORGANIZATION_TRAINEE_EMAIL
					));
					continue;
				}
				throw new InvitationConflictException("이미 등록되었거나 초대된 이메일입니다.");
			}
			reserved.add(new ReservedTrainee(slot.invitation(), slot.request().name()));
		}

		/*
		 * 2단계 — 발송은 요청 스레드 밖으로 넘긴다.
		 *
		 * 발송까지 여기서 끝내면 900명이 약 3분이라 게이트웨이의 "응답 첫 바이트까지" 상한을 넘어
		 * 화면에는 502가 뜨는데 뒤에서는 등록이 계속되는 상태가 된다. 자리는 이미 커밋됐으므로
		 * 여기서 응답을 끊어도 잃는 것이 없고, 진행률은 화면이 batchRequestId로 폴링해 따라온다.
		 *
		 * 발송 실패는 예외가 아니다. "등록은 됐지만 메일은 안 나간 행"은 원장에 DELIVERY_FAILED로 남아
		 * 폴링 응답의 mailFailedCount로 드러나고, 명단 화면의 [초대 재발송]이 켜진다.
		 */
		try {
			asyncMailer.sendTraineeInvitations(
					baseRequestId,
					reserved.stream()
							.map(item -> new InvitationMailSender.TraineeInvitationMail(item.invitation(), item.name()))
							.toList()
			);
		} catch (TaskRejectedException exception) {
			/*
			 * 발송 큐가 찼다. 등록 자체는 실패시키지 않는다 — 자리와 초대 원장은 이미 커밋됐고,
			 * 그 행들은 PENDING으로 남아 안전망 스케줄러가 토큰을 새로 발급해 이어받는다.
			 * 응답의 invitationSentCount가 0인 것도 정상 경로와 다르지 않다.
			 */
			log.warn("교육생 초대 메일 발송을 큐에 넣지 못했다. 안전망이 이어받는다: batchRequestId={}, count={}",
					baseRequestId, reserved.size(), exception);
		}

		/*
		 * invitationSentCount는 이 시점에 항상 0이다 — 아직 한 통도 나가지 않았다.
		 * 실제로 나간 수는 폴링 응답에서 답한다. 필드를 지우지 않은 것은 미리보기·기존 화면이
		 * 같은 스키마를 그리기 때문이다.
		 */
		return new RegisterTraineesResponse(
				rows.size(),
				reserved.size(),
				0,
				baseRequestId,
				List.copyOf(failures)
		);
	}

	/**
	 * 일괄 등록 한 건의 진행률(개선 D의 폴링). 잡 레코드가 따로 없고 {@code batch_request_id}가 같은
	 * 초대 원장 행들이 곧 잡이라, 어느 인스턴스가 받아도 같은 집계를 답한다.
	 *
	 * <p>범위는 <b>기수·기관</b>으로 좁힌다. 남의 배치 식별자를 찍어 넣어도 남의 진행률이 보이면 안 된다.
	 */
	public TraineeRegistrationProgress findRegistrationProgress(
			UUID cohortId, UUID organizationId, String batchRequestId) {
		return invitationRepository.findBatchProgress(batchRequestId, organizationId, cohortId)
				.map(TraineeRegistrationProgress::from)
				.orElseThrow(() -> new ApiException(MemberErrorCode.REGISTRATION_BATCH_NOT_FOUND));
	}

	/** 자리 확보까지 끝난 행. 이름은 메일 본문 인사말에 쓰이며 {@link PendingInvitation}에는 없다. */
	private record ReservedTrainee(PendingInvitation invitation, String name) {
	}

	/**
	 * 명단 등록 <b>사전 검증</b>(9차 Q3-③). 아무것도 만들지 않고 무엇이 걸리는지만 돌려준다.
	 *
	 * <p>200명을 붙여 넣고 나서야 30명이 중복이라는 걸 알게 되던 것을 없앤다. 형식 오류·기관 도메인 밖
	 * 주소는 화면이 그 자리에서 걸러낼 수 있지만, <b>이미 등록된 이메일은 명단 전량을 받아야 셀 수 있어
	 * 서버여야 한다</b>.
	 *
	 * <p>판정은 {@link #rejectionFor}로 등록 경로와 <b>같은 메서드</b>를 쓴다 — 규칙이 두 벌이면
	 * "미리보기는 통과했는데 등록이 실패"하는 상태가 생긴다.
	 *
	 * <p><b>미리보기가 통과했다고 등록이 반드시 성공하지는 않는다.</b> 두 호출 사이에 다른 운영자가
	 * 같은 주소를 등록할 수 있다. 등록 응답의 {@code failures}를 그대로 두는 이유다.
	 *
	 * <p>응답은 {@link PreviewTraineesResponse}로 등록과 <b>갈라 놓는다</b>(31차 R2) — 판정이 같은
	 * 메서드라고 해서 결과의 이름까지 같아도 되는 것은 아니다.
	 */
	public PreviewTraineesResponse previewTrainees(
			UUID cohortId, RegisterTraineesRequest request, String actorEmail) {
		return previewTrainees(cohortId, toRows(request), actorEmail);
	}

	/** @see #previewTrainees(UUID, RegisterTraineesRequest, String) */
	public PreviewTraineesResponse previewTrainees(UUID cohortId, List<TraineeCsvRow> rows, String actorEmail) {
		AuthUser actor = activeActor(actorEmail);
		if (actor.role() != Role.OPERATOR) {
			throw new ApiException(MemberErrorCode.INVITE_ROLE_NOT_ALLOWED, "오퍼레이터만 교육생을 초대할 수 있습니다.");
		}
		InvitationContext context = invitationRepository.findInvitableCohort(cohortId)
				.orElseThrow(() -> new ApiException(MemberErrorCode.COHORT_NOT_INVITABLE));
		if (!context.organizationId().equals(actor.organizationId())) {
			throw new ApiException(MemberErrorCode.INVITE_CROSS_ORGANIZATION, "다른 기관의 기수에는 교육생을 초대할 수 없습니다.");
		}

		// 이름 검사는 등록과 같게 요청 전체를 400으로 거절한다 — 미리보기에서만 통과시키면
		// "미리보기는 됐는데 등록이 400"이 되어 미리보기의 뜻이 없어진다.
		validateTraineeNames(rows);

		Set<String> duplicateEmails = findDuplicateNormalizedEmails(rows);
		Set<String> existingEmails = findExistingTraineeEmails(context.organizationId(), rows);
		List<RegisterTraineesResponse.Failure> failures = new ArrayList<>();
		for (TraineeCsvRow row : rows) {
			String email = row.email() == null ? "" : row.email().trim();
			TraineeInvitationFailureStatus rejection =
					rejectionFor(email, duplicateEmails, existingEmails);
			if (rejection != null) {
				failures.add(failure(row, email, rejection));
			}
		}

		// 아무것도 만들지 않았으므로 수는 전부 "그렇게 될 것"이다 — 이름이 registrableCount인 이유다.
		return new PreviewTraineesResponse(
				rows.size(),
				rows.size() - failures.size(),
				List.copyOf(failures)
		);
	}

	/**
	 * 행 하나가 걸리는 사유. 통과하면 {@code null}이다.
	 * 등록과 미리보기가 <b>이 한 메서드</b>를 공유하므로 두 응답이 서로 다른 말을 할 수 없다.
	 *
	 * <p>기존 이메일 집합을 <b>인자로 받는다.</b> 예전에는 행마다
	 * {@code existsOrganizationTraineeByNormalizedEmail}을 불러 900명 명단이 900 왕복이 됐다.
	 * 판정에 필요한 것은 "이 중 어느 것이 이미 있는가"이므로 {@link #findExistingTraineeEmails}가
	 * 한 번에 조회해 넘긴다.
	 */
	private TraineeInvitationFailureStatus rejectionFor(
			String email, Set<String> duplicateEmails, Set<String> existingEmails) {
		if (!isValidEmail(email)) {
			return TraineeInvitationFailureStatus.INVALID_EMAIL_FORMAT;
		}
		String normalizedEmail = EmailNormalizer.normalize(email);
		if (duplicateEmails.contains(normalizedEmail)) {
			return TraineeInvitationFailureStatus.DUPLICATE_EMAIL_IN_REQUEST;
		}
		if (existingEmails.contains(normalizedEmail)) {
			return TraineeInvitationFailureStatus.EXISTING_ORGANIZATION_TRAINEE_EMAIL;
		}
		return null;
	}

	/**
	 * 명단 전량 중 그 기관에 이미 있는 교육생 이메일을 <b>왕복 한 번으로</b> 모아 온다.
	 *
	 * <p>형식이 틀린 주소는 물어볼 필요가 없어 미리 걸러낸다 — 어차피 형식 오류로 먼저 판정된다.
	 */
	private Set<String> findExistingTraineeEmails(UUID organizationId, List<TraineeCsvRow> rows) {
		Set<String> candidates = new HashSet<>();
		for (TraineeCsvRow row : rows) {
			String email = row.email() == null ? "" : row.email().trim();
			if (isValidEmail(email)) {
				candidates.add(EmailNormalizer.normalize(email));
			}
		}
		return invitationRepository.findExistingOrganizationTraineeEmails(organizationId, candidates);
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
