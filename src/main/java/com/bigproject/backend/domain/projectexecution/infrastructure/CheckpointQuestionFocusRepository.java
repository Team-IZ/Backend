package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.CheckpointQuestionFocus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CheckpointQuestionFocusRepository
        extends JpaRepository<CheckpointQuestionFocus, UUID> {

    // 화면의 "검증 개념 3건" — 지금 유효한 것만 뽑는다
    @Query("""
            select f from CheckpointQuestionFocus f
            where f.checkpointId = :checkpointId
              and f.effectiveFrom <= :at
              and (f.effectiveTo is null or f.effectiveTo > :at)
            """)
    List<CheckpointQuestionFocus> findEffectiveByCheckpointId(
            @Param("checkpointId") UUID checkpointId, @Param("at") OffsetDateTime at);

    // "검증 개념 3건을 먼저 정하세요" 잠금 화면(#draft)에서 정확히 3건 붙었는지
    // 확인할 때 쓰는 카운트. 서비스 계층에서 이 값이 3이 아니면 일정·현황 탭을 막는다
    long countByCheckpointIdAndEffectiveToIsNull(UUID checkpointId);
}