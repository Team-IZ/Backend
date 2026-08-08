package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import java.util.List;
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

	@Test
	void rejectsLockedAsAnAccountStatusFilter() {
		assertThatThrownBy(() -> service.findManagers(
				orgId, AccountStatus.LOCKED, null, ManagerRosterSort.NAME, PageRequest.of(0, 20)))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(MemberErrorCode.ACCOUNT_STATUS_FILTER_NOT_SUPPORTED));
	}

	@Test
	void translatesInvitedFilterToTheRawPendingDbValue() {
		Page<ManagerRosterRepository.ManagerRosterRow> emptyPage = new PageImpl<>(List.of());
		when(managerRosterRepository.findManagers(any(), any())).thenReturn(emptyPage);

		service.findManagers(orgId, AccountStatus.INVITED, "강", ManagerRosterSort.ASSIGNED_TRAINEE_COUNT,
				PageRequest.of(0, 20));

		verify(managerRosterRepository).findManagers(
				eq(new ManagerRosterRepository.ManagerRosterCriteria(
						orgId, "PENDING", "강", ManagerRosterSort.ASSIGNED_TRAINEE_COUNT)),
				any());
	}

	@Test
	void defaultsToNameSortAndNoStatusFilterWhenOmitted() {
		Page<ManagerRosterRepository.ManagerRosterRow> emptyPage = new PageImpl<>(List.of());
		when(managerRosterRepository.findManagers(any(), any())).thenReturn(emptyPage);

		service.findManagers(orgId, null, null, null, PageRequest.of(0, 20));

		verify(managerRosterRepository).findManagers(
				eq(new ManagerRosterRepository.ManagerRosterCriteria(
						orgId, null, null, ManagerRosterSort.NAME)),
				any());
	}
}
