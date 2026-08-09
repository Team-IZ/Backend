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

	/**
	 * 9차 Q3-② — {@code LOCKED}를 <b>타입에서</b> 없앴다. 예전에는 값을 받아 두고 서비스가 거절했는데,
	 * 안 오는 값이 타입에 있으면 화면이 도달할 수 없는 분기를 계속 들고 있게 된다.
	 * {@code ck_app_user_status}가 세 값만 허용하므로 DB에서도 나올 수 없다.
	 */
	@Test
	void offersOnlyTheThreeAccountStatusesThatCanActuallyOccur() {
		assertThat(AccountStatus.values())
				.containsExactly(AccountStatus.INVITED, AccountStatus.ACTIVE, AccountStatus.INACTIVE);
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
