package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagerRosterServiceTest {

	private final ManagerRosterRepository managerRosterRepository = mock(ManagerRosterRepository.class);
	private final ManagerRosterService service = new ManagerRosterService(managerRosterRepository);

	private final UUID orgId = UUID.randomUUID();
	private final UUID cohortId = UUID.randomUUID();

	@Test
	void rejectsLockedAsAnAccountStatusFilter() {
		assertThatThrownBy(() -> service.findManagers(
				orgId, null, AccountStatus.LOCKED, null, ManagerRosterSort.NAME, PageRequest.of(0, 20)))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.ACCOUNT_STATUS_FILTER_NOT_SUPPORTED));
	}

	@Test
	void translatesInvitedFilterToTheRawPendingDbValue() {
		Page<ManagerRosterRepository.ManagerRosterRow> emptyPage = new PageImpl<>(List.of());
		when(managerRosterRepository.findManagers(any(), any())).thenReturn(emptyPage);

		service.findManagers(orgId, null, AccountStatus.INVITED, "강", ManagerRosterSort.ASSIGNED_TRAINEE_COUNT,
				PageRequest.of(0, 20));

		verify(managerRosterRepository).findManagers(
				eq(new ManagerRosterRepository.ManagerRosterCriteria(
						orgId, null, "PENDING", "강", ManagerRosterSort.ASSIGNED_TRAINEE_COUNT)),
				any());
	}

	@Test
	void defaultsToNameSortAndNoStatusFilterWhenOmitted() {
		Page<ManagerRosterRepository.ManagerRosterRow> emptyPage = new PageImpl<>(List.of());
		when(managerRosterRepository.findManagers(any(), any())).thenReturn(emptyPage);

		service.findManagers(orgId, null, null, null, null, PageRequest.of(0, 20));

		verify(managerRosterRepository).findManagers(
				eq(new ManagerRosterRepository.ManagerRosterCriteria(
						orgId, null, null, null, ManagerRosterSort.NAME)),
				any());
	}

	@Test
	void passesTheCohortScopeToTheListQuery() {
		Page<ManagerRosterRepository.ManagerRosterRow> emptyPage = new PageImpl<>(List.of());
		when(managerRosterRepository.findManagers(any(), any())).thenReturn(emptyPage);

		service.findManagers(orgId, cohortId, null, null, ManagerRosterSort.NAME, PageRequest.of(0, 20));

		verify(managerRosterRepository).findManagers(
				eq(new ManagerRosterRepository.ManagerRosterCriteria(
						orgId, cohortId, null, null, ManagerRosterSort.NAME)),
				any());
	}

	/**
	 * 상태 칩은 목록과 같은 모집단을 세야 한다. 기수를 목록에만 걸고 집계를 기관 전체로 두면
	 * '활성 7 · 초대 대기 1'의 합이 목록 건수와 어긋난다.
	 */
	@Test
	void countsStatusesWithinTheSameCohortScopeAsTheList() {
		Page<ManagerRosterRepository.ManagerRosterRow> emptyPage = new PageImpl<>(List.of());
		when(managerRosterRepository.findManagers(any(), any())).thenReturn(emptyPage);

		service.findManagers(orgId, cohortId, AccountStatus.ACTIVE, null, ManagerRosterSort.NAME,
				PageRequest.of(0, 20));

		verify(managerRosterRepository).countByStatus(orgId, cohortId);
	}

	@Test
	void alwaysReportsAllThreeStatusKeysEvenWhenNobodyHasThatStatus() {
		Page<ManagerRosterRepository.ManagerRosterRow> emptyPage = new PageImpl<>(List.of());
		when(managerRosterRepository.findManagers(any(), any())).thenReturn(emptyPage);
		when(managerRosterRepository.countByStatus(orgId, cohortId)).thenReturn(Map.of("ACTIVE", 7L));

		ManagerRosterService.RosterResult result = service.findManagers(
				orgId, cohortId, null, null, ManagerRosterSort.NAME, PageRequest.of(0, 20));

		assertThat(result.statusCounts())
				.containsEntry(AccountStatus.ACTIVE, 7L)
				.containsEntry(AccountStatus.INVITED, 0L)
				.containsEntry(AccountStatus.INACTIVE, 0L);
	}
}
