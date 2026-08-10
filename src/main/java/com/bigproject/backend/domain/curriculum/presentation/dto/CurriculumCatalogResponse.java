package com.bigproject.backend.domain.curriculum.presentation.dto;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

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
        @Schema(description = "필터 적용 후 전체 페이지 수", example = "1") int totalPages,

        @Schema(description = """
                분석 상태별 교안 수이며 **필터를 적용하지 않은 기관 전체 모집단**이라 `totalElements`와 다릅니다(11차 R7).
                교안 탭 헤더의 '12개 · 분석 완료 9 · 실패 1'이 이 값이며, 상태 칩이 자기 자신을 필터링하면
                안 되므로 걸러진 목록으로는 만들 수 없습니다.

                `SUCCEEDED` · `FAILED` 등 **분석 상태 값마다 키가 항상 있고**, 0건이면 0으로 옵니다 —
                키가 빠지는 것과 0건인 것은 다릅니다.

                ⚠️ **여기에 `notAnalyzedCount`를 더해야 전체가 됩니다.** 한 번도 분석하지 않은 교안은
                상태 자체가 없어 어느 키에도 들어가지 않습니다.
                """, example = "{\"SUCCEEDED\": 9, \"FAILED\": 1}")
        Map<CurriculumAnalysisStatus, Long> statusCounts,

        @Schema(description = """
                한 번도 분석하지 않은 교안 수입니다. `statusCounts`의 어느 키에도 들어가지 않으므로
                **`statusCounts`의 합 + 이 값 = 기관 전체 교안 수**입니다.
                """, example = "2")
        long notAnalyzedCount
) {
}
