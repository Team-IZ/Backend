package com.bigproject.backend.domain.academicoperations.infrastructure;

import com.bigproject.backend.domain.academicoperations.domain.ProjectVerificationConcept;
import com.bigproject.backend.domain.academicoperations.domain.RoundConceptView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProjectVerificationConceptRepository extends JpaRepository<ProjectVerificationConcept, UUID> {

	/** 개념 세트의 검증 개념 3건을 화면 표시 순서대로 조회. 개념 표준명은 Teaches 원장에서 가져온다. */
	@Query("""
			SELECT new com.bigproject.backend.domain.academicoperations.domain.RoundConceptView(
				c.projectConceptId, c.sequenceNo, t.canonicalName)
			FROM ProjectVerificationConcept c
			JOIN Teaches t ON t.teachesId = c.teachesId
			WHERE c.conceptSetId = :conceptSetId
			ORDER BY c.sequenceNo
			""")
	List<RoundConceptView> findRoundConcepts(@Param("conceptSetId") UUID conceptSetId);
}
