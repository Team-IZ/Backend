package com.bigproject.backend.domain.manager.domain;

import com.bigproject.backend.domain.member.domain.AccountStatus;

import java.util.List;
import java.util.UUID;

/**
 * MG-05 교육생 명부의 복합 조회 포트.
 *
 * <p>여기 남은 쿼리는 모두 (1) 대응하는 JPA 엔티티가 없는 측정·평가 원천(measurement_attempt, assessment_session,
 * assessment_problem, problem_stage)을 가로지르거나 (2) CTE·FILTER 집계·윈도 함수처럼 JPQL로 표현할 수 없는 것들이다.
 * 회차 확정, 검증 개념 정의, 담당 반 범위, 기수 조회처럼 단순한 조회는 Spring Data JPA 리포지토리로 옮겼다.
 */
public interface TraineeRosterRepository {

	// 정렬 기준이 이름(DEFAULT)일 때 사용하는 DB LIMIT/OFFSET 페이지 조회
	Page<RosterMemberRow> findRosterPage(Criteria criteria, UUID assessmentRoundId, int page, int size);

	// 정렬 기준이 우수/2단 이하처럼 개념 결과 집계에 의존할 때 사용하는 전체 대상 조회(페이지네이션은 서비스에서 수행)
	List<RosterMemberRow> findAllRosterMembers(Criteria criteria, UUID assessmentRoundId);

	// 헤더 집계: 담당 반 범위 전체(계정 필터 적용 전)의 계정 상태별 인원 수
	AccountStatusCounts countAccountStatuses(UUID cohortId, UUID organizationId, List<UUID> classIds);

	// userId 목록에 대해 선택 회차의 개념 3건 도달 결과를 반환
	List<ConceptResultRow> findConceptResults(List<UUID> userIds, UUID conceptSetId, UUID assessmentRoundId);

	// userId 목록에 대해 선택 회차까지(analysis_sequence_no 이하)의 우수 발생 횟수를 반환. 정책은 저장하지 않고 매 요청 재계산한다.
	List<ExcellentOccurrenceRow> findExcellentOccurrenceCounts(
			List<UUID> userIds, UUID cohortId, int maxAnalysisSequenceNo
	);

	record Criteria(
			UUID cohortId,
			UUID organizationId,
			List<UUID> classIds,
			AccountStatus accountStatus,
			String normalizedSearchQuery
	) {
	}

	record RosterMemberRow(
			UUID cohortMemberId,
			UUID userId,
			String name,
			String email,
			String appUserStatus,
			boolean appUserDeleted,
			UUID classId,
			String className,
			UUID attemptId,
			String attemptStatus,
			String validityReviewStatus
	) {
	}

	record AccountStatusCounts(
			long totalCount,
			long activeCount,
			long invitationPendingCount,
			long inactiveCount
	) {
	}

	record ConceptResultRow(
			UUID userId,
			UUID conceptId,
			int sequenceNo,
			String conceptName,
			String generationStatus,
			Integer highestReachedLevel
	) {
	}

	record ExcellentOccurrenceRow(UUID userId, int occurrenceCount) {
	}

	record Page<T>(List<T> content, long totalElements) {
		public Page {
			content = List.copyOf(content);
		}
	}
}
