package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.application.ProjectService.ProjectList;
import com.bigproject.backend.domain.projectexecution.domain.ProjectLifecycleStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * 프로젝트 목록 응답(9차 R3).
 *
 * <p>배열을 그대로 내려주던 것을 감쌌다. <b>{@code counts}를 실을 자리가 필요했기 때문</b>이다 —
 * 상태별 개수는 필터와 무관한 모집단 기준이라 걸러진 배열에서는 셀 수 없다. 9차 R3에서
 * "이것 하나가 실제 결함"이라고 짚어 주신 그 값이다.
 *
 * <p>기수 목록({@code CohortListResponse})·반 목록({@code ClassroomListResponse})·매니저 목록
 * ({@code ManagerRosterResponse})이 이미 같은 모양이라 목록 응답의 생김새가 도메인마다 갈리지 않는다.
 */
@Schema(description = "기수 프로젝트 목록 응답")
public record ProjectListResponse(
        @Schema(description = "필터·정렬이 적용된 프로젝트 목록") List<ProjectResponse> projects,

        @Schema(description = "**필터 적용 후** 개수. `projects`의 길이와 항상 같다", example = "3")
        int total,

        @Schema(description = """
                상태별 프로젝트 수이며 **필터를 적용하지 않은 기수 전체 모집단**이라 `total`과 다릅니다.
                화면 상단의 '총 7개'와 상태 드롭다운의 '준비 중 2 · 진행 중 1 · 종료 2'가 이 값이며,
                상태 칩이 자기 자신을 필터링하면 안 되므로 걸러진 목록으로는 만들 수 없습니다.
                PLANNED · RUNNING · CLOSED 세 키가 **항상 모두 있고**, 0건인 상태는 0으로 옵니다 —
                키가 빠지는 것과 0건인 것은 다릅니다. 세 값을 더하면 이 기수의 전체 회차 수입니다.
                """, example = "{\"PLANNED\": 2, \"RUNNING\": 1, \"CLOSED\": 2}")
        Map<ProjectLifecycleStatus, Long> counts
) {
    public static ProjectListResponse from(ProjectList list) {
        List<ProjectResponse> projects = list.projects().stream()
                .map(ProjectResponse::from)
                .toList();
        return new ProjectListResponse(projects, projects.size(), list.counts());
    }
}
