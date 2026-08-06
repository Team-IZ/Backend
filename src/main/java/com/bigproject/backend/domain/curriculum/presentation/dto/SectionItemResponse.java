package com.bigproject.backend.domain.curriculum.presentation.dto;

import com.bigproject.backend.domain.curriculum.application.CurriculumService.SectionItemView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "섹션 안 항목(가르친 것) 하나")
public record SectionItemResponse(
        @Schema(description = "매핑 ID") UUID mappingId,
        @Schema(description = "항목 이름", example = "HITL Trigger 조건 함수") String extractedName,

        @Schema(description = "정의문. definitionMissing이 true면 null — 빈칸이 아니라 '추출 안 됨'을 뜻한다.")
        String description,

        @Schema(description = "정의문이 아직 추출되지 않았으면 true(빈칸과 구분하기 위한 플래그)")
        boolean definitionMissing,

        @Schema(description = "시작 페이지") Integer pageStart,
        @Schema(description = "끝 페이지") Integer pageEnd,

        @Schema(description = "★ 표시 — 이 항목이 검증 개념으로 확정된 적 있으면 true")
        boolean usedAsVerificationConcept,

        @Schema(description = "이 항목이 검증 개념으로 쓰인 회차 라벨 목록. 비어있으면 안 쓰였다는 뜻")
        List<String> usedRoundLabels
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