package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.application.ProjectService.ConceptCandidate;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 검증개념 후보 하나.
 *
 * <p>필드 이름·타입을 {@code SectionItemResponse}(교안 섹션 항목)와 <b>같게 맞췄다</b>(9차 R2).
 * 두 응답이 같은 원장(curriculum_teaches_mapping)에서 나오는데 한쪽만 좁으면, 후보를 교안·섹션별로
 * 묶는 화면이 묶을 기준을 잃고 후보 12건이 평평한 한 덩어리가 된다.
 *
 * <p>스키마를 그대로 {@code $ref}로 공유하지 않은 이유는 이쪽에만 있어야 하는 값이 있기 때문이다 —
 * {@code teachesId}(확정 시 원장 키)와 {@code curriculumVersionId}(후보가 어느 교안에서 왔는지)는
 * 섹션 항목에는 필요 없다. 섹션 응답은 이미 교안·섹션 안에 들어 있어 물어볼 필요가 없는 값이라서다.
 * 겹치는 여섯 필드는 이름·타입·의미가 같으므로 화면이 한 함수로 다룰 수 있다.
 */
@Schema(description = "검증개념 후보 하나")
public record ConceptCandidateResponse(
        @Schema(description = "매핑 ID(개념 선택 시 이 ID를 보낸다)") UUID mappingId,
        @Schema(description = "공용 개념 원장 ID") UUID teachesId,
        @Schema(description = "항목 이름", example = "HITL Trigger 조건 함수") String extractedName,
        // SectionItemResponse.description과 같은 값(curriculum_teaches_mapping.source_description)이라
        // 여기서도 null이 온다. 8차 R2와 같은 종류의 문제이므로 함께 고친다.
        @Schema(description = "정의문. definitionMissing이 true면 null", nullable = true) String description,
        @Schema(description = "정의문이 아직 추출되지 않았으면 true — 정의문 없는 항목을 고르면 문항 품질이 갈린다")
        boolean definitionMissing,
        @Schema(description = "이 후보가 나온 교안 버전 ID. 후보를 교안별로 묶는 기준이다") UUID curriculumVersionId,
        @Schema(description = "이 후보가 속한 섹션 ID. 섹션 정보가 없는 매핑이면 null", nullable = true) UUID sectionId,
        @Schema(description = "섹션 제목. 후보 목록의 섹션 헤더 문구다. 섹션 정보가 없으면 null",
                example = "HITL (Human In The Loop)", nullable = true) String sectionTitle,
        @Schema(description = "시작 페이지. `p.53` 표기에 쓰며 확정 뒤에도 계속 쓰인다") Integer pageStart,
        @Schema(description = "끝 페이지") Integer pageEnd
) {
    public static ConceptCandidateResponse from(ConceptCandidate candidate) {
        return new ConceptCandidateResponse(
                candidate.mappingId(),
                candidate.teachesId(),
                candidate.extractedName(),
                candidate.description(),
                candidate.definitionMissing(),
                candidate.curriculumVersionId(),
                candidate.sectionId(),
                candidate.sectionTitle(),
                candidate.pageStart(),
                candidate.pageEnd());
    }
}
