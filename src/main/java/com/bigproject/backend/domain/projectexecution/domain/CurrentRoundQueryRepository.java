package com.bigproject.backend.domain.projectexecution.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TR-01(교육생 홈) "지금 할 일 하나" 조회 포트.
 *
 * <p>원천은 {@code trainee_home_round_view}(회차 + 사용자 grain) — TR-01 전용으로 이미 만들어진
 * BFF 조립 뷰다. 이 뷰가 {@code representative_status}·{@code default_action_code}를 직접 계산해
 * 주므로, 이 포트 위에서는 판정 로직을 다시 짜지 않고 값을 그대로 옮겨 담기만 한다.
 */
public interface CurrentRoundQueryRepository {

    /**
     * {@code round_status='OPEN'}인 행만 대상으로 한다. 한 사용자가 동시에 여러 프로젝트에서
     * OPEN 회차를 가질 수 있는 경우의 우선순위(지금은 제출 마감이 가장 이른 것 하나)는
     * 프론트와 확정되지 않았다.
     */
    Optional<CurrentRoundRow> findCurrentRound(UUID userId);

    record CurrentRoundRow(
            UUID projectId,
            String projectName,
            UUID assessmentRoundId,
            int roundNo,
            String roundName,
            List<String> curriculumDisplayNames,

            UUID currentSubmissionId,
            String submissionStatus,
            Instant submittedAt,
            Instant submissionDueAt,
            boolean canSubmit,
            boolean canResubmit,

            String analysisJobStatus,
            String analysisFailureCode,

            UUID initialAttemptId,
            String initialAttemptStatus,
            String initialTerminalReasonCode,
            Instant assessmentOpenAt,
            Instant assessmentCloseAt,

            UUID latestReviewAttemptId,
            String reviewStatus,
            Instant reviewDueAt,

            UUID reportId,
            /** NOT_PUBLISHED · GENERATING · PUBLISHED */
            String reportPublishStatus,

            List<String> warningCodes,
            /** SUBMISSION_REQUIRED · SUBMISSION_MISSED · ANALYZING · ... — {@link CurrentRoundStatus} 이름과 1:1 */
            String representativeStatus,
            /** {@link DefaultActionCode} 이름과 1:1 */
            String defaultActionCode,
            String actionUnavailableReasonCode,

            String managerName,
            /** 커밋 이메일 미등록이면 null */
            String commitEmailStatus
    ) {
    }
}
