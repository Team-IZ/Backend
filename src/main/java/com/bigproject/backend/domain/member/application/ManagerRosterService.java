package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.ManagerRosterRepository;
import com.bigproject.backend.domain.member.domain.ManagerRosterSort;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.global.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagerRosterService {

	private final ManagerRosterRepository managerRosterRepository;

	public RosterResult findManagers(
			UUID orgId, AccountStatus accountStatus, String query, ManagerRosterSort sort, Pageable pageable
	) {
		ManagerRosterRepository.ManagerRosterCriteria criteria = new ManagerRosterRepository.ManagerRosterCriteria(
				orgId,
				toRawStatus(accountStatus),
				query,
				sort == null ? ManagerRosterSort.NAME : sort
		);
		return new RosterResult(
				managerRosterRepository.findManagers(criteria, pageable),
				toStatusCounts(managerRosterRepository.countByStatus(orgId))
		);
	}

	/**
	 * {@code app_user.status} 원문을 화면이 쓰는 {@link AccountStatus}로 옮긴다.
	 *
	 * <p>0인 상태도 키를 채워 넣는다. 목록 응답이 상태별 칩을 그리는 데 쓰는 값이라, 키가 빠지면
	 * 화면이 '정지 0'을 그리지 못하고 칩 자체가 사라진다 — 0명인 것과 알 수 없는 것은 다르다.
	 */
	private Map<AccountStatus, Long> toStatusCounts(Map<String, Long> rawCounts) {
		Map<AccountStatus, Long> counts = new EnumMap<>(AccountStatus.class);
		counts.put(AccountStatus.INVITED, rawCounts.getOrDefault("PENDING", 0L));
		counts.put(AccountStatus.ACTIVE, rawCounts.getOrDefault("ACTIVE", 0L));
		counts.put(AccountStatus.INACTIVE, rawCounts.getOrDefault("INACTIVE", 0L));
		return counts;
	}

	private String toRawStatus(AccountStatus status) {
		if (status == null) {
			return null;
		}
		return switch (status) {
			case INVITED -> "PENDING";
			case ACTIVE -> "ACTIVE";
			case INACTIVE -> "INACTIVE";
			case LOCKED -> throw new ApiException(
					MemberErrorCode.ACCOUNT_STATUS_FILTER_NOT_SUPPORTED, "LOCKED는 계정 상태 필터로 지원하지 않습니다.");
		};
	}

	/** 한 페이지와, 그 페이지의 필터와 무관한 기관 전체 상태별 인원. */
	public record RosterResult(
			Page<ManagerRosterRepository.ManagerRosterRow> page,
			Map<AccountStatus, Long> statusCounts
	) {
	}
}
