package com.bigproject.backend.domain.curriculum.presentation.dto;

import com.bigproject.backend.domain.projectexecution.application.ProjectService.CurriculumUsingProject;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * 이 교안을 쓰고 있는 회차 한 건(11차 R3).
 *
 * <p>예전에는 {@code ["미니프로젝트 4차", "미니프로젝트 5차"]} 처럼 <b>이름 배열</b>이었다.
 * 그래서 화면이 재분석 경고를 좁힐 수 없었다 — 응시가 시작됐는지 알 방법이 없어
 * "연결된 회차가 하나라도 있으면 경고"가 됐고, 늘 뜨는 경고는 아무도 읽지 않는다.
 *
 * <p>{@code attendedCount}가 그 문턱을 정한다. 0이면 아직 아무도 응시하지 않은 회차라
 * 다시 분석해도 이미 발행된 리포트가 어긋나지 않는다.
 */
@Schema(description = "교안을 사용 중인 회차")
public record CurriculumUsingProjectResponse(
        @Schema(description = "회차 ID. 회차 이름을 눌러 프로젝트 화면으로 보낼 때 쓴다") UUID projectId,

        @Schema(description = "회차 이름(기수 안에서 붙인 이름)", example = "미니프로젝트 4차") String name,

        @Schema(description = "기수를 포함한 회차 라벨. 같은 교안이 여러 기수에 쓰이면 "
                + "`미프 4차`만으로는 구분되지 않아 기수를 붙인다", example = "9기 미프 4차", nullable = true)
        String roundLabel,

        @Schema(description = "기수 ID") UUID cohortId,

        @Schema(description = "기수 이름", example = "9기", nullable = true) String cohortName,

        @Schema(description = """
                **응시를 시작한 인원.** 재분석 경고의 문턱이다 — 0이면 아직 아무도 응시하지 않아
                다시 분석해도 발행된 리포트가 어긋나지 않는다.

                완료한 인원이 아니라 **시작한 인원**이다. 완료만 세면 진행 중인 응시가 빠져
                이미 문항을 받은 학생이 있는 회차를 경고 없이 지나가게 된다.
                """, example = "24") int attendedCount,

        @Schema(description = "이 회차가 지금 쓰고 있는 확정 검증 개념 이름. 재분석하면 교안 위치가 "
                + "어긋날 개념들이다. 확정 전이면 빈 배열") List<String> conceptNames
) {
    public static CurriculumUsingProjectResponse from(CurriculumUsingProject project) {
        return new CurriculumUsingProjectResponse(
                project.projectId(),
                project.name(),
                project.roundLabel(),
                project.cohortId(),
                project.cohortName(),
                project.attendedCount(),
                project.conceptNames());
    }
}
