package com.bigproject.backend.domain.curriculum.presentation.dto;

import com.bigproject.backend.domain.curriculum.application.CurriculumService.SectionItemView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "섹션 안 항목(가르친 것) 하나")
public record SectionItemResponse(
        @Schema(description = "매핑 ID") UUID mappingId,
        @Schema(description = "항목 이름", example = "HITL Trigger 조건 함수") String extractedName,
        // 값이 null일 수 있다는 사실은 설명문이 아니라 타입에 있어야 한다. 설명에만 적으면
        // 생성 타입이 `description: string`이 되어 컴파일러가 null 검사를 요구하지 않고,
        // `description.trim()` 한 줄에서 런타임에 터진다(8차 R2).
        @Schema(description = "정의문. definitionMissing이 true면 null", nullable = true) String description,
        @Schema(description = "정의문이 아직 추출되지 않았으면 true") boolean definitionMissing,
        @Schema(description = "시작 페이지") Integer pageStart,
        @Schema(description = "끝 페이지") Integer pageEnd,
        @Schema(description = "★ 표시 — 검증개념으로 확정된 적 있으면 true") boolean usedAsVerificationConcept,
        @Schema(description = "검증개념으로 쓰인 회차 라벨 목록") List<String> usedRoundLabels
) {
    public static SectionItemResponse from(SectionItemView view) {
        return new SectionItemResponse(
                view.mappingId(),
                view.extractedName(),
                view.description(),
                view.definitionMissing(),
                view.pageStart(),
                view.pageEnd(),
                view.usedAsVerificationConcept(),
                view.usedRoundLabels()
        );
    }
}