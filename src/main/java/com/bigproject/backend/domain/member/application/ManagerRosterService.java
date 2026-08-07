package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagerRosterService {

	private final ManagerRosterRepository managerRosterRepository;

	public Page<ManagerRosterRepository.ManagerRosterRow> findManagers(
			UUID orgId, AccountStatus accountStatus, String query, ManagerRosterSort sort, Pageable pageable
	) {
		ManagerRosterRepository.ManagerRosterCriteria criteria = new ManagerRosterRepository.ManagerRosterCriteria(
				orgId,
				toRawStatus(accountStatus),
				query,
				sort == null ? ManagerRosterSort.NAME : sort
		);
		return managerRosterRepository.findManagers(criteria, pageable);
	}

	private String toRawStatus(AccountStatus status) {
		if (status == null) {
			return null;
		}
		return switch (status) {
			case INVITED -> "PENDING";
			case ACTIVE -> "ACTIVE";
			case INACTIVE -> "INACTIVE";
			case LOCKED -> throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "LOCKED는 계정 상태 필터로 지원하지 않습니다.");
		};
	}
}
