package com.bigproject.backend.domain.assessment.infrastructure;

import com.bigproject.backend.domain.assessment.domain.MiniProjectRoundView;
import com.bigproject.backend.domain.assessment.domain.ProjectAssessmentRound;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectAssessmentRoundRepository extends JpaRepository<ProjectAssessmentRound, UUID> {

	/** 회차 ID로 미니프로젝트 회차 단건 조회. 논리 삭제된 회차·프로젝트는 제외한다. */
	@Query("""
			SELECT new com.bigproject.backend.domain.assessment.domain.MiniProjectRoundView(
				r.assessmentRoundId, r.projectId, r.cohortId, r.conceptSetId, r.roundNo, r.roundName, p.sequenceNo)
			FROM ProjectAssessmentRound r
			JOIN Project p ON p.projectId = r.projectId
			WHERE r.assessmentRoundId = :assessmentRoundId
				AND p.projectCategory = 'MINI_PROJECT'
				AND r.deletedAt IS NULL
				AND p.deletedAt IS NULL
			""")
	Optional<MiniProjectRoundView> findMiniProjectRound(@Param("assessmentRoundId") UUID assessmentRoundId);

	/** 회차를 지정하지 않았을 때의 기본값: 기수에서 운영 순서가 가장 늦은 미니프로젝트의 활성 회차 */
	@Query("""
			SELECT new com.bigproject.backend.domain.assessment.domain.MiniProjectRoundView(
				r.assessmentRoundId, r.projectId, r.cohortId, r.conceptSetId, r.roundNo, r.roundName, p.sequenceNo)
			FROM ProjectAssessmentRound r
			JOIN Project p ON p.projectId = r.projectId
			WHERE p.cohortId = :cohortId
				AND p.projectCategory = 'MINI_PROJECT'
				AND r.deletedAt IS NULL
				AND p.deletedAt IS NULL
			ORDER BY p.sequenceNo DESC, p.projectId DESC, r.roundNo DESC
			""")
	List<MiniProjectRoundView> findLatestMiniProjectRounds(@Param("cohortId") UUID cohortId, Limit limit);
}
