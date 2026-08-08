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
			traineeRosterRepository.updateStatus(
					traineeId,
					targetRawStatus,
					actorUserId,
					RAW_INACTIVE.equals(targetRawStatus) ? INACTIVATE_REASON_CODE : null,
					reason
			);
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

	private String toRawStatus(AccountStatus status) {
		if (status == null) {
			return null;
		}
		return switch (status) {
			case INVITED -> RAW_PENDING;
			case ACTIVE -> RAW_ACTIVE;
			case INACTIVE -> RAW_INACTIVE;
			case LOCKED -> throw new ApiException(
					MemberErrorCode.ACCOUNT_STATUS_FILTER_NOT_SUPPORTED, "LOCKED는 계정 상태 필터로 지원하지 않습니다.");
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
