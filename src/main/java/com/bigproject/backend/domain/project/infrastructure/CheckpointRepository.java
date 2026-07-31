package com.bigproject.backend.domain.project.infrastructure;

import com.bigproject.backend.domain.project.domain.Checkpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckpointRepository extends JpaRepository<Checkpoint, UUID> {

    Optional<Checkpoint> findByCheckpointIdAndPlanId(UUID checkpointId, UUID planId);

    // "미프 3차, 4차, 5차..." — plan(=project) 하나 안의 회차들을 순서대로
    List<Checkpoint> findByPlanIdOrderByProjectSequenceNoAsc(UUID planId);

    // track_sequence_no 채번용. 기수 전체를 통틀어 지금까지 나온 최댓값을 구해서
    // 서비스 계층에서 +1 해서 다음 checkpoint에 넣는다.
    // plan을 거쳐야 cohort_id에 닿을 수 있어서 서브쿼리로 우회한다
    @Query("""
            select max(c.trackSequenceNo) from Checkpoint c
            where c.planId in (
                select p.planId from MeasurementPlan p where p.cohortId = :cohortId)
            """)
    Integer findMaxTrackSequenceNoByCohortId(@Param("cohortId") UUID cohortId);
}
