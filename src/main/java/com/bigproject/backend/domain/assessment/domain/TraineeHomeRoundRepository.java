package com.bigproject.backend.domain.assessment.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TraineeHomeRoundRepository {

	/** 교육생이 속한 모든 회차 카드. 진행 중인 프로젝트가 없으면 빈 목록이다. */
	List<TraineeHomeRound> findAllByTraineeUserId(UUID traineeUserId);

	/**
	 * 회차 카드가 0행일 때 쓰는 기수 스코프 소속. 기수 미소속이면 비어 있다.
	 * 이 경우 응답은 오류가 아니라 {@code NO_ACTIVE_ROUND} 합성 카드다.
	 */
	Optional<TraineeMembership> findMembershipByUserId(UUID traineeUserId);

	/** 교육생이 속한 기관의 ACTIVE 정책. 없으면 비어 있다. */
	Optional<SubmissionMethodPolicy> findSubmissionMethodPolicyByUserId(UUID traineeUserId);
}
