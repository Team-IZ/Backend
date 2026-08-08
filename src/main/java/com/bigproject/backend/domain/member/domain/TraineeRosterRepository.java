package com.bigproject.backend.domain.member.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 기수 교육생 명단(GET /cohorts/{cohortId}/trainees) 원천 조회·상태 변경 포트.
 *
 * <p>{@code cohort_member} + {@code class_membership} + {@code class} + {@code app_user}를 직접 조인한다.
 * academicoperations 도메인의 JPA 엔티티를 재사용하지 않는 이유는 이름·이메일·계정 상태가 {@code app_user}에
 * 있고 그건 auth 도메인 소유라, 이 화면 하나를 위해 도메인 간 JPA 연관을 만들기보다 이 화면이 필요한 필드만
 * 직접 SQL로 뽑는 편(이미 organization·analytics 도메인이 쓰는 방식)이 더 적다.
 */
public interface TraineeRosterRepository {

	Optional<CohortScope> findCohortScope(UUID cohortId);

	Page<RosterRow> findRoster(RosterCriteria criteria, Pageable pageable);

	/** 반 배정이 없는(class_membership 활성 행이 없는) 교육생 수. 필터와 무관하게 기수 전체 기준. */
	int countUnassigned(UUID cohortId, UUID orgId);

	/**
	 * 기수 전체 교육생 수. <b>필터를 적용하지 않은</b> 모집단이라 페이지의 {@code totalElements}와 다르다.
	 * 화면 상단이 '명단 393명'과 '7기 393명에서 찾았습니다'를 필터와 무관하게 보여주는데, 그 값을
	 * 목록 한 페이지에서는 만들 수 없어 따로 센다.
	 */
	int countCohortTotal(UUID cohortId, UUID orgId);

	Optional<RosterRow> findTrainee(UUID traineeId, UUID cohortId, UUID orgId);

	/** @return 실제로 바뀐 행 수(0이면 대상 없음 또는 이미 같은 상태) */
	int updateStatus(UUID traineeId, String rawStatus, UUID actorUserId, String reasonCode, String reason);

	record CohortScope(UUID cohortId, UUID orgId) {
	}

	record RosterCriteria(
			UUID cohortId,
			UUID orgId,
			UUID classroomId,
			boolean unassignedOnly,
			String rawAccountStatus,
			String query,
			TraineeRosterSort sort
	) {
	}

	/**
	 * 명단 한 행. {@code classroomId}·{@code className}은 현재 유효한(unassigned_at IS NULL) 반 배정이
	 * 없으면 둘 다 null이다. {@code leftAt}은 cohort_member.status가 LEFT일 때만 값이 있다.
	 */
	record RosterRow(
			UUID traineeId,
			String name,
			String email,
			String rawAccountStatus,
			UUID classroomId,
			String className,
			OffsetDateTime joinedAt,
			OffsetDateTime leftAt
	) {
	}
}
