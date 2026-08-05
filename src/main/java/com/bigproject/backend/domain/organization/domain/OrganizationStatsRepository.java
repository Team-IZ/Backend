package com.bigproject.backend.domain.organization.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * cohort/member 도메인은 아직 organization이 가져다 쓸 수 있는 엔티티/리포지토리가 없어서(cohort는 엔티티 자체가 없고,
 * member는 cohort 단위로만 교육생을 조회할 수 있음), organization 도메인이 자기 응답에 필요한 집계만 직접 조회하기 위한
 * 전용 포트. auth.domain.AuthUserRepository와 같은 패턴(인터페이스는 domain, JDBC 구현은 infrastructure)을 따른다.
 */
public interface OrganizationStatsRepository {

	/** 기관별 기수 수를 상태별(진행/종료)로 분해해 조회한다. 목업 SA-02 개요의 `기수 3 (진행 2 · 종료 1)` 카드용. */
	Map<UUID, CohortCounts> countCohortsByOrgId(Collection<UUID> organizationIds);

	/**
	 * 기관별 오퍼레이터 계정 목록. 목업 SA-01 목록의 `오퍼레이터` 열(`박지현 외 1`)과
	 * SA-02 개요의 `오퍼레이터 박지현 · 이도윤` 행을 채운다.
	 *
	 * <p>v1의 managerCount(LEAD_MANAGER+MANAGER 합산)를 대체한다 — v2 슈퍼어드민 화면에는 매니저 수가 더 이상 없고
	 * (매니저 관리는 OP-06 ③으로 이동), 대신 오퍼레이터의 <b>이름</b>이 필요하다.
	 */
	Map<UUID, List<OrganizationOperator>> findOperatorsByOrgId(Collection<UUID> organizationIds);

	Map<UUID, Integer> countActiveTraineesByOrgId(Collection<UUID> organizationIds);

	/**
	 * 기관별 진행 중 세션 수. 목업 SA-01 목록 · SA-02 개요의 `활성 세션` 값이다.
	 *
	 * <p>v07에서 {@code assessment_session}이 생겨 실제 집계로 대체됐다(이전에는 테이블이 없어 0 고정이었다).
	 * "진행 중"은 <b>시작됐고 아직 끝나지 않은</b> 세션 — {@code IN_PROGRESS}·{@code PAUSED} — 만 센다.
	 * {@code READY}는 아직 응시가 시작되지 않아 "지금 몇 명이 보고 있나"라는 이 지표의 물음에 답하지 않는다.
	 */
	Map<UUID, Integer> countActiveSessionsByOrgId(Collection<UUID> organizationIds);

	/** 기관 상세 개요 하단의 읽기전용 기수 목록(기수/상태/반 수/교육생 수/기간). */
	List<OrganizationCohortSummary> findCohortSummaries(UUID organizationId);

	/** 플랫폼 전체 기관 수를 상태별로 조회한다. 목업 SA-01 상단 `총 기관 14 (활성 12 · 정지 2)` 카드용. */
	PlatformOrganizationCounts countOrganizationsByStatus();

	/** 플랫폼 전체 활성 교육생 수. 목업 SA-01 상단 `총 교육생 1,284` 카드용. */
	int countAllActiveTrainees();

	/**
	 * 플랫폼 전체 진행 중 세션 수. 목업 SA-01 상단 `활성 세션` 카드용.
	 * "진행 중"의 정의는 {@link #countActiveSessionsByOrgId(Collection)}과 같아야 한다.
	 */
	int countAllActiveSessions();

	/**
	 * 기관별 저장량(바이트). storage_usage_snapshot은 주기 스냅샷이라 기간 내 값을 단순 합산하면 중복 집계가 된다.
	 *
	 * <p>v06 기준으로 기관별 <b>가장 최근 측정 세트(measurement_batch_id)</b> 하나만 골라 합산한 시점 값을 반환한다.
	 * 카테고리별로 최신 행을 따로 고르면 서로 다른 측정 시점이 섞여 총계와 세부가 어긋난다.
	 * 총계 행(ORG_TOTAL)이 있으면 그 값을 쓰고, 없을 때만 세부 카테고리를 합산한다 — 둘을 함께 더하면 이중 계산이다.
	 */
	Map<UUID, Long> sumLatestStorageBytesByOrgId(Collection<UUID> organizationIds, Instant from, Instant to);

	/** 기관별 기수 수(상태별 분해). */
	record CohortCounts(int total, int running, int closed) {
		public static CohortCounts empty() {
			return new CohortCounts(0, 0, 0);
		}
	}

	/** 기관 소속 오퍼레이터 계정 요약. */
	record OrganizationOperator(UUID memberId, String name, String email) {
	}

	/** 슈퍼어드민이 지원·과금 맥락에서 읽기전용으로 보는 기수 요약. */
	record OrganizationCohortSummary(
			UUID cohortId,
			String name,
			String status,
			int classCount,
			int traineeCount,
			LocalDate startDate,
			LocalDate endDate
	) {
	}

	/** 플랫폼 전체 기관 수 집계. */
	record PlatformOrganizationCounts(int total, int active, int suspended) {
	}
}
