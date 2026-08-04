package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

	/**
	 * 화면의 "미프 N차"에 해당하는 분석 순서를 계산한다.
	 *
	 * <p>기수 안에서 삭제되지 않은 MINI_PROJECT만 sequence_no·project_id 순으로 재번호화한 값이며,
	 * 회차 원장의 round_no와는 다른 값이다. 저장하지 않고 조회 시점마다 파생한다.
	 */
	@Query("""
			SELECT COUNT(p)
			FROM Project p
			WHERE p.cohortId = :cohortId
				AND p.projectCategory = 'MINI_PROJECT'
				AND p.deletedAt IS NULL
				AND (p.sequenceNo < :sequenceNo
					OR (p.sequenceNo = :sequenceNo AND p.projectId <= :projectId))
			""")
	int countMiniProjectAnalysisSequenceNo(
			@Param("cohortId") UUID cohortId,
			@Param("sequenceNo") int sequenceNo,
			@Param("projectId") UUID projectId
	);
}
