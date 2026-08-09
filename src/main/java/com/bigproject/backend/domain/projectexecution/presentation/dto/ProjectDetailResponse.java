package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.application.ProjectService.ProjectDetail;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectLifecycleStatus;
import com.bigproject.backend.domain.projectexecution.domain.ProjectReadiness;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 프로젝트 상세 한 건. {@code ProjectResponse}(목록 항목)의 <b>모든 필드를 그대로 포함</b>하고
 * 상세 화면이 쓰는 세 목록을 더한 것이다 — 목록에서 쓰던 코드를 그대로 쓸 수 있다.
 *
 * <p>목록 응답과 스키마를 나눈 이유는 이 세 목록이 <b>회차마다 조회를 부르기 때문</b>이다.
 * 목록에 실으면 회차 6~8건짜리 한 화면이 교안·개념 전량을 끌고 온다. 목록에는 숫자 셋만 두고,
 * 목록이 답하지 못하는 값은 여기에 담는다(9차 R1).
 */
@Schema(description = "프로젝트 상세 정보")
public record ProjectDetailResponse(
        @Schema(description = "프로젝트 ID") UUID projectId,
        @Schema(description = "소속 기수 ID") UUID cohortId,
        @Schema(description = "프로젝트명") String name,
        @Schema(description = "기수 내 순번") Integer sequenceNo,
        ProjectCategory category,
        ProjectLifecycleStatus status,
        // 9차 Q1 ⓐ — 목록 항목과 같은 값·같은 규칙이다.
        ProjectReadiness readiness,
        @Schema(description = "시작일") LocalDate startDate,
        @Schema(description = "종료일. **날짜만이며 시각 의미가 없다** — 9차 Q2 참고", nullable = true) LocalDate endDate,

        @Schema(description = "연결된 교안 수 = curricula의 길이", example = "2") int curriculumCount,
        @Schema(description = "확정된 검증 개념 수 = concepts의 길이", example = "3") int conceptCount,
        @Schema(description = """
                검증 개념 후보 수. 화면의 `후보 12건에서 3건`에서 앞 숫자다.
                연결된 교안들의 **승인된(ACTIVE) 매핑 전체** 개수이며
                `GET /projects/{projectId}/concept-candidates` 응답의 길이와 같다 —
                후보 목록 자체가 필요 없는 화면은 이 숫자만 쓰면 된다.""", example = "12")
        int conceptCandidateCount,

        @Schema(description = "연결된 교안 목록. 순서는 연결 순서(sequenceNo). 없으면 빈 배열")
        List<LinkedCurriculum> curricula,

        @Schema(description = """
                확정된 검증 개념 목록. 순서는 확정할 때의 순서다.
                아직 확정하지 않았으면 빈 배열이며, 확정했다면 그때 보낸 개수 그대로다.""")
        List<ConfirmedConcept> concepts,

        @Schema(description = "`PUT /projects/{projectId}/requirements`로 보낸 그 문구 배열. 순서도 그대로다")
        List<String> requirementTitles
) {

    @Schema(name = "ProjectLinkedCurriculum", description = "프로젝트에 연결된 교안 한 건")
    public record LinkedCurriculum(
            @Schema(description = "연결 ID. 연결 해제에 이 ID를 쓴다") UUID projectCurriculumId,
            @Schema(description = "연결된 교안 버전 ID") UUID curriculumVersionId,
            @Schema(description = "교안(자료) ID. 버전이 바뀌어도 유지되는 안정 식별자라 교안 화면으로 이동할 때 쓴다",
                    nullable = true) UUID materialId,
            @Schema(description = "업로드된 파일명", example = "spring_backend_v1.pdf", nullable = true)
            String originalFileName,
            @Schema(description = "교안 버전 번호", example = "1", nullable = true) Integer versionNo,
            @Schema(description = "연결 시각") OffsetDateTime linkedAt
    ) {
    }

    @Schema(name = "ProjectConfirmedConcept", description = "확정된 검증 개념 한 건")
    public record ConfirmedConcept(
            @Schema(description = "출처 매핑 ID") UUID mappingId,
            @Schema(description = "공용 개념 원장 ID. 현황·리포트가 개념을 가리킬 때 쓰는 값") UUID teachesId,
            @Schema(description = "개념 이름", example = "트랜잭션 경계 설정", nullable = true) String extractedName,
            @Schema(description = "출처 교안 버전 ID. 없으면 교안 위치를 가리킬 수 없다", nullable = true)
            UUID curriculumVersionId,
            @Schema(description = "출처 시작 페이지. 구성 탭의 `· spring_backend_v1 v1 · p.53`", nullable = true)
            Integer pageStart,
            @Schema(description = "출처 끝 페이지", nullable = true) Integer pageEnd
    ) {
    }

    public static ProjectDetailResponse from(ProjectDetail detail) {
        Project project = detail.summary().project();
        return new ProjectDetailResponse(
                project.getProjectId(),
                project.getCohortId(),
                project.getName(),
                project.getSequenceNo(),
                project.getProjectCategory(),
                project.getLifecycleStatus(),
                detail.summary().readiness(),
                project.getStartDate(),
                project.getEndDate(),
                detail.summary().curriculumCount(),
                detail.summary().conceptCount(),
                detail.summary().conceptCandidateCount(),
                detail.curricula().stream()
                        .map(curriculum -> new LinkedCurriculum(
                                curriculum.projectCurriculumId(),
                                curriculum.curriculumVersionId(),
                                curriculum.materialId(),
                                curriculum.originalFileName(),
                                curriculum.versionNo(),
                                curriculum.linkedAt()))
                        .toList(),
                detail.concepts().stream()
                        .map(concept -> new ConfirmedConcept(
                                concept.mappingId(),
                                concept.teachesId(),
                                concept.extractedName(),
                                concept.curriculumVersionId(),
                                concept.pageStart(),
                                concept.pageEnd()))
                        .toList(),
                detail.requirementTitles());
    }
}
