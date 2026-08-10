package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.member.domain.TraineeRosterSort;
import com.bigproject.backend.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TraineeRosterService {

	/** ck_app_user_inactivated_reason_code 값 중 하나. 화면에서 오는 정지는 전부 운영자 조치다. */
	private static final String INACTIVATE_REASON_CODE = "ADMIN_SUSPENDED";
	private static final String RAW_PENDING = "PENDING";
	private static final String RAW_ACTIVE = "ACTIVE";
	private static final String RAW_INACTIVE = "INACTIVE";

	private final TraineeRosterRepository traineeRosterRepository;
	private final com.bigproject.backend.domain.auth.domain.PasswordResetRepository accountRepository;
	private final com.bigproject.backend.domain.auth.application.InvitationResendDispatcher resendDispatcher;

	/**
	 * 교육생 초대 재발송(11차 R2). 명단에서 고른 여러 명을 <b>한 번에</b> 받는다.
	 *
	 * <p>지금까지는 교육생만 받는 사람용 {@code POST /auth/invitations/resend}를 써야 했다.
	 * 그쪽은 계정 존재 여부를 숨기려 <b>항상 같은 202</b>를 주므로 오퍼레이터가 눌러도 나갔는지 알 수 없고,
	 * 이메일 하나씩만 받아 고른 사람 수만큼 호출이 나갔다. 이 경로는 오퍼레이터 인증을 거치므로
	 * 숨길 것이 없어 <b>실제로 나간 수</b>를 답한다.
	 *
	 * <p>행별 부분 성공을 허용한다 — 20명 중 하나가 이미 활성이라고 나머지 19명을 막을 이유가 없다.
	 *
	 * @return 요청 수·실제 발송 수·실패한 행
	 */
	@Transactional
	public InvitationResendResult resendInvitations(
			UUID cohortId, UUID orgId, java.util.List<UUID> traineeIds, String requestId) {

		verifyCohortScope(cohortId, orgId);

		java.util.List<InvitationResendFailure> failures = new java.util.ArrayList<>();
		int sentCount = 0;

		for (UUID traineeId : traineeIds) {
			TraineeRosterRepository.RosterRow row =
					traineeRosterRepository.findTrainee(traineeId, cohortId, orgId).orElse(null);
			if (row == null) {
				failures.add(new InvitationResendFailure(traineeId, null, InvitationResendStatus.NOT_FOUND));
				continue;
			}
			// 이미 활성화된 계정에는 보낼 초대가 없다. 대기 토큰이 있는지로 판정한다 —
			// status만 보면 초대가 취소돼 토큰이 없는 계정까지 대상이 된다.
			if (!RAW_PENDING.equals(row.rawAccountStatus()) || row.pendingInvitationTokenId() == null) {
				failures.add(new InvitationResendFailure(traineeId, row.email(), InvitationResendStatus.NOT_PENDING));
				continue;
			}

			var account = accountRepository.findAccountByNormalizedEmail(row.email().trim().toLowerCase(java.util.Locale.ROOT))
					.orElse(null);
			if (account == null || account.invitationId() == null) {
				failures.add(new InvitationResendFailure(traineeId, row.email(), InvitationResendStatus.NO_INVITATION));
				continue;
			}

			resendDispatcher.resend(account, requestId);
			sentCount++;
		}

		return new InvitationResendResult(traineeIds.size(), sentCount, List.copyOf(failures));
	}

	/**
	 * @param requestedCount      요청에 담긴 교육생 수
	 * @param invitationSentCount <b>실제로 메일이 나간 수</b>. {@code registerTrainees}의 같은 이름 필드와 같은 뜻이다
	 */
	public record InvitationResendResult(
			int requestedCount,
			int invitationSentCount,
			java.util.List<InvitationResendFailure> failures) {
	}

	public record InvitationResendFailure(UUID traineeId, String email, InvitationResendStatus status) {
	}

	/** 재발송이 걸린 이유. 화면이 행마다 다른 문구를 붙일 수 있어야 한다. */
	public enum InvitationResendStatus {
		/** 이 기수에 없는 교육생이다. 명단이 낡았다는 뜻이라 화면은 목록을 다시 읽어야 한다. */
		NOT_FOUND,
		/** 이미 활성화됐거나 초대가 취소돼 보낼 초대가 없다. */
		NOT_PENDING,
		/** 초대 원장이 없다. 계정만 만들어지고 초대가 발행되지 않은 상태다. */
		NO_INVITATION
	}

	public RosterResult findRoster(
			UUID cohortId,
			UUID orgId,
			UUID classroomId,
			boolean unassignedOnly,
			AccountStatus accountStatus,
			String query,
			TraineeRosterSort sort,
			Pageable pageable
	) {
		verifyCohortScope(cohortId, orgId);
		if (classroomId != null && unassignedOnly) {
			throw new ApiException(MemberErrorCode.ROSTER_FILTER_CONFLICT);
		}

		TraineeRosterRepository.RosterCriteria criteria = new TraineeRosterRepository.RosterCriteria(
				cohortId,
				orgId,
				classroomId,
				unassignedOnly,
				toRawStatus(accountStatus),
				query,
				sort == null ? TraineeRosterSort.NAME : sort
		);

		Page<TraineeRosterRepository.RosterRow> page = traineeRosterRepository.findRoster(criteria, pageable);
		int unassignedCount = traineeRosterRepository.countUnassigned(cohortId, orgId);
		int cohortTotal = traineeRosterRepository.countCohortTotal(cohortId, orgId);
		return new RosterResult(page, unassignedCount, cohortTotal);
	}

	@Transactional
	public TraineeRosterRepository.RosterRow updateStatus(
			UUID cohortId, UUID orgId, UUID traineeId, AccountStatus status, String reason, UUID actorUserId
	) {
		verifyCohortScope(cohortId, orgId);
		TraineeRosterRepository.RosterRow current = traineeRosterRepository
				.findTrainee(traineeId, cohortId, orgId)
				.orElseThrow(() -> new ApiException(MemberErrorCode.TRAINEE_NOT_FOUND));

		if (RAW_PENDING.equals(current.rawAccountStatus())) {
			throw new ApiException(MemberErrorCode.TRAINEE_STATUS_NOT_MUTABLE);
		}

		String targetRawStatus = status == AccountStatus.INACTIVE ? RAW_INACTIVE : RAW_ACTIVE;
		if (!targetRawStatus.equals(current.rawAccountStatus())) {
			boolean inactivating = RAW_INACTIVE.equals(targetRawStatus);
			traineeRosterRepository.updateStatus(
					traineeId,
					targetRawStatus,
					actorUserId,
					inactivating ? INACTIVATE_REASON_CODE : null,
					reason
			);
			// 화면이 '계정 비활성'과 '중도 이탈 {날짜}'를 한 행에 함께 보여주므로 기수 소속도 같이 움직인다.
			traineeRosterRepository.updateCohortMembership(traineeId, cohortId, orgId, inactivating);
		}

		return traineeRosterRepository.findTrainee(traineeId, cohortId, orgId).orElseThrow();
	}

	private void verifyCohortScope(UUID cohortId, UUID orgId) {
		TraineeRosterRepository.CohortScope scope = traineeRosterRepository.findCohortScope(cohortId)
				.orElseThrow(() -> new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND));
		if (!scope.orgId().equals(orgId)) {
			// 다른 기관의 기수인지 여부를 노출하지 않기 위해 403이 아니라 404로 응답한다(CohortController와 동일 정책).
			throw new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND);
		}
	}

	/**
	 * 화면 용어를 {@code app_user.status} 원문으로 옮긴다.
	 *
	 * <p>{@code LOCKED} 분기는 9차 Q3-②로 사라졌다 — enum에서 값을 빼서 <b>애초에 들어올 수 없게</b> 했다.
	 */
	private String toRawStatus(AccountStatus status) {
		if (status == null) {
			return null;
		}
		return switch (status) {
			case INVITED -> RAW_PENDING;
			case ACTIVE -> RAW_ACTIVE;
			case INACTIVE -> RAW_INACTIVE;
		};
	}

	/** 한 페이지와, 그 페이지의 필터와 무관한 기수 전체 기준 집계 둘. */
	public record RosterResult(
			Page<TraineeRosterRepository.RosterRow> page,
			int unassignedCount,
			int cohortTotal
	) {
	}
}
