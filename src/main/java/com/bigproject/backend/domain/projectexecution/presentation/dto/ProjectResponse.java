package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.projectexecution.application.ProjectService.ProjectSummary;
import com.bigproject.backend.domain.projectexecution.domain.Project;
import com.bigproject.backend.domain.projectexecution.domain.ProjectCategory;
import com.bigproject.backend.domain.projectexecution.domain.ProjectLifecycleStatus;
import com.bigproject.backend.domain.projectexecution.domain.ProjectReadiness;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "프로젝트 정보")
public record ProjectResponse(
        @Schema(description = "프로젝트 ID") UUID projectId,
        @Schema(description = "소속 기수 ID") UUID cohortId,
        @Schema(description = "프로젝트명") String name,
        @Schema(description = "기수 내 순번") Integer sequenceNo,
        // 값 목록은 공유 스키마(ProjectCategory·ProjectStatus)에 한 곳만 둔다. 여기에 description을
        // 적으면 $ref 형제 키가 되어 어차피 버려지므로, 설명도 enum 쪽에 적었다.
        ProjectCategory category,
        ProjectLifecycleStatus status,
        // 9차 Q1 ⓐ — 준비 상태는 서버가 판정한다. status(시간 축)와 별개인 구성 축이라 필드를 나눴다.
        // 화면의 4값은 `status === 'PLANNED' ? readiness : status` 한 줄로 만들어진다.
        ProjectReadiness readiness,
        @Schema(description = "시작일") LocalDate startDate,
        // 10차 R4 — nullable=true를 뺐다. project.end_date가 DB에서 NOT NULL이고 생성·수정 요청도
        // 둘 다 @NotNull이라 null인 회차는 만들 방법이 없다. 타입만 null을 허용하고 있어서 화면이
        // `마감 미설정` 분기를 그려 놓고 영원히 확인하지 못했다.
        @Schema(description = "종료일. **날짜만이며 시각 의미가 없다** — 9차 Q2 참고. "
                + "항상 값이 있다(생성·수정 모두 필수이며 DB도 NOT NULL이다)") LocalDate endDate,

        // 22차 R5 — 목록과 대시보드가 상세와 다른 마감을 말하면 안 된다. 여태 이 값이 없어서
        // 화면이 endDate를 마감이라고 그렸고, 9기 5차가 실제 마감과 12일 어긋난 채 표시됐다.
        @Schema(description = """
                **실제 제출 마감 시각**이며 `endDate`와 다른 값이다.

                `endDate`는 회차 기간의 종료 **날짜**일 뿐 시각 의미가 없다. 화면의 「제출 마감」은
                반드시 이 값으로 그려야 한다 — 둘은 서버에서 연결돼 있지 않아 기간을 늘려도
                마감은 움직이지 않는다.

                22차 이전에 만들어져 **회차 레코드가 없는 프로젝트는 `null`**이다.""",
                example = "2026-08-21T14:59:00Z", nullable = true)
        Instant submissionDueAt,

        // 22차 R10 ⓐ — `3차 / 6회`의 분모. sequenceNo(분자)는 있는데 이것이 없어서
        // 대시보드가 /projects/current를 못 쓰고 목록을 계속 부르고 있었다.
        @Schema(description = """
                이 기수의 **전체 회차 수**이며 화면의 `3차 / 6회`에서 뒤 숫자다.
                `sequenceNo`가 분자이고 이 값이 분모다.

                **필터와 무관한 모집단**이다 — 목록을 `?status=RUNNING`으로 걸러도 이 값은 줄지 않는다.
                걸러진 개수는 `ProjectListResponse.total`이다.

                회차 수를 세지 않는 응답(상세 조회 · 생성·수정 직후)에서는 `0`이다.""",
                example = "6")
        int totalRounds,

        // ── 9차 R1: 목록 화면이 셀마다 그리는 세 숫자 ────────────────────────────
        // 목록에 회차가 6~8건인데 화면이 직접 세려면 회차마다 교안·개념·후보를 따로 물어야 한다.
        // 목록 항목에 실어 주면 목록 조회 한 번으로 끝난다.
        @Schema(description = "연결된 교안 수. 0이면 화면의 `교안 연결 안 됨`", example = "2") int curriculumCount,
        @Schema(description = "확정된 검증 개념 수. 화면의 `검증 개념 2 / 3건`에서 분자", example = "3") int conceptCount,
        @Schema(description = "검증 개념 후보 수. 화면의 `후보 12건에서 3건`에서 앞 숫자", example = "12") int conceptCandidateCount,

        // ── 18차 R3: 개수만으로는 목록에서 확인할 수 없던 것 ──────────────────────
        // `교안 1개`만 보여서, 교안으로 걸러 7건이 나와도 **무엇이 걸린 것인지** 표에서 알 수 없었다.
        // 다른 교안으로 걸어 0건이 나왔을 때 "정말 없어서"인지 "필터가 안 먹어서"인지도 구분되지 않았다.
        // 개념도 같다 — `3건 확정`이 아니라 무엇을 확정했는지가 그 회차의 정체다.
        @Schema(description = """
                연결된 교안 파일명. 순서는 연결 순서(`sequence_no`)다.

                `curriculumCount`와 길이가 <b>다를 수 있다</b> — 개수는 연결 행을 그대로 세지만
                이름은 못 찾은 항목이 빠진다. 개수 표시에는 `curriculumCount`를 쓸 것.""",
                example = "[\"spring_backend_v1.pdf\"]")
        List<String> curriculumNames,

        @Schema(description = """
                확정된 검증 개념 이름. 순서는 확정 순서(`sequence_no`)다.

                `conceptCount`와 길이가 다를 수 있는 이유는 위와 같다.""",
                example = "[\"예외 처리와 롤백 전략\", \"API 응답 계약 설계\"]")
        List<String> conceptNames,

        // ── MG-07: 매니저 프로젝트 목록의 '진행'·'조치' 열 ──────────────────────
        // SubmissionStatusService.ManagerProjectProgress를 그대로 옮기지 않고 이 record로 한 번 감싼다 —
        // presentation DTO가 application 계층의 내부 응답 타입을 그대로 노출하지 않기 위해서다.
        @Schema(description = """
                매니저 담당 반 기준 진행 현황. **매니저 전용 목록(`GET /projects`)에서만 채워진다.**

                오퍼레이터 목록(`GET /cohorts/{cohortId}/projects`)이나 생성·수정 직후 응답에서는
                항상 `null`이다 — 계산 자체를 하지 않는다.

                빅프로젝트 행이거나(개인 커밋 영역이라 반별 집계 대상이 아님) 회차가 `PLANNED`거나
                아직 회차가 없으면 `null`이다.""",
                nullable = true)
        Progress progress,

        @Schema(description = """
                매니저가 바로 조치할 수 있는 항목 목록. 반별 미제출·분석 실패 팀을 담는다.

                **매니저 전용 목록에서만 채워진다.** 조치가 필요 없으면 빈 배열이며,
                **그것이 정상이다** — 화면은 —로 그리면 된다.""")
        List<ActionItem> actionItems
) {
    public static ProjectResponse from(ProjectSummary summary) {
        Project project = summary.project();
        return new ProjectResponse(
                project.getProjectId(),
                project.getCohortId(),
                project.getName(),
                project.getSequenceNo(),
                project.getProjectCategory(),
                project.getLifecycleStatus(),
                summary.readiness(),
                project.getStartDate(),
                project.getEndDate(),
                summary.submissionDueAt(),
                summary.totalRounds(),
                summary.curriculumCount(),
                summary.conceptCount(),
                summary.conceptCandidateCount(),
                summary.curriculumNames(),
                summary.conceptNames(),
                null,
                List.of()
        );
    }

    /** 매니저 목록 enrichWithProgress()가 진행·조치를 계산한 뒤 붙일 때 쓴다. 다른 필드는 그대로 복사한다. */
    public ProjectResponse withProgress(Progress progress, List<ActionItem> actionItems) {
        return new ProjectResponse(
                projectId, cohortId, name, sequenceNo, category, status, readiness,
                startDate, endDate, submissionDueAt, totalRounds,
                curriculumCount, conceptCount, conceptCandidateCount,
                curriculumNames, conceptNames,
                progress, actionItems
        );
    }

    /** 화면의 `응시 58/71` + 그 아래 `C반이 12/23`. */
    @Schema(description = "매니저 담당 반 진행 합계")
    public record Progress(
            @Schema(description = "담당 반 전체에서 응시(완료)를 마친 인원") long assessedCount,
            @Schema(description = "담당 반 전체 대상 인원") long targetTraineeCount,
            @Schema(description = "담당 반이 둘 이상일 때, 진행률이 가장 낮은 반. 담당 반이 하나뿐이면 null",
                    nullable = true) LaggingClass laggingClass) {
    }

    /** 담당 반 중 진행률이 가장 낮은 반. */
    @Schema(description = "담당 반 중 진행률이 가장 낮은 반")
    public record LaggingClass(
            UUID classId,
            String className,
            @Schema(description = "그 반에서 응시(완료)를 마친 인원") long assessedCount,
            @Schema(description = "그 반의 전체 대상 인원") long targetTraineeCount) {
    }

    /** 화면의 `A반 미제출 2팀` 한 줄. */
    @Schema(description = "매니저가 조치할 수 있는 항목")
    public record ActionItem(
            UUID classId,
            String className,
            @Schema(description = "UNSUBMITTED_TEAMS(제출 마감 지남·미제출) · ANALYSIS_FAILED_TEAMS(제출했으나 분석 실패)",
                    example = "UNSUBMITTED_TEAMS") String type,
            @Schema(description = "해당 유형에 걸린 팀 수") int teamCount) {
    }
}