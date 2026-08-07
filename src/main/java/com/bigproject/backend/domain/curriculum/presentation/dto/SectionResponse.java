package com.bigproject.backend.domain.curriculum.presentation.dto;

import com.bigproject.backend.domain.curriculum.application.CurriculumService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "섹션 하나 + 그 안의 항목들")
public record SectionResponse(
        @Schema(description = "섹션 ID") UUID sectionId,
        @Schema(description = "섹션 제목", example = "HITL (Human In The Loop)") String title,
        @Schema(description = "시작 페이지") Integer pageStart,
        @Schema(description = "끝 페이지") Integer pageEnd,
        @Schema(description = "섹션 안 항목 목록") List<SectionItemResponse> items
) {
    public static SectionResponse from(CurriculumService.SectionView view) {
        return new SectionResponse(
                view.sectionId(),
                view.title(),
                view.pageStart(),
                view.pageEnd(),
                view.items().stream().map(SectionItemResponse::from).toList()
        );
    }
}