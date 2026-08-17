package com.bigproject.backend.domain.curriculum.presentation.dto;

import com.bigproject.backend.domain.curriculum.application.CurriculumService.LinkedCurriculum;
import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.projectexecution.application.ProjectService.CohortCurriculumLink;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = """
		이 기수의 회차에 **실제로 연결된** 교안 한 건입니다(30차 Q2).

		`GET /cohorts/{cohortId}/curricula`(연결 가능한 후보)와 다릅니다 — 그쪽은 오퍼레이터가
		회차에 붙일 수 있는 기관 전체 목록이고, 이쪽은 이미 붙어 있는 것만입니다.
		""")
public record CohortCurriculumResponse(
        @Schema(description = """
				`curriculum_version_id` — **이 기수가 실제로 연결한 버전**입니다.

				🔴 **상세로 갈 때 쓰는 ID가 아닙니다**(32차 Q3에서 정정). 종전 설명이 그렇게 적고
				있었는데 **`versionId`를 받는 오퍼레이션은 스펙 전체에 하나도 없습니다.** 그 설명을
				따르면 13차 R1이 그대로 재현됩니다 — 그때도 화면이 교안 축에 버전 ID를 넣어 늘 빈
				배열을 받았고, 목록은 「24개 회차에서 사용 중」인데 상세는 「쓰는 회차가 없습니다」로
				답했습니다.

				**상세로 갈 때는 `materialId`를 쓰세요.** 이 값은 화면에 버전 번호(`versionNo`)를
				표시하거나, 회차가 어느 버전을 물고 있는지 대조할 때 씁니다.
				""")
        UUID versionId,
        @Schema(description = """
				교안 원장 ID. **상세 셋과 「쓰인 회차」 조회가 모두 이 축을 받습니다.**

				`GET /curricula/{materialId}` · `/sections` · `/projects`
				""")
        UUID materialId,
        @Schema(description = "버전 번호", example = "2") Integer versionNo,
        @Schema(description = "원본 파일명", example = "AI_LLMOps_v2.pdf") String originalFileName,
        @Schema(description = "페이지 수. 분석 전이거나 확정되지 않았으면 null", example = "84", nullable = true)
        Integer pageCount,
        @Schema(description = """
                가장 최근 분석 **시도**의 상태입니다. **한 번도 분석하지 않았으면 `null`**이며
                실패(`FAILED`)와 구분해야 해서 값을 만들어 넣지 않습니다.
                """, nullable = true)
        CurriculumAnalysisStatus analysisStatus,
        @Schema(description = "승인된(ACTIVE) 가르친 항목 수", example = "34") int teachesCount,
        @Schema(description = "등록 시각") OffsetDateTime createdAt,

        @Schema(description = """
                이 교안을 연결한 **회차들**이며 차수 오름차순입니다. **비어 있지 않습니다** —
                연결이 있어야 이 목록에 들어옵니다.

                한 교안이 여러 회차에 걸리면 교안은 한 번만 나오고 그 회차들이 여기 모입니다.
                이 값이 없으면 기관 전체 교안 목록과 구분되지 않습니다 — 「이 교안이 3차에 쓰였다」가
                이 화면의 맥락 전부입니다.
                """)
        List<LinkedProject> linkedProjects
) {

    @Schema(name = "CohortCurriculumLinkedProject", description = """
			교안을 연결한 회차 하나. 미니프로젝트는 `round_no`가 늘 1이라 차수는 `sequenceNo`로 셉니다 —
			화면의 `미프 3차`가 이 값입니다.
			""")
    public record LinkedProject(
            @Schema(description = "회차(프로젝트) ID") UUID projectId,
            @Schema(description = "회차 이름", example = "미니프로젝트 3차") String projectName,
            @Schema(description = "기수 안의 차수", example = "3") int sequenceNo
    ) {
        public static LinkedProject from(CohortCurriculumLink link) {
            return new LinkedProject(link.projectId(), link.projectName(), link.sequenceNo());
        }
    }

    public static CohortCurriculumResponse from(LinkedCurriculum linked) {
        CurriculumVersion version = linked.version();
        return new CohortCurriculumResponse(
                version.getVersionId(),
                version.getMaterialId(),
                version.getVersionNo(),
                version.getOriginalFileName(),
                version.getPageCount(),
                linked.analysisStatus(),
                linked.teachesCount(),
                version.getCreatedAt(),
                linked.linkedProjects().stream().map(LinkedProject::from).toList());
    }
}
