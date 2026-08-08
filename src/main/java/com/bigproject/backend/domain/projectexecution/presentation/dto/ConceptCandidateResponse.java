package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.application.ProjectService.ConceptCandidate;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "검증개념 후보 하나")
public record ConceptCandidateResponse(
        @Schema(description = "매핑 ID(개념 선택 시 이 ID를 보낸다)") UUID mappingId,
        @Schema(description = "공용 개념 원장 ID") UUID teachesId,
        @Schema(description = "항목 이름", example = "HITL Trigger 조건 함수") String extractedName,
        // SectionItemResponse.description과 같은 값(curriculum_teaches_mapping.source_description)이라
        // 여기서도 null이 온다. 8차 R2와 같은 종류의 문제이므로 함께 고친다.
        @Schema(description = "정의문. 아직 추출되지 않았으면 null", nullable = true) String description
) {
    public static ConceptCandidateResponse from(ConceptCandidate candidate) {
        return new ConceptCandidateResponse(
                candidate.mappingId(), candidate.teachesId(),
                candidate.extractedName(), candidate.description());
    }
}