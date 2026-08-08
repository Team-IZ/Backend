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
			UUID orgId,
			UUID cohortId,
			AccountStatus accountStatus,
			String query,
			ManagerRosterSort sort,
			Pageable pageable
	) {
		ManagerRosterRepository.ManagerRosterCriteria criteria = new ManagerRosterRepository.ManagerRosterCriteria(
				orgId,
				cohortId,
				toRawStatus(accountStatus),
				query,
				sort == null ? ManagerRosterSort.NAME : sort
		);
		return new RosterResult(
				managerRosterRepository.findManagers(criteria, pageable),
				toStatusCounts(managerRosterRepository.countByStatus(orgId, cohortId)),
				// suspendable은 기관 전체 기준이다(9차 R7). 기수로 좁힌 목록에서도 "마지막 활성 매니저"의
				// 판정 모집단은 기관이라, statusCounts와 달리 cohortId를 걸지 않는다.
				managerRosterRepository.countActiveManagers(orgId)
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

	/**
	 * 화면 용어를 {@code app_user.status} 원문으로 옮긴다.
	 *
	 * <p>{@code LOCKED} 분기는 9차 Q3-②로 사라졌다 — enum에서 값을 빼서 <b>애초에 들어올 수 없게</b> 했다.
	 * 이제 잘못된 값은 쿼리 파라미터 바인딩에서 400으로 걸린다.
	 */
	private String toRawStatus(AccountStatus status) {
		if (status == null) {
			return null;
		}
		return switch (status) {
			case INVITED -> "PENDING";
			case ACTIVE -> "ACTIVE";
			case INACTIVE -> "INACTIVE";
		};
	}

	/**
	 * 한 페이지와, 상태·검색 필터와 무관한 같은 조회 범위(기관 또는 그 기수)의 상태별 인원,
	 * 그리고 {@code suspendable} 판정에 쓰는 <b>기관 전체</b> 활성 매니저 수.
	 */
	public record RosterResult(
			Page<ManagerRosterRepository.ManagerRosterRow> page,
			Map<AccountStatus, Long> statusCounts,
			int activeManagerCount
	) {
	}
}
