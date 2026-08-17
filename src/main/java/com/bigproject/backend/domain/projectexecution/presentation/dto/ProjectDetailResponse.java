package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.application.ProjectService.ProjectDetail;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectDependencyRepository;
import com.bigproject.backend.domain.projectexecution.domain.ProjectLifecycleStatus;
import com.bigproject.backend.domain.projectexecution.domain.ProjectReadiness;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
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
        // 10차 R4 — 목록 항목과 같은 이유로 nullable=true를 뺐다(ProjectResponse.endDate 주석 참고).
        @Schema(description = "종료일. **날짜만이며 시각 의미가 없다** — 9차 Q2 참고. "
                + "항상 값이 있다(생성·수정 모두 필수이며 DB도 NOT NULL이다)") LocalDate endDate,

        /*
         * 22차 R5 — 마감을 정하고 보여주는 자리가 회차 상세인데 그 화면이 읽는 이 응답에 값이 없었다.
         * class-progress에도 있지만 그것은 현황 탭의 조회이고 PLANNED 회차에서는 답하지 못한다.
         * 일정 수정 모달의 초기값이기도 해서, 없으면 고쳐도 다시 열었을 때 시각 입력이 비어 있었다.
         */
        @Schema(description = """
                **실제 제출 마감 시각**이며 `endDate`와 다른 값이다. 개요 타임라인의 「제출 마감」과
                **일정 수정 모달의 초기값**이 이 값이다.

                `endDate`는 기간의 종료 날짜일 뿐 시각 의미가 없고 서버에서 이 값과 연결돼 있지 않다.
                22차 이전에 만들어져 **회차 레코드가 없는 프로젝트는 `null`**이다.""",
                example = "2026-08-21T14:59:00Z", nullable = true)
        Instant submissionDueAt,

        /*
         * 22차 R9 — 교육생 홈(CurrentRoundResponse)은 date-time을 9개 받는데 운영자 회차 상세는
         * 0개였다. 서버는 응시 창·리포트 발행 시각을 이미 계산해 두고 교육생에게만 주고 있었고,
         * 그래서 개요 타임라인이 "코드 분석 완료 시점부터 24시간" 같은 규칙 문장만 그렸다.
         * 운영자가 「이 회차 응시 창이 언제 닫히나」에 답할 수 없는 상태였다 — 그 문의를 받는
         * 사람이 정작 시각을 모른다.
         *
         * 개인별 값(assessmentOpenAt 같은 것)은 싣지 않는다. 회차 단위가 아니라 사람마다 다르고,
         * 운영자에게 필요한 것은 회차의 창이다(프론트가 요청서에서 직접 빼도 된다고 했다).
         */
        @Schema(description = """
                🔴 **폐기된 필드. 언제나 `null`이다**(2026-08-16).

                회차 공통 응시 창 시작이었다. 컬럼이 폐기돼 `project_assessment_round`의 전 행이
                `null`이며 다시 채우는 코드 경로도 없다. 계약은 화면이 깨지지 않도록 남겨 둔다.

                32차 R5 — `UpcomingRoundResponse`·`CurrentRoundResponse`에만 이 표시가 붙고
                여기는 빠져 있었다. 같은 값을 주는 자리라 표시도 같아야 한다.""",
                nullable = true)
        Instant roundAssessmentOpenAt,

        @Schema(description = """
                🔴 **폐기된 필드. 언제나 `null`이다**(2026-08-16). `roundAssessmentOpenAt`과 같다.

                ⚠️ 종전 설명이 *"개요 타임라인의 `응시 창`이 이 값으로 그려진다"* 라고 적고 있었는데,
                그 지시를 따르면 **영원히 그릴 수 없다.** 32차 R5로 정정한다.

                ## 회차 단위 응시 창은 이제 없다

                응시 창은 **개인별**이다. 팀 분석이 끝난 시각부터 사람마다 따로 열리므로
                (`measurement_attempt.assessment_open_at` · `assessment_close_at`), 회차 하나를
                가리키는 구간이 존재하지 않는다.

                개요 타임라인에는 회차 단위로 확정된 값들만 쓰면 된다.

                | 구간 | 값 |
                |---|---|
                | 제출 마감 | `submissionDueAt` |
                | 리포트 발행 하한 | `reportPublishNotBeforeAt` |

                「응시 창」 구간이 꼭 필요하면 그 회차 수행들의 **실제** 창 범위
                (`MIN(assessment_open_at)` ~ `MAX(assessment_close_at)`)가 유일한 근거다.
                사후적이지만 진행 상황을 그리는 자리라면 맞는 값이다 — **필요하다고 하시면
                회차 단위로 접어 내려보내겠다.** 지금은 쓰는 화면이 없어 넣지 않았다.""",
                nullable = true)
        Instant roundAssessmentDueAt,

        @Schema(description = """
                리포트를 이 시각 **이전에는 발행하지 않는다**는 하한이다. 타임라인의 `리포트 발행`이
                이 값으로 그려진다.

                응시가 닫혀야 정해지므로 그 전에는 `null`이다. DB가 `submission_due_at`·
                `assessment_due_at`보다 뒤일 것을 CHECK로 강제한다.""",
                example = "2026-08-24T00:00:00Z", nullable = true)
        Instant reportPublishNotBeforeAt,

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

    /**
     * 확정된 검증 개념 한 건.
     *
     * <p>10차 Q2 — 아래 네 필드에서 {@code nullable = true}를 뺐다. <b>null이 올 수 없다.</b>
     * 값의 출처인 {@code curriculum_teaches_mapping}에서 {@code extracted_name}·{@code version_id}·
     * {@code page_start}·{@code page_end}가 전부 NOT NULL이고, 그 행은
     * {@code fk_project_verification_concept_source_mapping_id ... ON DELETE RESTRICT} 때문에
     * <b>확정 개념이 참조하는 동안 지워지지 않는다</b>. 즉 "확정되면서 이름이 사라지는" 경로가 없다.
     */
    @Schema(name = "ProjectConfirmedConcept", description = "확정된 검증 개념 한 건")
    public record ConfirmedConcept(
            @Schema(description = "출처 매핑 ID") UUID mappingId,
            @Schema(description = "공용 개념 원장 ID. 현황·리포트가 개념을 가리킬 때 쓰는 값") UUID teachesId,
            @Schema(description = "개념 이름. 항상 값이 있다 — 출처 매핑이 NOT NULL이고 참조되는 동안 삭제되지 않는다",
                    example = "트랜잭션 경계 설정") String extractedName,
            @Schema(description = "출처 교안 버전 ID. 항상 값이 있다") UUID curriculumVersionId,
            @Schema(description = "출처 시작 페이지. 구성 탭의 `· spring_backend_v1 v1 · p.53`") Integer pageStart,
            @Schema(description = "출처 끝 페이지") Integer pageEnd
    ) {
    }

    public static ProjectDetailResponse from(ProjectDetail detail) {
        Project project = detail.summary().project();
        ProjectDependencyRepository.RoundSchedule schedule = detail.summary().schedule();
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
                detail.summary().submissionDueAt(),
                // 회차가 없는 프로젝트(22차 이전 생성)는 schedule 자체가 null이라 셋 다 null이다.
                schedule == null ? null : schedule.assessmentOpenAt(),
                schedule == null ? null : schedule.assessmentDueAt(),
                schedule == null ? null : schedule.reportPublishNotBeforeAt(),
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
