package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.member.domain.TraineeRosterRepository;
import com.bigproject.backend.domain.member.domain.TraineeRosterSort;
import com.bigproject.backend.global.exception.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraineeRosterServiceTest {

	private final TraineeRosterRepository traineeRosterRepository = mock(TraineeRosterRepository.class);
	// 초대 재발송(11차 R2)에 쓰는 두 협력자. 이 테스트가 보는 경로에서는 호출되지 않는다.
	private final com.bigproject.backend.domain.auth.domain.PasswordResetRepository accountRepository =
			mock(com.bigproject.backend.domain.auth.domain.PasswordResetRepository.class);
	private final com.bigproject.backend.domain.auth.application.InvitationResendDispatcher resendDispatcher =
			mock(com.bigproject.backend.domain.auth.application.InvitationResendDispatcher.class);
	private final TraineeRosterService service =
			new TraineeRosterService(traineeRosterRepository, accountRepository, resendDispatcher);

	private final UUID cohortId = UUID.randomUUID();
	private final UUID orgId = UUID.randomUUID();
	private final UUID traineeId = UUID.randomUUID();
	private final UUID actorUserId = UUID.randomUUID();

	@BeforeEach
	void givenCohortInScope() {
		when(traineeRosterRepository.findCohortScope(cohortId))
				.thenReturn(Optional.of(new TraineeRosterRepository.CohortScope(cohortId, orgId)));
	}

	@Test
	void rejectsAnUnknownCohort() {
		when(traineeRosterRepository.findCohortScope(cohortId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findRoster(
				cohortId, orgId, null, false, null, null, TraineeRosterSort.NAME, PageRequest.of(0, 20)))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AcademicOperationsErrorCode.COHORT_NOT_FOUND));
	}

	@Test
	void rejectsACohortFromAnotherOrganization() {
		UUID otherOrgId = UUID.randomUUID();

		assertThatThrownBy(() -> service.findRoster(
				cohortId, otherOrgId, null, false, null, null, TraineeRosterSort.NAME, PageRequest.of(0, 20)))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(AcademicOperationsErrorCode.COHORT_NOT_FOUND));
	}

	@Test
	void rejectsClassroomIdCombinedWithUnassignedOnly() {
		assertThatThrownBy(() -> service.findRoster(
				cohortId, orgId, UUID.randomUUID(), true, null, null, TraineeRosterSort.NAME,
				PageRequest.of(0, 20)))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.ROSTER_FILTER_CONFLICT));
	}

	/** 9차 Q3-② — 명단 필터도 같은 {@code AccountStatus}를 쓰므로 세 값뿐이다. */
	@Test
	void offersOnlyTheThreeAccountStatusesThatCanActuallyOccur() {
		assertThat(AccountStatus.values())
				.containsExactly(AccountStatus.INVITED, AccountStatus.ACTIVE, AccountStatus.INACTIVE);
	}

	@Test
	void translatesInvitedFilterToTheRawPendingDbValue() {
		Page<TraineeRosterRepository.RosterRow> emptyPage = new PageImpl<>(List.of());
		when(traineeRosterRepository.findRoster(any(), any())).thenReturn(emptyPage);
		when(traineeRosterRepository.countUnassigned(cohortId, orgId)).thenReturn(0);

		service.findRoster(cohortId, orgId, null, false, AccountStatus.INVITED, null, TraineeRosterSort.NAME,
				PageRequest.of(0, 20));

		verify(traineeRosterRepository).findRoster(
				eq(new TraineeRosterRepository.RosterCriteria(
						cohortId, orgId, null, false, "PENDING", null, TraineeRosterSort.NAME)),
				any());
	}

	@Test
	void reportsTheUnassignedCountAlongsideThePage() {
		Page<TraineeRosterRepository.RosterRow> page = new PageImpl<>(List.of());
		when(traineeRosterRepository.findRoster(any(), any())).thenReturn(page);
		when(traineeRosterRepository.countUnassigned(cohortId, orgId)).thenReturn(3);

		TraineeRosterService.RosterResult result = service.findRoster(
				cohortId, orgId, null, false, null, null, TraineeRosterSort.NAME, PageRequest.of(0, 20));

		assertThat(result.unassignedCount()).isEqualTo(3);
	}

	@Test
	void rejectsChangingTheStatusOfAnInvitedTrainee() {
		TraineeRosterRepository.RosterRow pendingTrainee = rosterRow("PENDING");
		when(traineeRosterRepository.findTrainee(traineeId, cohortId, orgId))
				.thenReturn(Optional.of(pendingTrainee));

		assertThatThrownBy(() -> service.updateStatus(
				cohortId, orgId, traineeId, AccountStatus.INACTIVE, "사유", actorUserId))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.TRAINEE_STATUS_NOT_MUTABLE));

		verify(traineeRosterRepository, never()).updateStatus(any(), any(), any(), any(), any());
	}

	@Test
	void rejectsAnUnknownTrainee() {
		when(traineeRosterRepository.findTrainee(traineeId, cohortId, orgId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.updateStatus(
				cohortId, orgId, traineeId, AccountStatus.INACTIVE, null, actorUserId))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.TRAINEE_NOT_FOUND));
	}

	@Test
	void suspendsAnActiveTraineeWithTheAdminSuspendedReasonCode() {
		TraineeRosterRepository.RosterRow activeTrainee = rosterRow("ACTIVE");
		when(traineeRosterRepository.findTrainee(traineeId, cohortId, orgId))
				.thenReturn(Optional.of(activeTrainee))
				.thenReturn(Optional.of(rosterRow("INACTIVE")));

		service.updateStatus(cohortId, orgId, traineeId, AccountStatus.INACTIVE, "중도 이탈", actorUserId);

		verify(traineeRosterRepository).updateStatus(
				traineeId, "INACTIVE", actorUserId, "ADMIN_SUSPENDED", "중도 이탈");
	}

	/** 화면이 '계정 비활성'과 '중도 이탈 {날짜}'를 한 행에 보여주므로 기수 소속도 함께 이탈 처리된다. */
	@Test
	void marksTheCohortMembershipAsLeftWhenSuspending() {
		when(traineeRosterRepository.findTrainee(traineeId, cohortId, orgId))
				.thenReturn(Optional.of(rosterRow("ACTIVE")))
				.thenReturn(Optional.of(rosterRow("INACTIVE")));

		service.updateStatus(cohortId, orgId, traineeId, AccountStatus.INACTIVE, "중도 이탈", actorUserId);

		verify(traineeRosterRepository).updateCohortMembership(traineeId, cohortId, orgId, true);
	}

	/** ACTIVE면 left_at IS NULL이어야 하므로 재활성화는 이탈 표시를 되돌린다. */
	@Test
	void clearsTheLeftMarkWhenReactivating() {
		when(traineeRosterRepository.findTrainee(traineeId, cohortId, orgId))
				.thenReturn(Optional.of(rosterRow("INACTIVE")))
				.thenReturn(Optional.of(rosterRow("ACTIVE")));

		service.updateStatus(cohortId, orgId, traineeId, AccountStatus.ACTIVE, null, actorUserId);

		verify(traineeRosterRepository).updateCohortMembership(traineeId, cohortId, orgId, false);
	}

	@Test
	void isIdempotentWhenTheStatusAlreadyMatches() {
		TraineeRosterRepository.RosterRow alreadyActive = rosterRow("ACTIVE");
		when(traineeRosterRepository.findTrainee(traineeId, cohortId, orgId))
				.thenReturn(Optional.of(alreadyActive));

		service.updateStatus(cohortId, orgId, traineeId, AccountStatus.ACTIVE, null, actorUserId);

		verify(traineeRosterRepository, never()).updateStatus(any(), any(), any(), any(), any());
		// left_at을 다시 찍지 않아야 최초 이탈 시각이 보존된다.
		verify(traineeRosterRepository, never()).updateCohortMembership(any(), any(), any(), anyBoolean());
		verify(traineeRosterRepository, times(2)).findTrainee(traineeId, cohortId, orgId);
	}

	private TraineeRosterRepository.RosterRow rosterRow(String rawStatus) {
		boolean inactive = "INACTIVE".equals(rawStatus);
		return new TraineeRosterRepository.RosterRow(
				traineeId, "교육생", "trainee@example.com", rawStatus,
				null, null, OffsetDateTime.now(), inactive ? OffsetDateTime.now() : null,
				inactive ? "ADMIN_SUSPENDED" : null, null, inactive ? OffsetDateTime.now() : null,
				inactive ? actorUserId : null, inactive ? "김오퍼레이터" : null,
				// 대기 중 초대 토큰(11차 R2). 활성화 전(PENDING)인 계정에만 있다.
				"PENDING".equals(rawStatus) ? UUID.randomUUID() : null);
	}
}
