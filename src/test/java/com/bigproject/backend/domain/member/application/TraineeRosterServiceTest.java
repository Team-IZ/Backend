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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraineeRosterServiceTest {

	private final TraineeRosterRepository traineeRosterRepository = mock(TraineeRosterRepository.class);
	private final TraineeRosterService service = new TraineeRosterService(traineeRosterRepository);

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

	@Test
	void rejectsLockedAsAnAccountStatusFilter() {
		assertThatThrownBy(() -> service.findRoster(
				cohortId, orgId, null, false, AccountStatus.LOCKED, null, TraineeRosterSort.NAME,
				PageRequest.of(0, 20)))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.ACCOUNT_STATUS_FILTER_NOT_SUPPORTED));
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

	@Test
	void isIdempotentWhenTheStatusAlreadyMatches() {
		TraineeRosterRepository.RosterRow alreadyActive = rosterRow("ACTIVE");
		when(traineeRosterRepository.findTrainee(traineeId, cohortId, orgId))
				.thenReturn(Optional.of(alreadyActive));

		service.updateStatus(cohortId, orgId, traineeId, AccountStatus.ACTIVE, null, actorUserId);

		verify(traineeRosterRepository, never()).updateStatus(any(), any(), any(), any(), any());
		verify(traineeRosterRepository, times(2)).findTrainee(traineeId, cohortId, orgId);
	}

	private TraineeRosterRepository.RosterRow rosterRow(String rawStatus) {
		return new TraineeRosterRepository.RosterRow(
				traineeId, "교육생", "trainee@example.com", rawStatus,
				null, null, OffsetDateTime.now(), null);
	}
}
