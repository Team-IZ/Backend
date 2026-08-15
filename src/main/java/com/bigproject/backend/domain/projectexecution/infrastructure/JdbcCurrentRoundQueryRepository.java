package com.bigproject.backend.domain.projectexecution.infrastructure;

import com.bigproject.backend.domain.projectexecution.domain.CurrentRoundQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcCurrentRoundQueryRepository implements CurrentRoundQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * round_status='OPEN'인 행 중 제출 마감이 가장 이른 것 하나만 가져온다.
     * 여러 프로젝트에 동시 소속되어 OPEN 회차가 둘 이상 잡히는 경우의 우선순위는 프론트 확인 전이다.
     */
    @Override
    public Optional<CurrentRoundRow> findCurrentRound(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT
                    project_id, project_name, assessment_round_id, round_no, round_name,
                    curriculum_display_names,
                    current_submission_id, submission_status, submitted_at, submission_due_at,
                    can_submit, can_resubmit,
                    analysis_job_status, analysis_failure_code,
                    initial_attempt_id, initial_attempt_status, initial_terminal_reason_code,
                    assessment_open_at, assessment_close_at,
                    latest_review_attempt_id, review_status, review_due_at,
                    report_id, report_publish_status,
                    warning_codes, representative_status, default_action_code, action_unavailable_reason_code,
                    manager_name, commit_email_status
                FROM trainee_home_round_view
                WHERE trainee_user_id = ?
                    AND round_status = 'OPEN'
                ORDER BY submission_due_at ASC NULLS LAST
                LIMIT 1
                """,
                (rs, rowNum) -> new CurrentRoundRow(
                        rs.getObject("project_id", UUID.class),
                        rs.getString("project_name"),
                        rs.getObject("assessment_round_id", UUID.class),
                        rs.getInt("round_no"),
                        rs.getString("round_name"),
                        textArray(rs, "curriculum_display_names"),
                        rs.getObject("current_submission_id", UUID.class),
                        rs.getString("submission_status"),
                        instant(rs, "submitted_at"),
                        instant(rs, "submission_due_at"),
                        rs.getBoolean("can_submit"),
                        rs.getBoolean("can_resubmit"),
                        rs.getString("analysis_job_status"),
                        rs.getString("analysis_failure_code"),
                        rs.getObject("initial_attempt_id", UUID.class),
                        rs.getString("initial_attempt_status"),
                        rs.getString("initial_terminal_reason_code"),
                        instant(rs, "assessment_open_at"),
                        instant(rs, "assessment_close_at"),
                        rs.getObject("latest_review_attempt_id", UUID.class),
                        rs.getString("review_status"),
                        instant(rs, "review_due_at"),
                        rs.getObject("report_id", UUID.class),
                        rs.getString("report_publish_status"),
                        textArray(rs, "warning_codes"),
                        rs.getString("representative_status"),
                        rs.getString("default_action_code"),
                        rs.getString("action_unavailable_reason_code"),
                        rs.getString("manager_name"),
                        rs.getString("commit_email_status")
                ),
                userId
        ).stream().findFirst();
    }

    private List<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((String[]) array.getArray()).filter(java.util.Objects::nonNull).toList();
    }

    /** TIMESTAMPTZ → Instant. null 컬럼을 0 epoch로 만들지 않으려면 getTimestamp를 거쳐야 한다. */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}