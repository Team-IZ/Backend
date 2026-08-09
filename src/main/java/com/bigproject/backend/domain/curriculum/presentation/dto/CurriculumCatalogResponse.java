package com.bigproject.backend.domain.curriculum.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 기관 전체 교안 목록 한 페이지(9차 R8).
 *
 * <p>필드 이름을 매니저 목록({@code ManagerRosterResponse})과 맞췄다 — 목록 응답의 생김새가
 * 도메인마다 갈리면 화면이 페이지네이션을 도메인 수만큼 다르게 다루게 된다.
 */
@Schema(description = "기관 교안 목록 페이지 응답")
public record CurriculumCatalogResponse(
        @Schema(description = "이 페이지의 교안 목록") List<CurriculumCatalogItemResponse> content,
        @Schema(description = "0부터 시작하는 현재 페이지 번호", example = "0") int page,
        @Schema(description = "페이지당 개수", example = "20") int size,
        @Schema(description = "**필터 적용 후** 전체 교안 수. OP-06 교안 탭의 배지 숫자가 이 값이다", example = "12")
        long totalElements,
        @Schema(description = "필터 적용 후 전체 페이지 수", example = "1") int totalPages
) {
}
