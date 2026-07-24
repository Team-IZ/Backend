package com.bigproject.backend.domain.member.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.auth.domain.AuthUserRepository;
import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.MemberQueryRepository;
import com.bigproject.backend.domain.member.domain.MemberSortField;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.member.domain.SortDirection;
import com.bigproject.backend.domain.member.presentation.dto.ManagerSummaryResponse;
import com.bigproject.backend.domain.member.presentation.dto.MemberListResponse;
import com.bigproject.backend.domain.member.presentation.dto.TraineeListResponse;
import com.bigproject.backend.domain.member.presentation.dto.TraineeSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberQueryService {
	private static final String ACTIVE = "ACTIVE";
	private static final ZoneId SERVICE_ZONE_ID = ZoneId.of("Asia/Seoul");

	private final AuthUserRepository authUserRepository;
	private final MemberQueryRepository memberQueryRepository;

	public MemberListResponse findManagers(
			UUID requestedOrganizationId,
			Role role,
			AccountStatus status,
			String query,
			int page,
			int size,
			MemberSortField sortBy,
			SortDirection direction,
			String actorEmail
	) {
		AuthUser actor = activeActor(actorEmail);
		validateManagerRoleFilter(role);
		UUID organizationId = managerOrganization(actor, requestedOrganizationId);
		validatePage(page, size);

		MemberQueryRepository.Page<MemberQueryRepository.ManagerRow> result = memberQueryRepository.findManagers(
				new MemberQueryRepository.ManagerCriteria(
						organizationId,
						role,
						status,
						normalizeQuery(query),
						page,
						size,
						sortBy,
						direction
				)
		);
		Map<UUID, List<String>> cohortNames = managerCohortNames(result.content());
		List<ManagerSummaryResponse> content = result.content().stream()
				.map(row -> new ManagerSummaryResponse(
						row.memberId(),
						row.name(),
						row.email(),
						managerRoleName(row.role()),
						row.role() == Role.LEAD_MANAGER
								? List.of("기관 전체")
								: cohortNames.getOrDefault(row.memberId(), List.of()),
						managerStatusName(apiStatus(row.databaseStatus(), row.deleted())),
						lastLoginDate(row.lastLoginAt())
				))
				.toList();
		return new MemberListResponse(content, page, size, result.totalElements(), totalPages(result.totalElements(), size));
	}

	public TraineeListResponse findTrainees(
			UUID cohortId,
			UUID classroomId,
			AccountStatus status,
			String query,
			int page,
			int size,
			String actorEmail
	) {
		AuthUser actor = activeActor(actorEmail);
		if (actor.role() != Role.LEAD_MANAGER && actor.role() != Role.MANAGER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "매니저만 교육생 명단을 조회할 수 있습니다.");
		}
		MemberQueryRepository.CohortScope cohort = memberQueryRepository.findCohortScope(cohortId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기수를 찾을 수 없습니다."));
		if (!cohort.organizationId().equals(actor.organizationId())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 기관의 교육생 명단은 조회할 수 없습니다.");
		}
		if (classroomId != null && !memberQueryRepository.classroomBelongsToCohort(
				classroomId,
				cohortId,
				cohort.organizationId()
		)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "해당 기수에 속한 반이 아닙니다.");
		}
		validatePage(page, size);

		MemberQueryRepository.Page<MemberQueryRepository.TraineeRow> result = memberQueryRepository.findTrainees(
				new MemberQueryRepository.TraineeCriteria(
						cohortId,
						cohort.organizationId(),
						classroomId,
						status,
						normalizeQuery(query),
						page,
						size
				)
		);
		Map<UUID, MemberQueryRepository.CurrentClassroomRow> classrooms = currentClassrooms(result.content());
		List<TraineeSummaryResponse> content = result.content().stream()
				.map(row -> {
					MemberQueryRepository.CurrentClassroomRow classroom = classrooms.get(row.cohortMemberId());
					return new TraineeSummaryResponse(
							row.memberId(),
							row.name(),
							row.email(),
							apiStatus(row.databaseStatus(), row.deleted()),
							row.membershipStatus(),
							row.leftAt(),
							classroom == null ? null : classroom.classroomId(),
							classroom == null ? null : classroom.classroomName()
					);
				})
				.toList();
		return new TraineeListResponse(content, page, size, result.totalElements(), totalPages(result.totalElements(), size));
	}

	private AuthUser activeActor(String email) {
		AuthUser actor = authUserRepository.findByNormalizedEmail(EmailNormalizer.normalize(email))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 사용자를 찾을 수 없습니다."));
		if (!ACTIVE.equals(actor.status()) || !actor.emailVerified()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 사용자만 회원 정보를 조회할 수 있습니다.");
		}
		if (actor.role() != Role.SUPER_ADMIN && !ACTIVE.equals(actor.organizationStatus())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "활성 기관의 사용자만 회원 정보를 조회할 수 있습니다.");
		}
		return actor;
	}

	private UUID managerOrganization(AuthUser actor, UUID requestedOrganizationId) {
		UUID organizationId;
		if (actor.role() == Role.SUPER_ADMIN) {
			if (requestedOrganizationId == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "슈퍼어드민은 organizationId를 지정해야 합니다.");
			}
			organizationId = requestedOrganizationId;
		} else if (actor.role() == Role.LEAD_MANAGER) {
			organizationId = actor.organizationId();
			if (organizationId == null) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "소속 기관이 없는 총괄 매니저입니다.");
			}
			if (requestedOrganizationId != null && !organizationId.equals(requestedOrganizationId)) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "다른 기관의 매니저는 조회할 수 없습니다.");
			}
		} else {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "총괄 매니저 이상만 매니저 목록을 조회할 수 있습니다.");
		}
		if (!memberQueryRepository.existsOrganization(organizationId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "기관을 찾을 수 없습니다.");
		}
		return organizationId;
	}

	private void validateManagerRoleFilter(Role role) {
		if (role != null && role != Role.LEAD_MANAGER && role != Role.MANAGER) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role은 LEAD_MANAGER 또는 MANAGER만 허용합니다.");
		}
	}

	private void validatePage(int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지 범위가 올바르지 않습니다.");
		}
	}

	private Map<UUID, List<String>> managerCohortNames(
			List<MemberQueryRepository.ManagerRow> managers
	) {
		List<UUID> managerIds = managers.stream()
				.filter(manager -> manager.role() == Role.MANAGER)
				.map(MemberQueryRepository.ManagerRow::memberId)
				.toList();
		Map<UUID, List<String>> current = new LinkedHashMap<>();
		Map<UUID, List<String>> history = new LinkedHashMap<>();
		for (MemberQueryRepository.ManagerAssignmentRow row : memberQueryRepository.findManagerAssignments(managerIds)) {
			Map<UUID, List<String>> target = row.unassignedAt() == null && ACTIVE.equals(row.status())
					? current
					: history;
			List<String> names = target.computeIfAbsent(row.managerId(), ignored -> new ArrayList<>());
			if (!names.contains(row.cohortName())) {
				names.add(row.cohortName());
			}
		}
		Map<UUID, List<String>> result = new LinkedHashMap<>(history);
		current.forEach(result::put);
		return result;
	}

	private String managerRoleName(Role role) {
		return switch (role) {
			case LEAD_MANAGER -> "총괄";
			case MANAGER -> "담당";
			default -> throw new IllegalStateException("지원하지 않는 매니저 역할입니다: " + role);
		};
	}

	private String managerStatusName(AccountStatus status) {
		return switch (status) {
			case ACTIVE -> "활성화";
			case INVITED -> "초대됨";
			case LOCKED, INACTIVE -> "비활성화";
		};
	}

	private LocalDate lastLoginDate(Instant lastLoginAt) {
		return lastLoginAt == null ? null : lastLoginAt.atZone(SERVICE_ZONE_ID).toLocalDate();
	}

	private Map<UUID, MemberQueryRepository.CurrentClassroomRow> currentClassrooms(
			List<MemberQueryRepository.TraineeRow> trainees
	) {
		List<UUID> membershipIds = trainees.stream()
				.map(MemberQueryRepository.TraineeRow::cohortMemberId)
				.toList();
		Map<UUID, MemberQueryRepository.CurrentClassroomRow> result = new LinkedHashMap<>();
		for (MemberQueryRepository.CurrentClassroomRow row : memberQueryRepository.findCurrentClassrooms(membershipIds)) {
			result.putIfAbsent(row.cohortMemberId(), row);
		}
		return result;
	}

	private AccountStatus apiStatus(String databaseStatus, boolean deleted) {
		if (deleted) {
			return AccountStatus.INACTIVE;
		}
		return switch (databaseStatus) {
			case "PENDING" -> AccountStatus.INVITED;
			case "ACTIVE" -> AccountStatus.ACTIVE;
			case "LOCKED" -> AccountStatus.LOCKED;
			case "INACTIVE" -> AccountStatus.INACTIVE;
			default -> throw new IllegalStateException("지원하지 않는 회원 상태입니다: " + databaseStatus);
		};
	}

	private String normalizeQuery(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		return query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	private int totalPages(long totalElements, int size) {
		return totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
	}
}
