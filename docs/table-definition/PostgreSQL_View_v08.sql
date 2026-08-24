create view assessment_round_attendance
            (org_id, cohort_id, project_id, assessment_round_id, class_id, team_id, user_id, round_no, round_name,
             round_status, submission_due_at, analysis_role_code, is_final, source_submission_id, submission_method,
             submission_status, submitted_at, submission_deadline_status, repository_id, latest_verification_id,
             repository_verification_status, repository_failure_code, submission_artifact_id,
             artifact_validation_status, artifact_failure_code, analysis_job_id, analysis_status, primary_attempt_id,
             primary_attempt_status, primary_terminal_reason_code, primary_assessment_open_at,
             primary_assessment_close_at, latest_retry_attempt_id, latest_retry_status,
             latest_retry_terminal_reason_code, latest_review_attempt_id, latest_review_status,
             latest_review_terminal_reason_code, primary_session_id, primary_session_status,
             primary_session_end_reason_code, completion_status, nudge_eligible, nudge_reason_code,
             nudge_exclusion_reason_code, latest_reminder_dispatch_id, latest_reminder_status, not_submitted_count,
             analysis_in_progress_count, analysis_failed_count, assessment_available_count,
             assessment_in_progress_count, completed_count, not_attended_count, session_incomplete_count,
             no_question_count, aggregation_status, as_of_at)
as
WITH b AS (SELECT r.org_id,
                  r.cohort_id,
                  p.project_id,
                  r.assessment_round_id,
                  pm.class_id,
                  tm.team_id,
                  pm.user_id,
                  r.round_no,
                  r.round_name,
                  r.status AS round_status,
                  r.submission_due_at,
                  r.analysis_role_code,
                  r.is_final
           FROM project_assessment_round r
                    JOIN project p ON p.project_id = r.project_id AND p.deleted_at IS NULL
                    JOIN project_membership pm ON pm.project_id = p.project_id AND pm.status::text = 'ACTIVE'::text
                    LEFT JOIN LATERAL ( SELECT x.team_id
                                        FROM team_membership x
                                        WHERE x.project_membership_id = pm.project_membership_id
                                          AND x.from_at <= COALESCE(r.submission_due_at, CURRENT_TIMESTAMP)
                                          AND (x.to_at IS NULL OR
                                               x.to_at > COALESCE(r.submission_due_at, CURRENT_TIMESTAMP))
                                        ORDER BY x.from_at DESC
                                        LIMIT 1) tm ON true
           WHERE r.deleted_at IS NULL)
SELECT b.org_id,
       b.cohort_id,
       b.project_id,
       b.assessment_round_id,
       b.class_id,
       b.team_id,
       b.user_id,
       b.round_no,
       b.round_name,
       b.round_status,
       b.submission_due_at,
       b.analysis_role_code,
       b.is_final,
       sub.submission_id                                                                                                                                                                                                             AS source_submission_id,
       sub.method                                                                                                                                                                                                                    AS submission_method,
       sub.status                                                                                                                                                                                                                    AS submission_status,
       sub.submitted_at,
       CASE
           WHEN sub.submission_id IS NOT NULL THEN
               CASE
                   WHEN sub.submitted_at > b.submission_due_at THEN 'LATE'::text
                   ELSE 'ON_TIME'::text
                   END
           WHEN CURRENT_TIMESTAMP > b.submission_due_at THEN 'MISSED'::text
           ELSE 'OPEN'::text
           END::character varying(100)                                                                                                                                                                                               AS submission_deadline_status,
       repo.repository_id,
       rv.verification_id                                                                                                                                                                                                            AS latest_verification_id,
       rv.status::character varying(100)                                                                                                                                                                                             AS repository_verification_status,
       rv.failure_code                                                                                                                                                                                                               AS repository_failure_code,
       art.artifact_id                                                                                                                                                                                                               AS submission_artifact_id,
       art.validation_status                                                                                                                                                                                                         AS artifact_validation_status,
       art.validation_failure_code                                                                                                                                                                                                   AS artifact_failure_code,
       aj.job_id                                                                                                                                                                                                                     AS analysis_job_id,
       aj.status                                                                                                                                                                                                                     AS analysis_status,
       pa.attempt_id                                                                                                                                                                                                                 AS primary_attempt_id,
       pa.status                                                                                                                                                                                                                     AS primary_attempt_status,
       pa.terminal_reason_code                                                                                                                                                                                                       AS primary_terminal_reason_code,
       pa.assessment_open_at                                                                                                                                                                                                         AS primary_assessment_open_at,
       pa.assessment_close_at                                                                                                                                                                                                        AS primary_assessment_close_at,
       ra.attempt_id                                                                                                                                                                                                                 AS latest_retry_attempt_id,
       ra.status                                                                                                                                                                                                                     AS latest_retry_status,
       ra.terminal_reason_code                                                                                                                                                                                                       AS latest_retry_terminal_reason_code,
       rev.attempt_id                                                                                                                                                                                                                AS latest_review_attempt_id,
       rev.status                                                                                                                                                                                                                    AS latest_review_status,
       rev.terminal_reason_code                                                                                                                                                                                                      AS latest_review_terminal_reason_code,
       sess.session_id                                                                                                                                                                                                               AS primary_session_id,
       sess.status                                                                                                                                                                                                                   AS primary_session_status,
       sess.end_reason_code                                                                                                                                                                                                          AS primary_session_end_reason_code,
       CASE
           WHEN pa.status::text = 'COMPLETED'::text THEN 'COMPLETED'::text
           WHEN pa.attempt_id IS NULL OR pa.status::text = 'NOT_STARTED'::text THEN 'NOT_STARTED'::text
           ELSE 'IN_PROGRESS'::text
           END::character varying(100)                                                                                                                                                                                               AS completion_status,
       CASE
           WHEN sub.submission_id IS NULL AND CURRENT_TIMESTAMP <= b.submission_due_at THEN true
           WHEN aj.status::text = 'FAILED'::text AND CURRENT_TIMESTAMP <= b.submission_due_at THEN true
           WHEN pa.attempt_id IS NOT NULL AND (pa.status::text <> ALL
                                               (ARRAY ['COMPLETED'::character varying::text, 'FAILED'::character varying::text, 'EXPIRED'::character varying::text])) AND
                pa.assessment_close_at > CURRENT_TIMESTAMP THEN true
           ELSE false
           END                                                                                                                                                                                                                       AS nudge_eligible,
       CASE
           WHEN sub.submission_id IS NULL AND CURRENT_TIMESTAMP <= b.submission_due_at THEN 'TEAM_SUBMISSION_MISSING'::text
           WHEN aj.status::text = 'FAILED'::text AND CURRENT_TIMESTAMP <= b.submission_due_at
               THEN 'TEAM_ANALYSIS_FAILED'::text
           WHEN pa.attempt_id IS NOT NULL AND (pa.status::text <> ALL
                                               (ARRAY ['COMPLETED'::character varying::text, 'FAILED'::character varying::text, 'EXPIRED'::character varying::text])) AND
                pa.assessment_close_at > CURRENT_TIMESTAMP THEN 'INDIVIDUAL_ASSESSMENT_NOT_STARTED'::text
           ELSE NULL::text
           END::character varying(100)                                                                                                                                                                                               AS nudge_reason_code,
       CASE
           WHEN sub.submission_id IS NULL AND CURRENT_TIMESTAMP > b.submission_due_at
               THEN 'NUDGE_SUBMISSION_DEADLINE_PASSED'::text
           WHEN pa.assessment_close_at IS NOT NULL AND pa.assessment_close_at <= CURRENT_TIMESTAMP
               THEN 'NUDGE_ASSESSMENT_WINDOW_CLOSED'::text
           WHEN rd.dispatch_id IS NOT NULL AND
                (rd.status::text = ANY (ARRAY ['PENDING'::character varying::text, 'SENT'::character varying::text]))
               THEN 'NUDGE_DUPLICATE_SUPPRESSED'::text
           ELSE NULL::text
           END::character varying(100)                                                                                                                                                                                               AS nudge_exclusion_reason_code,
       rd.dispatch_id                                                                                                                                                                                                                AS latest_reminder_dispatch_id,
       rd.status::character varying(100)                                                                                                                                                                                             AS latest_reminder_status,
       count(*)
       FILTER (WHERE sub.submission_id IS NULL) OVER (PARTITION BY b.assessment_round_id, b.class_id)::integer                                                                                                                       AS not_submitted_count,
       count(*) FILTER (WHERE aj.status::text = ANY
                              (ARRAY ['QUEUED'::character varying::text, 'RUNNING'::character varying::text])) OVER (PARTITION BY b.assessment_round_id, b.class_id)::integer                                                        AS analysis_in_progress_count,
       count(*)
       FILTER (WHERE aj.status::text = 'FAILED'::text) OVER (PARTITION BY b.assessment_round_id, b.class_id)::integer                                                                                                                AS analysis_failed_count,
       count(*) FILTER (WHERE pa.status::text = 'SESSION_READY'::text OR pa.assessment_open_at <= CURRENT_TIMESTAMP AND
                                                                         pa.assessment_close_at > CURRENT_TIMESTAMP AND
                                                                         pa.status::text =
                                                                         'NOT_STARTED'::text) OVER (PARTITION BY b.assessment_round_id, b.class_id)::integer                                                                         AS assessment_available_count,
       count(*) FILTER (WHERE pa.status::text = 'SESSION_IN_PROGRESS'::text OR (sess.status::text = ANY
                                                                                (ARRAY ['IN_PROGRESS'::character varying::text, 'PAUSED'::character varying::text]))) OVER (PARTITION BY b.assessment_round_id, b.class_id)::integer AS assessment_in_progress_count,
       count(*)
       FILTER (WHERE pa.status::text = 'COMPLETED'::text) OVER (PARTITION BY b.assessment_round_id, b.class_id)::integer                                                                                                             AS completed_count,
       count(*) FILTER (WHERE pa.terminal_reason_code::text = ANY
                              (ARRAY ['NOT_ATTENDED'::character varying::text, 'NOT_SUBMITTED'::character varying::text])) OVER (PARTITION BY b.assessment_round_id, b.class_id)::integer                                            AS not_attended_count,
       count(*) FILTER (WHERE pa.terminal_reason_code::text = 'SESSION_INCOMPLETE'::text OR sess.status::text =
                                                                                            'INTERRUPTED'::text) OVER (PARTITION BY b.assessment_round_id, b.class_id)::integer                                                      AS session_incomplete_count,
       count(*) FILTER (WHERE pa.terminal_reason_code::text = ANY
                              (ARRAY ['INSUFFICIENT_PROBLEM_EVIDENCE'::character varying::text, 'INSUFFICIENT_OWN_COMMIT_EVIDENCE'::character varying::text])) OVER (PARTITION BY b.assessment_round_id, b.class_id)::integer        AS no_question_count,
       'COMPLETE'::character varying(20)                                                                                                                                                                                             AS aggregation_status,
       CURRENT_TIMESTAMP                                                                                                                                                                                                             AS as_of_at
FROM b
         LEFT JOIN LATERAL ( SELECT x.submission_id,
                                    x.org_id,
                                    x.team_id,
                                    x.assessment_round_id,
                                    x.method,
                                    x.repository_id,
                                    x.requested_branch,
                                    x.resolved_branch,
                                    x.source_commit_sha,
                                    x.source_commit_message,
                                    x.source_commit_committed_at,
                                    x.supersedes_submission_id,
                                    x.submitted_by,
                                    x.status,
                                    x.submitted_at,
                                    x.is_current,
                                    x.failure_reason,
                                    x.analysis_input_hash,
                                    x.git_history,
                                    x.analysis_input_file_count,
                                    x.analysis_input_captured_at,
                                    x.code_snippets,
                                    x.analysis_input_byte_count,
                                    x.repository_verification_id,
                                    x.request_idempotency_key
                             FROM submission x
                             WHERE x.team_id = b.team_id
                               AND x.assessment_round_id = b.assessment_round_id
                             ORDER BY x.is_current DESC, x.submitted_at DESC
                             LIMIT 1) sub ON true
         LEFT JOIN repository repo ON repo.repository_id = sub.repository_id
         LEFT JOIN repository_verification rv ON rv.verification_id = sub.repository_verification_id
         LEFT JOIN LATERAL ( SELECT x.artifact_id,
                                    x.submission_id,
                                    x.artifact_type,
                                    x.original_file_name,
                                    x.content_type,
                                    x.storage_uri,
                                    x.safe_extract_uri,
                                    x.content_hash,
                                    x.file_size_bytes,
                                    x.applied_max_file_bytes,
                                    x.validation_status,
                                    x.validation_failure_code,
                                    x.validated_at,
                                    x.extraction_policy_version
                             FROM submission_artifact x
                             WHERE x.submission_id = sub.submission_id
                             ORDER BY x.validated_at DESC NULLS LAST, x.artifact_id DESC
                             LIMIT 1) art ON true
         LEFT JOIN LATERAL ( SELECT x.job_id,
                                    x.org_id,
                                    x.assessment_round_id,
                                    x.team_id,
                                    x.submission_id,
                                    x.analysis_id,
                                    x.question_focus_version_no,
                                    x.batch_key,
                                    x.job_type,
                                    x.execution_no,
                                    x.status,
                                    x.started_at,
                                    x.completed_at,
                                    x.failure_reason,
                                    x.trace_id,
                                    x.external_job_id,
                                    x.extraction_scope_id,
                                    x.requested_model_id,
                                    x.question_budget,
                                    x.request_payload,
                                    x.request_payload_hash,
                                    x.payload_schema_version,
                                    x.failure_code,
                                    x.created_at
                             FROM analysis_job x
                             WHERE x.assessment_round_id = b.assessment_round_id
                               AND x.team_id = b.team_id
                               AND (sub.submission_id IS NULL OR x.submission_id = sub.submission_id)
                             ORDER BY x.execution_no DESC, x.started_at DESC NULLS LAST, x.job_id DESC
                             LIMIT 1) aj ON true
         LEFT JOIN LATERAL ( SELECT x.attempt_id,
                                    x.org_id,
                                    x.cohort_id,
                                    x.assessment_round_id,
                                    x.project_id,
                                    x.user_id,
                                    x.assessment_contract_version,
                                    x.source_submission_id,
                                    x.code_analysis_id,
                                    x.attempt_type,
                                    x.source_attempt_id,
                                    x.attempt_sequence_no,
                                    x.assigned_at,
                                    x.assigned_by,
                                    x.review_source_report_id,
                                    x.review_source_report_snapshot_id,
                                    x.review_due_at,
                                    x.status,
                                    x.terminal_reason_code,
                                    x.terminal_at,
                                    x.analysis_completed_at,
                                    x.assessment_open_at,
                                    x.assessment_close_at,
                                    x.validity_review_status,
                                    x.validity_trigger_reason_code,
                                    x.validity_decision_reason_code,
                                    x.validity_decision_note,
                                    x.validity_review_started_at,
                                    x.validity_reviewed_by,
                                    x.validity_reviewed_at,
                                    x.row_version,
                                    x.updated_at,
                                    x.outcome_type_code,
                                    x.outcome_verdict,
                                    x.outcome_policy_version,
                                    x.outcome_judged_at
                             FROM measurement_attempt x
                             WHERE x.assessment_round_id = b.assessment_round_id
                               AND x.user_id = b.user_id
                               AND x.attempt_type::text = 'INITIAL'::text
                             ORDER BY x.attempt_sequence_no DESC, x.updated_at DESC
                             LIMIT 1) pa ON true
         LEFT JOIN LATERAL ( SELECT x.attempt_id,
                                    x.org_id,
                                    x.cohort_id,
                                    x.assessment_round_id,
                                    x.project_id,
                                    x.user_id,
                                    x.assessment_contract_version,
                                    x.source_submission_id,
                                    x.code_analysis_id,
                                    x.attempt_type,
                                    x.source_attempt_id,
                                    x.attempt_sequence_no,
                                    x.assigned_at,
                                    x.assigned_by,
                                    x.review_source_report_id,
                                    x.review_source_report_snapshot_id,
                                    x.review_due_at,
                                    x.status,
                                    x.terminal_reason_code,
                                    x.terminal_at,
                                    x.analysis_completed_at,
                                    x.assessment_open_at,
                                    x.assessment_close_at,
                                    x.validity_review_status,
                                    x.validity_trigger_reason_code,
                                    x.validity_decision_reason_code,
                                    x.validity_decision_note,
                                    x.validity_review_started_at,
                                    x.validity_reviewed_by,
                                    x.validity_reviewed_at,
                                    x.row_version,
                                    x.updated_at,
                                    x.outcome_type_code,
                                    x.outcome_verdict,
                                    x.outcome_policy_version,
                                    x.outcome_judged_at
                             FROM measurement_attempt x
                             WHERE x.assessment_round_id = b.assessment_round_id
                               AND x.user_id = b.user_id
                               AND x.attempt_type::text = 'RETRY'::text
                             ORDER BY x.attempt_sequence_no DESC, x.updated_at DESC
                             LIMIT 1) ra ON true
         LEFT JOIN LATERAL ( SELECT x.attempt_id,
                                    x.org_id,
                                    x.cohort_id,
                                    x.assessment_round_id,
                                    x.project_id,
                                    x.user_id,
                                    x.assessment_contract_version,
                                    x.source_submission_id,
                                    x.code_analysis_id,
                                    x.attempt_type,
                                    x.source_attempt_id,
                                    x.attempt_sequence_no,
                                    x.assigned_at,
                                    x.assigned_by,
                                    x.review_source_report_id,
                                    x.review_source_report_snapshot_id,
                                    x.review_due_at,
                                    x.status,
                                    x.terminal_reason_code,
                                    x.terminal_at,
                                    x.analysis_completed_at,
                                    x.assessment_open_at,
                                    x.assessment_close_at,
                                    x.validity_review_status,
                                    x.validity_trigger_reason_code,
                                    x.validity_decision_reason_code,
                                    x.validity_decision_note,
                                    x.validity_review_started_at,
                                    x.validity_reviewed_by,
                                    x.validity_reviewed_at,
                                    x.row_version,
                                    x.updated_at,
                                    x.outcome_type_code,
                                    x.outcome_verdict,
                                    x.outcome_policy_version,
                                    x.outcome_judged_at
                             FROM measurement_attempt x
                             WHERE x.assessment_round_id = b.assessment_round_id
                               AND x.user_id = b.user_id
                               AND x.attempt_type::text = 'REVIEW'::text
                             ORDER BY x.attempt_sequence_no DESC, x.updated_at DESC
                             LIMIT 1) rev ON true
         LEFT JOIN assessment_session sess ON sess.attempt_id = pa.attempt_id
         LEFT JOIN LATERAL ( SELECT x.dispatch_id,
                                    x.assessment_round_id,
                                    x.team_id,
                                    x.user_id,
                                    x.org_id,
                                    x.analysis_job_id,
                                    x.report_id,
                                    x.trigger_type,
                                    x.reason_code,
                                    x.message_template_code,
                                    x.dispatch_batch_id,
                                    x.request_idempotency_key,
                                    x.request_fingerprint,
                                    x.request_id,
                                    x.correlation_id,
                                    x.channel,
                                    x.status,
                                    x.suppression_reason_code,
                                    x.requested_by,
                                    x.requested_at,
                                    x.sent_at,
                                    x.failure_code,
                                    x.failure_reason,
                                    x.retry_count,
                                    x.dedupe_key,
                                    x.created_at
                             FROM reminder_dispatch x
                             WHERE x.assessment_round_id = b.assessment_round_id
                               AND (x.user_id = b.user_id OR x.team_id = b.team_id AND x.user_id IS NULL)
                             ORDER BY x.requested_at DESC, x.created_at DESC
                             LIMIT 1) rd ON true;

alter table assessment_round_attendance
    owner to postgres;

grant delete, insert, select, update on assessment_round_attendance to teamiz_app;

create view curriculum_reanalysis_impact_view
            (org_id, material_id, version_id, linked_project_count, affected_round_count, running_round_count,
             started_trainee_count, generated_problem_count, generated_report_count, published_report_count,
             affected_rounds, requires_confirmation, confirmation_reason_codes, aggregation_status, calculated_at)
as
SELECT m.org_id,
       m.material_id,
       v.version_id,
       COALESCE(x.linked_project_count, 0)                                                   AS linked_project_count,
       COALESCE(x.affected_round_count, 0)                                                   AS affected_round_count,
       COALESCE(x.running_round_count, 0)                                                    AS running_round_count,
       COALESCE(x.started_trainee_count, 0)                                                  AS started_trainee_count,
       COALESCE(x.generated_problem_count, 0)                                                AS generated_problem_count,
       COALESCE(x.generated_report_count, 0)                                                 AS generated_report_count,
       COALESCE(x.published_report_count, 0)                                                 AS published_report_count,
       COALESCE(x.affected_rounds, '[]'::jsonb)::text                                        AS affected_rounds,
       COALESCE(x.started_trainee_count, 0) > 0 OR COALESCE(x.published_report_count, 0) > 0 AS requires_confirmation,
       array_remove(ARRAY [
                        CASE
                            WHEN COALESCE(x.started_trainee_count, 0) > 0 THEN 'ASSESSMENT_ALREADY_STARTED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN COALESCE(x.published_report_count, 0) > 0 THEN 'REPORT_ALREADY_PUBLISHED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN COALESCE(x.running_round_count, 0) > 0 THEN 'ROUND_IN_PROGRESS'::text
                            ELSE NULL::text
                            END],
                    NULL::text)                                                              AS confirmation_reason_codes,
       'COMPLETE'::character varying(20)                                                     AS aggregation_status,
       CURRENT_TIMESTAMP                                                                     AS calculated_at
FROM curriculum_version v
         JOIN curriculum_material m ON m.material_id = v.material_id
         LEFT JOIN LATERAL ( SELECT count(DISTINCT p.project_id)::integer                                                                                                       AS linked_project_count,
                                    count(DISTINCT r.assessment_round_id)::integer                                                                                              AS affected_round_count,
                                    count(DISTINCT r.assessment_round_id) FILTER (WHERE r.status::text = ANY
                                                                                        (ARRAY ['OPEN'::character varying::text, 'RUNNING'::character varying::text]))::integer AS running_round_count,
                                    count(DISTINCT ma.user_id)
                                    FILTER (WHERE ma.status::text <> 'NOT_STARTED'::text)::integer                                                                              AS started_trainee_count,
                                    count(DISTINCT ap.problem_id)::integer                                                                                                      AS generated_problem_count,
                                    count(DISTINCT rpt.report_id)::integer                                                                                                      AS generated_report_count,
                                    count(DISTINCT rpt.report_id)
                                    FILTER (WHERE rpt.published_at IS NOT NULL)::integer                                                                                        AS published_report_count,
                                    jsonb_agg(DISTINCT
                                    jsonb_build_object('assessmentRoundId', r.assessment_round_id, 'projectId',
                                                       p.project_id, 'projectName', p.name, 'roundNo', r.round_no,
                                                       'roundName', r.round_name, 'status', r.status))
                                    FILTER (WHERE r.assessment_round_id IS NOT NULL)                                                                                            AS affected_rounds
                             FROM project_curriculum pc
                                      JOIN project p ON p.project_id = pc.project_id
                                      LEFT JOIN project_assessment_round r
                                                ON r.project_id = p.project_id AND r.deleted_at IS NULL
                                      LEFT JOIN measurement_attempt ma ON ma.assessment_round_id = r.assessment_round_id
                                      LEFT JOIN assessment_problem ap ON ap.measurement_attempt_id = ma.attempt_id OR
                                                                         ap.code_analysis_id = ma.code_analysis_id
                                      LEFT JOIN report rpt ON rpt.assessment_round_id = r.assessment_round_id
                             WHERE pc.curriculum_version_id = v.version_id) x ON true
WHERE m.deleted_at IS NULL;

alter table curriculum_reanalysis_impact_view
    owner to postgres;

grant delete, insert, select, update on curriculum_reanalysis_impact_view to teamiz_app;

create view curriculum_section_detail_view
            (org_id, material_id, version_id, effective_analysis_id, display_analysis_status, analysis_quality_status,
             section_id, sequence_no, title, page_start, page_end, keywords, confidence, teaching_item_count,
             distinct_teaches_count, mapping_id, teaches_id, extracted_name, source_description, description_status,
             mapping_status, mapping_page_start, mapping_page_end, mapping_sequence_no, mapping_confidence,
             canonical_name, canonical_description, teaches_status, aggregation_status, as_of_at)
as
WITH effective_analysis AS (SELECT DISTINCT ON (curriculum_analysis.version_id) curriculum_analysis.analysis_id,
                                                                                curriculum_analysis.version_id,
                                                                                curriculum_analysis.model_id,
                                                                                curriculum_analysis.retry_of_analysis_id,
                                                                                curriculum_analysis.analysis_version,
                                                                                curriculum_analysis.status,
                                                                                curriculum_analysis.fallback_used,
                                                                                curriculum_analysis.idempotency_key,
                                                                                curriculum_analysis.request_fingerprint,
                                                                                curriculum_analysis.request_reason,
                                                                                curriculum_analysis.impact_acknowledged,
                                                                                curriculum_analysis.requested_by,
                                                                                curriculum_analysis.requested_at,
                                                                                curriculum_analysis.started_at,
                                                                                curriculum_analysis.completed_at,
                                                                                curriculum_analysis.failed_at,
                                                                                curriculum_analysis.failure_code,
                                                                                curriculum_analysis.failure_stage,
                                                                                curriculum_analysis.failure_reason,
                                                                                curriculum_analysis.is_retryable,
                                                                                curriculum_analysis.recovery_action,
                                                                                curriculum_analysis.created_at,
                                                                                curriculum_analysis.external_job_id
                            FROM curriculum_analysis
                            WHERE curriculum_analysis.status::text = 'SUCCEEDED'::text
                            ORDER BY curriculum_analysis.version_id, curriculum_analysis.completed_at DESC NULLS LAST,
                                     curriculum_analysis.analysis_version DESC),
     mapping_counts AS (SELECT curriculum_teaches_mapping.version_id,
                               curriculum_teaches_mapping.source_analysis_id,
                               curriculum_teaches_mapping.section_id,
                               count(curriculum_teaches_mapping.mapping_id)
                               FILTER (WHERE curriculum_teaches_mapping.mapping_status::text = 'ACTIVE'::text)::integer AS teaching_item_count,
                               count(DISTINCT curriculum_teaches_mapping.teaches_id)
                               FILTER (WHERE curriculum_teaches_mapping.mapping_status::text = 'ACTIVE'::text)::integer AS distinct_teaches_count
                        FROM curriculum_teaches_mapping
                        GROUP BY curriculum_teaches_mapping.version_id, curriculum_teaches_mapping.source_analysis_id,
                                 curriculum_teaches_mapping.section_id)
SELECT m.org_id,
       m.material_id,
       v.version_id,
       a.analysis_id                          AS effective_analysis_id,
       a.status::character varying(50)        AS display_analysis_status,
       CASE
           WHEN a.status::text = 'SUCCEEDED'::text AND a.fallback_used THEN 'FALLBACK'::character varying
           WHEN a.status::text = 'SUCCEEDED'::text THEN 'NORMAL'::character varying
           ELSE a.status
           END::character varying(100)        AS analysis_quality_status,
       s.section_id,
       s.sequence_no,
       s.title,
       s.page_start,
       s.page_end,
       s.keywords,
       s.confidence::numeric(5, 4)            AS confidence,
       COALESCE(mc.teaching_item_count, 0)    AS teaching_item_count,
       COALESCE(mc.distinct_teaches_count, 0) AS distinct_teaches_count,
       map.mapping_id,
       map.teaches_id,
       map.extracted_name,
       map.source_description,
       CASE
           WHEN map.source_description IS NULL OR btrim(map.source_description) = ''::text THEN 'MISSING'::text
           ELSE 'AVAILABLE'::text
           END::character varying(30)         AS description_status,
       map.mapping_status,
       map.page_start                         AS mapping_page_start,
       map.page_end                           AS mapping_page_end,
       map.sequence_no                        AS mapping_sequence_no,
       map.confidence::text                   AS mapping_confidence,
       t.canonical_name,
       t.canonical_description,
       t.status::character varying(100)       AS teaches_status,
       'COMPLETE'::character varying(20)      AS aggregation_status,
       CURRENT_TIMESTAMP                      AS as_of_at
FROM curriculum_material m
         JOIN curriculum_version v ON v.material_id = m.material_id
         LEFT JOIN effective_analysis a ON a.version_id = v.version_id
         LEFT JOIN curriculum_section s ON s.version_id = v.version_id AND s.source_analysis_id = a.analysis_id
         LEFT JOIN curriculum_teaches_mapping map
                   ON map.version_id = v.version_id AND map.source_analysis_id = a.analysis_id AND
                      (map.section_id = s.section_id OR map.section_id IS NULL AND s.section_id IS NULL)
         LEFT JOIN mapping_counts mc ON mc.version_id = v.version_id AND mc.source_analysis_id = a.analysis_id AND
                                        NOT mc.section_id IS DISTINCT FROM s.section_id
         LEFT JOIN teaches t ON t.teaches_id = map.teaches_id
WHERE m.deleted_at IS NULL;

alter table curriculum_section_detail_view
    owner to postgres;

grant delete, insert, select, update on curriculum_section_detail_view to teamiz_app;

create view curriculum_version_project_usage_view
            (org_id, material_id, version_id, version_no, curriculum_title, project_id, project_sequence_no,
             project_name, project_category, project_status, cohort_id, cohort_name, current_version_selection_status,
             active_concept_set_id, selected_mapping_count, selected_concepts, display_round_id, display_round_no,
             display_round_name, display_round_status, eligible_trainee_count, attempted_trainee_count,
             completed_trainee_count, can_open_project, open_block_reason_code, aggregation_status, as_of_at)
as
SELECT m.org_id,
       m.material_id,
       v.version_id,
       v.version_no,
       m.title::text                              AS curriculum_title,
       p.project_id,
       p.sequence_no                              AS project_sequence_no,
       p.name                                     AS project_name,
       p.project_category,
       p.lifecycle_status                         AS project_status,
       p.cohort_id,
       co.name                                    AS cohort_name,
       CASE
           WHEN pc.project_curriculum_id IS NULL THEN 'NOT_SELECTED'::text
           WHEN v.status::text = 'ACTIVE'::text THEN 'SELECTED_ACTIVE'::text
           ELSE 'SELECTED_INACTIVE'::text
           END::character varying(100)            AS current_version_selection_status,
       cs.concept_set_id                          AS active_concept_set_id,
       COALESCE(con.selected_mapping_count, 0)    AS selected_mapping_count,
       COALESCE(con.items, '[]'::jsonb)           AS selected_concepts,
       rd.assessment_round_id                     AS display_round_id,
       rd.round_no                                AS display_round_no,
       rd.round_name                              AS display_round_name,
       rd.status                                  AS display_round_status,
       COALESCE(st.eligible_count, 0)             AS eligible_trainee_count,
       COALESCE(st.attempted_count, 0)            AS attempted_trainee_count,
       COALESCE(st.completed_count, 0)            AS completed_trainee_count,
       p.lifecycle_status::text <> 'CLOSED'::text AS can_open_project,
       CASE
           WHEN p.lifecycle_status::text = 'CLOSED'::text THEN 'PROJECT_CLOSED'::text
           WHEN v.status::text <> 'ACTIVE'::text THEN 'CURRICULUM_VERSION_INACTIVE'::text
           ELSE NULL::text
           END::character varying(100)            AS open_block_reason_code,
       'COMPLETE'::character varying(20)          AS aggregation_status,
       CURRENT_TIMESTAMP                          AS as_of_at
FROM curriculum_material m
         JOIN curriculum_version v ON v.material_id = m.material_id
         JOIN project_curriculum pc ON pc.curriculum_version_id = v.version_id
         JOIN project p ON p.project_id = pc.project_id AND p.deleted_at IS NULL
         JOIN cohort co ON co.cohort_id = p.cohort_id
         LEFT JOIN LATERAL ( SELECT x.concept_set_id,
                                    x.project_id,
                                    x.org_id,
                                    x.version_no,
                                    x.status,
                                    x.effective_from,
                                    x.effective_to,
                                    x.created_by,
                                    x.change_reason,
                                    x.created_at
                             FROM project_verification_concept_set x
                             WHERE x.project_id = p.project_id
                               AND x.status::text = 'ACTIVE'::text
                             ORDER BY x.version_no DESC
                             LIMIT 1) cs ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer                 AS selected_mapping_count,
                                    jsonb_agg(jsonb_build_object('projectConceptId', x.project_concept_id, 'teachesId',
                                                                 x.teaches_id, 'mappingId', x.source_mapping_id,
                                                                 'sequenceNo', x.sequence_no, 'name', t.canonical_name)
                                              ORDER BY x.sequence_no) AS items
                             FROM project_verification_concept x
                                      LEFT JOIN teaches t ON t.teaches_id = x.teaches_id
                             WHERE x.concept_set_id = cs.concept_set_id) con ON true
         LEFT JOIN LATERAL ( SELECT x.assessment_round_id,
                                    x.project_id,
                                    x.org_id,
                                    x.cohort_id,
                                    x.concept_set_id,
                                    x.round_no,
                                    x.round_name,
                                    x.analysis_role_code,
                                    x.trigger_type,
                                    x.scheduled_at,
                                    x.submission_due_at,
                                    x.assessment_open_at,
                                    x.assessment_due_at,
                                    x.report_publish_mode,
                                    x.report_publish_not_before_at,
                                    x.is_final,
                                    x.status,
                                    x.created_by,
                                    x.updated_by,
                                    x.created_at,
                                    x.updated_at,
                                    x.deleted_at
                             FROM project_assessment_round x
                             WHERE x.project_id = p.project_id
                               AND x.deleted_at IS NULL
                             ORDER BY (
                                          CASE
                                              WHEN x.status::text = ANY
                                                   (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                  THEN 0
                                              ELSE 1
                                              END), x.round_no DESC
                             LIMIT 1) rd ON true
         LEFT JOIN LATERAL ( SELECT ((SELECT count(*) AS count
                                      FROM project_membership pm
                                      WHERE pm.project_id = p.project_id
                                        AND pm.status::text = 'ACTIVE'::text))::integer                          AS eligible_count,
                                    count(DISTINCT ma.user_id) FILTER (WHERE ma.attempt_id IS NOT NULL)::integer AS attempted_count,
                                    count(DISTINCT ma.user_id)
                                    FILTER (WHERE ma.status::text = 'COMPLETED'::text)::integer                  AS completed_count
                             FROM measurement_attempt ma
                             WHERE ma.assessment_round_id = rd.assessment_round_id) st ON true
WHERE m.deleted_at IS NULL;

alter table curriculum_version_project_usage_view
    owner to postgres;

grant delete, insert, select, update on curriculum_version_project_usage_view to teamiz_app;

create view manager_class_heatmap_view
            (org_id, cohort_id, class_id, project_id, assessment_round_id, problem_no, average_highest_reached_level,
             valid_result_count, not_attended_count, invalid_attempt_count, interrupted_count, aggregation_status,
             as_of_at, calculation_version)
as
WITH problem_result AS (SELECT ma.org_id,
                               ma.cohort_id,
                               ma.project_id,
                               ma.assessment_round_id,
                               ma.user_id,
                               ma.attempt_id,
                               ma.attempt_type,
                               ma.source_attempt_id,
                               ma.attempt_sequence_no,
                               ma.status                                                                            AS attempt_status,
                               ma.validity_review_status,
                               ma.terminal_reason_code,
                               pm.class_id,
                               tm.team_id,
                               ap.problem_id,
                               ap.problem_no,
                               max(
                                       CASE
                                           WHEN ps.status::text = 'PASSED'::text THEN
                                               CASE ps.axis_code
                                                   WHEN 'L4'::text THEN 4
                                                   WHEN 'L3'::text THEN 3
                                                   WHEN 'L2'::text THEN 2
                                                   WHEN 'L1'::text THEN 1
                                                   ELSE 0
                                                   END
                                           ELSE 0
                                           END)                                                                     AS highest_reached_level,
                               CASE
                                   WHEN ma.validity_review_status::text = 'CONFIRMED_INVALID'::text THEN 'INVALID'::text
                                   WHEN ma.status::text = 'COMPLETED'::text THEN 'VALID'::text
                                   WHEN ma.terminal_reason_code::text = ANY
                                        (ARRAY ['NOT_ATTENDED'::character varying::text, 'NOT_SUBMITTED'::character varying::text])
                                       THEN 'NOT_ATTENDED'::text
                                   WHEN ma.status::text = 'FAILED'::text OR
                                        ma.terminal_reason_code::text = 'SESSION_INCOMPLETE'::text THEN 'INTERRUPTED'::text
                                   WHEN ma.status::text = 'EXPIRED'::text THEN 'NOT_ATTENDED'::text
                                   ELSE 'PENDING'::text
                                   END                                                                              AS result_status,
                               sum((ps.question_answer_text IS NOT NULL)::integer +
                                   (ps.first_hint_answer_text IS NOT NULL)::integer +
                                   (ps.second_hint_answer_text IS NOT NULL)::integer)::integer                      AS answer_attempt_count,
                               jsonb_agg(jsonb_build_object('axisCode', ps.axis_code, 'status', ps.status,
                                                            'questionScore', ps.question_score, 'firstHintScore',
                                                            ps.first_hint_score, 'secondHintScore',
                                                            ps.second_hint_score)
                                         ORDER BY ps.question_sequence_no)                                          AS score_summary
                        FROM measurement_attempt ma
                                 LEFT JOIN project_membership pm
                                           ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id AND
                                              pm.joined_at <=
                                              COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP) AND
                                              (pm.left_at IS NULL OR pm.left_at >
                                                                     COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP))
                                 LEFT JOIN LATERAL ( SELECT x.team_id
                                                     FROM team_membership x
                                                     WHERE x.project_membership_id = pm.project_membership_id
                                                       AND x.from_at <=
                                                           COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP)
                                                       AND (x.to_at IS NULL OR x.to_at >
                                                                               COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP))
                                                     ORDER BY x.from_at DESC
                                                     LIMIT 1) tm ON true
                                 LEFT JOIN assessment_session s ON s.attempt_id = ma.attempt_id
                                 LEFT JOIN problem_stage ps ON ps.session_id = s.session_id
                                 LEFT JOIN assessment_problem ap ON ap.problem_id = ps.problem_id
                        GROUP BY ma.org_id, ma.cohort_id, ma.project_id, ma.assessment_round_id, ma.user_id,
                                 ma.attempt_id, ma.attempt_type, ma.source_attempt_id, ma.attempt_sequence_no,
                                 ma.status, ma.validity_review_status, ma.terminal_reason_code, pm.class_id, tm.team_id,
                                 ap.problem_id, ap.problem_no)
SELECT org_id,
       cohort_id,
       class_id,
       project_id,
       assessment_round_id,
       problem_no,
       avg(highest_reached_level) FILTER (WHERE result_status = 'VALID'::text)::numeric(10, 4) AS average_highest_reached_level,
       count(*) FILTER (WHERE result_status = 'VALID'::text)::integer                          AS valid_result_count,
       count(*) FILTER (WHERE result_status = 'NOT_ATTENDED'::text)::integer                   AS not_attended_count,
       count(*) FILTER (WHERE result_status = 'INVALID'::text)::integer                        AS invalid_attempt_count,
       count(*) FILTER (WHERE result_status = 'INTERRUPTED'::text)::integer                    AS interrupted_count,
       CASE
           WHEN count(*) = 0 THEN 'EMPTY'::text
           WHEN count(*) FILTER (WHERE result_status = 'VALID'::text) = 0 THEN 'NO_VALID_RESULT'::text
           ELSE 'COMPLETE'::text
           END::character varying(20)                                                          AS aggregation_status,
       CURRENT_TIMESTAMP                                                                       AS as_of_at,
       1                                                                                       AS calculation_version
FROM problem_result pr
WHERE attempt_type::text = 'INITIAL'::text
GROUP BY org_id, cohort_id, class_id, project_id, assessment_round_id, problem_no;

alter table manager_class_heatmap_view
    owner to postgres;

grant delete, insert, select, update on manager_class_heatmap_view to teamiz_app;

create view manager_curriculum_list_view
            (org_id, manager_user_id, cohort_id, assigned_class_ids, material_id, curriculum_title, version_id,
             version_no, registered_at, page_count, latest_analysis_attempt_id, latest_analysis_attempt_status,
             latest_failure_code, latest_recovery_action, effective_analysis_id, display_analysis_status,
             analysis_quality_status, section_count, teaching_item_count, distinct_teaches_count, used_round_count,
             used_round_ids, used_round_names, latest_used_at, empty_reason, aggregation_status, as_of_at)
as
WITH scope AS (SELECT ma.org_id,
                      ma.manager_user_id,
                      c.cohort_id,
                      array_agg(DISTINCT ma.class_id) AS class_ids
               FROM manager_assignment ma
                        JOIN class c ON c.class_id = ma.class_id
               WHERE ma.status::text = 'ACTIVE'::text
                 AND ma.unassigned_at IS NULL
               GROUP BY ma.org_id, ma.manager_user_id, c.cohort_id)
SELECT scope.org_id,
       scope.manager_user_id,
       scope.cohort_id,
       scope.class_ids                                                          AS assigned_class_ids,
       m.material_id,
       m.title::text                                                            AS curriculum_title,
       v.version_id,
       v.version_no,
       m.created_at                                                             AS registered_at,
       v.page_count,
       la.analysis_id                                                           AS latest_analysis_attempt_id,
       la.status::character varying(100)                                        AS latest_analysis_attempt_status,
       la.failure_code                                                          AS latest_failure_code,
       la.recovery_action::text                                                 AS latest_recovery_action,
       ea.analysis_id                                                           AS effective_analysis_id,
       CASE
           WHEN la.analysis_id IS NULL THEN 'NOT_ANALYZED'::character varying
           WHEN la.status::text = 'FAILED'::text AND ea.analysis_id IS NOT NULL
               THEN 'FAILED_USING_PREVIOUS'::character varying
           ELSE la.status
           END::character varying(50)                                           AS display_analysis_status,
       CASE
           WHEN ea.analysis_id IS NULL THEN 'UNAVAILABLE'::text
           WHEN ea.fallback_used THEN 'FALLBACK'::text
           ELSE 'NORMAL'::text
           END::character varying(100)                                          AS analysis_quality_status,
       COALESCE(stats.section_count, 0)                                         AS section_count,
       COALESCE(stats.teaching_item_count, 0)                                   AS teaching_item_count,
       COALESCE(stats.distinct_teaches_count, 0)                                AS distinct_teaches_count,
       COALESCE(use.used_round_count, 0)                                        AS used_round_count,
       COALESCE(use.round_ids, ARRAY []::uuid[])                                AS used_round_ids,
       COALESCE(use.round_names, ARRAY []::text[]::character varying[])::text[] AS used_round_names,
       use.latest_used_at,
       CASE
           WHEN v.version_id IS NULL THEN 'NO_VERSION'::text
           WHEN ea.analysis_id IS NULL THEN 'NO_SUCCESSFUL_ANALYSIS'::text
           ELSE NULL::text
           END::character varying(100)                                          AS empty_reason,
       'COMPLETE'::character varying(20)                                        AS aggregation_status,
       CURRENT_TIMESTAMP                                                        AS as_of_at
FROM scope
         JOIN curriculum_material m ON m.org_id = scope.org_id AND m.deleted_at IS NULL
         LEFT JOIN LATERAL ( SELECT x.version_id,
                                    x.material_id,
                                    x.version_no,
                                    x.original_file_name,
                                    x.file_uri,
                                    x.mime_type,
                                    x.file_size_bytes,
                                    x.content_hash,
                                    x.page_count,
                                    x.status,
                                    x.created_by,
                                    x.created_at
                             FROM curriculum_version x
                             WHERE x.material_id = m.material_id
                             ORDER BY x.version_no DESC
                             LIMIT 1) v ON true
         LEFT JOIN LATERAL ( SELECT x.analysis_id,
                                    x.version_id,
                                    x.model_id,
                                    x.retry_of_analysis_id,
                                    x.analysis_version,
                                    x.status,
                                    x.fallback_used,
                                    x.idempotency_key,
                                    x.request_fingerprint,
                                    x.request_reason,
                                    x.impact_acknowledged,
                                    x.requested_by,
                                    x.requested_at,
                                    x.started_at,
                                    x.completed_at,
                                    x.failed_at,
                                    x.failure_code,
                                    x.failure_stage,
                                    x.failure_reason,
                                    x.is_retryable,
                                    x.recovery_action,
                                    x.created_at,
                                    x.external_job_id
                             FROM curriculum_analysis x
                             WHERE x.version_id = v.version_id
                             ORDER BY x.requested_at DESC
                             LIMIT 1) la ON true
         LEFT JOIN LATERAL ( SELECT x.analysis_id,
                                    x.version_id,
                                    x.model_id,
                                    x.retry_of_analysis_id,
                                    x.analysis_version,
                                    x.status,
                                    x.fallback_used,
                                    x.idempotency_key,
                                    x.request_fingerprint,
                                    x.request_reason,
                                    x.impact_acknowledged,
                                    x.requested_by,
                                    x.requested_at,
                                    x.started_at,
                                    x.completed_at,
                                    x.failed_at,
                                    x.failure_code,
                                    x.failure_stage,
                                    x.failure_reason,
                                    x.is_retryable,
                                    x.recovery_action,
                                    x.created_at,
                                    x.external_job_id
                             FROM curriculum_analysis x
                             WHERE x.version_id = v.version_id
                               AND x.status::text = 'SUCCEEDED'::text
                             ORDER BY x.completed_at DESC, x.analysis_version DESC
                             LIMIT 1) ea ON true
         LEFT JOIN LATERAL ( SELECT count(DISTINCT s.section_id)::integer   AS section_count,
                                    count(map.mapping_id)::integer          AS teaching_item_count,
                                    count(DISTINCT map.teaches_id)::integer AS distinct_teaches_count
                             FROM curriculum_section s
                                      LEFT JOIN curriculum_teaches_mapping map ON map.section_id = s.section_id
                             WHERE s.version_id = v.version_id
                               AND s.source_analysis_id = ea.analysis_id) stats ON true
         LEFT JOIN LATERAL ( SELECT count(DISTINCT r.assessment_round_id)::integer         AS used_round_count,
                                    array_agg(DISTINCT r.assessment_round_id)              AS round_ids,
                                    array_agg(DISTINCT r.round_name ORDER BY r.round_name) AS round_names,
                                    max(COALESCE(r.updated_at, r.created_at))              AS latest_used_at
                             FROM project_curriculum pc
                                      JOIN project p ON p.project_id = pc.project_id AND p.cohort_id = scope.cohort_id
                                      JOIN project_assessment_round r
                                           ON r.project_id = p.project_id AND r.deleted_at IS NULL
                             WHERE pc.curriculum_version_id = v.version_id) use ON true;

alter table manager_curriculum_list_view
    owner to postgres;

grant delete, insert, select, update on manager_curriculum_list_view to teamiz_app;

create view manager_curriculum_usage_view
            (org_id, manager_user_id, cohort_id, material_id, version_id, project_id, project_sequence_no, project_name,
             project_category, assessment_round_id, round_no, round_name, round_status, concept_set_id,
             concept_selection_status, selected_concept_count, concepts, assigned_class_count, assigned_class_ids,
             eligible_trainee_count, attempted_trainee_count, completed_trainee_count, result_release_status,
             published_report_count, aggregation_status, as_of_at)
as
WITH scope AS (SELECT ma.org_id,
                      ma.manager_user_id,
                      c.cohort_id,
                      array_agg(DISTINCT ma.class_id) AS class_ids
               FROM manager_assignment ma
                        JOIN class c ON c.class_id = ma.class_id
               WHERE ma.status::text = 'ACTIVE'::text
                 AND ma.unassigned_at IS NULL
               GROUP BY ma.org_id, ma.manager_user_id, c.cohort_id)
SELECT scope.org_id,
       scope.manager_user_id,
       scope.cohort_id,
       m.material_id,
       v.version_id,
       p.project_id,
       p.sequence_no                          AS project_sequence_no,
       p.name                                 AS project_name,
       p.project_category,
       r.assessment_round_id,
       r.round_no,
       r.round_name,
       r.status                               AS round_status,
       cs.concept_set_id,
       CASE
           WHEN COALESCE(con.cnt, 0) = 3 THEN 'COMPLETE'::text
           WHEN COALESCE(con.cnt, 0) = 0 THEN 'EMPTY'::text
           ELSE 'PARTIAL'::text
           END::character varying(30)         AS concept_selection_status,
       COALESCE(con.cnt, 0)                   AS selected_concept_count,
       COALESCE(con.items, '[]'::jsonb)::text AS concepts,
       cardinality(scope.class_ids)           AS assigned_class_count,
       scope.class_ids                        AS assigned_class_ids,
       COALESCE(st.eligible, 0)               AS eligible_trainee_count,
       COALESCE(st.attempted, 0)              AS attempted_trainee_count,
       COALESCE(st.completed, 0)              AS completed_trainee_count,
       CASE
           WHEN COALESCE(rep.published_count, 0) > 0 THEN 'PUBLISHED'::text
           ELSE 'NOT_PUBLISHED'::text
           END::character varying(100)        AS result_release_status,
       COALESCE(rep.published_count, 0)       AS published_report_count,
       'COMPLETE'::character varying(20)      AS aggregation_status,
       CURRENT_TIMESTAMP                      AS as_of_at
FROM scope
         JOIN project p ON p.cohort_id = scope.cohort_id AND p.deleted_at IS NULL
         JOIN project_curriculum pc ON pc.project_id = p.project_id
         JOIN curriculum_version v ON v.version_id = pc.curriculum_version_id
         JOIN curriculum_material m ON m.material_id = v.material_id
         LEFT JOIN project_assessment_round r ON r.project_id = p.project_id AND r.deleted_at IS NULL
         LEFT JOIN LATERAL ( SELECT x.concept_set_id,
                                    x.project_id,
                                    x.org_id,
                                    x.version_no,
                                    x.status,
                                    x.effective_from,
                                    x.effective_to,
                                    x.created_by,
                                    x.change_reason,
                                    x.created_at
                             FROM project_verification_concept_set x
                             WHERE x.project_id = p.project_id
                               AND x.status::text = 'ACTIVE'::text
                             ORDER BY x.version_no DESC
                             LIMIT 1) cs ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer                                                      AS cnt,
                                    jsonb_agg(jsonb_build_object('projectConceptId', x.project_concept_id, 'teachesId',
                                                                 x.teaches_id, 'sequenceNo', x.sequence_no, 'name',
                                                                 t.canonical_name) ORDER BY x.sequence_no) AS items
                             FROM project_verification_concept x
                                      LEFT JOIN teaches t ON t.teaches_id = x.teaches_id
                             WHERE x.concept_set_id = cs.concept_set_id) con ON true
         LEFT JOIN LATERAL ( SELECT ((SELECT count(*) AS count
                                      FROM project_membership pm_1
                                      WHERE pm_1.project_id = p.project_id
                                        AND (pm_1.class_id = ANY (scope.class_ids))
                                        AND pm_1.status::text = 'ACTIVE'::text))::integer       AS eligible,
                                    count(DISTINCT ma.user_id)::integer                         AS attempted,
                                    count(DISTINCT ma.user_id)
                                    FILTER (WHERE ma.status::text = 'COMPLETED'::text)::integer AS completed
                             FROM measurement_attempt ma
                                      JOIN project_membership pm
                                           ON pm.user_id = ma.user_id AND pm.project_id = p.project_id
                             WHERE ma.assessment_round_id = r.assessment_round_id
                               AND (pm.class_id = ANY (scope.class_ids))) st ON true
         LEFT JOIN LATERAL ( SELECT count(*) FILTER (WHERE rpt.published_at IS NOT NULL)::integer AS published_count
                             FROM report rpt
                             WHERE rpt.assessment_round_id = r.assessment_round_id) rep ON true;

alter table manager_curriculum_usage_view
    owner to postgres;

grant delete, insert, select, update on manager_curriculum_usage_view to teamiz_app;

create view manager_interview_brief_view
            (org_id, manager_user_id, cohort_id, class_id, interview_id, interview_status, target_user_id,
             target_user_name, brief_id, latest_confirmed_brief_id, brief_persistence_status, brief_type, version_no,
             brief_status, is_first_interview, previous_completed_interview_count, first_interview_scope_code,
             first_interview_evaluated_at, brief_generation_policy_version, difficulty_scale_version,
             persisted_total_item_count, persisted_selected_item_count, persisted_source_count,
             has_multiple_selected_items, manager_note, source_snapshot_status, source_consistency_status,
             can_initialize, can_edit, can_save, can_confirm, action_unavailable_reason_code, row_version,
             last_saved_by, last_saved_at, created_by, created_at, updated_by, updated_at, confirmed_by, confirmed_at,
             aggregation_status, as_of_at)
as
WITH scope AS (SELECT ma.manager_user_id,
                      ma.class_id
               FROM manager_assignment ma
               WHERE ma.status::text = 'ACTIVE'::text
                 AND ma.unassigned_at IS NULL)
SELECT i.org_id,
       scope.manager_user_id,
       i.cohort_id,
       i.class_id,
       i.interview_id,
       i.status                                                                                                      AS interview_status,
       i.target_user_id,
       u.name                                                                                                        AS target_user_name,
       b.brief_id,
       cb.brief_id                                                                                                   AS latest_confirmed_brief_id,
       CASE
           WHEN b.brief_id IS NULL THEN 'NOT_INITIALIZED'::character varying
           WHEN b.status::text = 'DRAFT'::text THEN 'DRAFT_SAVED'::character varying
           ELSE b.status
           END::character varying(100)                                                                               AS brief_persistence_status,
       b.brief_type,
       b.version_no,
       b.status                                                                                                      AS brief_status,
       b.is_first_interview,
       NULL::integer                                                                                                 AS previous_completed_interview_count,
       NULL::character varying(100)                                                                                  AS first_interview_scope_code,
       NULL::timestamp with time zone                                                                                AS first_interview_evaluated_at,
       b.brief_generation_policy_version,
       NULL::integer                                                                                                 AS difficulty_scale_version,
       COALESCE(items.total_count, 0)                                                                                AS persisted_total_item_count,
       COALESCE(items.selected_count, 0)                                                                             AS persisted_selected_item_count,
       COALESCE(src.source_count, 0)                                                                                 AS persisted_source_count,
       COALESCE(items.selected_count, 0) > 1                                                                         AS has_multiple_selected_items,
       b.manager_note,
       jsonb_build_object('status',
                          CASE
                              WHEN COALESCE(src.source_count, 0) = 0 THEN 'EMPTY'::text
                              ELSE 'AVAILABLE'::text
                              END, 'sourceCount',
                          COALESCE(src.source_count, 0))                                                             AS source_snapshot_status,
       CASE
           WHEN COALESCE(src.source_count, 0) = 0 THEN 'NOT_CHECKED'::text
           ELSE 'CONSISTENT'::text
           END::character varying(100)                                                                               AS source_consistency_status,
       b.brief_id IS NULL AND i.status::text = 'PENDING'::text                                                       AS can_initialize,
       b.status::text = 'DRAFT'::text AND i.status::text = 'PENDING'::text                                           AS can_edit,
       b.status::text = 'DRAFT'::text AND i.status::text = 'PENDING'::text                                           AS can_save,
       b.status::text = 'DRAFT'::text AND i.status::text = 'PENDING'::text AND COALESCE(items.selected_count, 0) >
                                                                               0                                     AS can_confirm,
       CASE
           WHEN i.status::text <> 'PENDING'::text THEN 'BRIEF_NOT_EDITABLE'::text
           WHEN b.brief_id IS NULL THEN NULL::text
           WHEN b.status::text <> 'DRAFT'::text THEN 'BRIEF_NOT_EDITABLE'::text
           WHEN COALESCE(items.selected_count, 0) = 0 THEN 'NO_SELECTED_ITEM'::text
           ELSE NULL::text
           END::character varying(100)                                                                               AS action_unavailable_reason_code,
       COALESCE(b.row_version, 0)                                                                                    AS row_version,
       b.updated_by::text                                                                                            AS last_saved_by,
       b.updated_at                                                                                                  AS last_saved_at,
       b.created_by,
       b.created_at,
       b.updated_by,
       b.updated_at,
       b.confirmed_by,
       b.confirmed_at,
       'COMPLETE'::character varying(20)                                                                             AS aggregation_status,
       CURRENT_TIMESTAMP                                                                                             AS as_of_at
FROM interview i
         JOIN scope ON scope.class_id = i.class_id
         JOIN app_user u ON u.user_id = i.target_user_id
         LEFT JOIN LATERAL ( SELECT x.brief_id,
                                    x.interview_id,
                                    x.org_id,
                                    x.cohort_id,
                                    x.user_id,
                                    x.assessment_round_id,
                                    x.brief_type,
                                    x.version_no,
                                    x.is_first_interview,
                                    x.brief_generation_policy_version,
                                    x.status,
                                    x.manager_note,
                                    x.opening_remark_text,
                                    x.opening_remark_generated_at,
                                    x.created_by,
                                    x.created_at,
                                    x.updated_by,
                                    x.updated_at,
                                    x.confirmed_by,
                                    x.confirmed_at,
                                    x.row_version,
                                    x.last_request_id,
                                    x.last_request_fingerprint
                             FROM interview_brief x
                             WHERE x.interview_id = i.interview_id
                             ORDER BY (
                                          CASE
                                              WHEN x.status::text = 'DRAFT'::text THEN 0
                                              WHEN x.status::text = 'CONFIRMED'::text THEN 1
                                              ELSE 2
                                              END), x.version_no DESC
                             LIMIT 1) b ON true
         LEFT JOIN LATERAL ( SELECT x.brief_id
                             FROM interview_brief x
                             WHERE x.interview_id = i.interview_id
                               AND x.status::text = 'CONFIRMED'::text
                             ORDER BY x.version_no DESC
                             LIMIT 1) cb ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer                              AS total_count,
                                    count(*) FILTER (WHERE x.is_selected)::integer AS selected_count
                             FROM interview_brief_item x
                             WHERE x.brief_id = b.brief_id) items ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer AS source_count
                             FROM interview_source x
                             WHERE x.interview_id = i.interview_id) src ON true;

alter table manager_interview_brief_view
    owner to postgres;

grant delete, insert, select, update on manager_interview_brief_view to teamiz_app;

create view mini_project_round_sequence_view(assessment_round_id, project_id, cohort_id, analysis_sequence_no) as
SELECT par.assessment_round_id,
       pj.project_id,
       pj.cohort_id,
       dense_rank()
       OVER (PARTITION BY pj.cohort_id ORDER BY pj.sequence_no, pj.project_id)::integer AS analysis_sequence_no
FROM project_assessment_round par
         JOIN project pj
              ON pj.project_id = par.project_id AND pj.deleted_at IS NULL AND pj.project_category::text = 'MINI_PROJECT'::text
WHERE par.deleted_at IS NULL;

alter table mini_project_round_sequence_view
    owner to postgres;

grant delete, insert, select, update on mini_project_round_sequence_view to teamiz_app;

create view manager_interview_list_context_view
            (org_id, manager_user_id, cohort_id, list_mode, round_scope, selected_project_id,
             selected_assessment_round_id, current_assessment_round_id, round_relation_code,
             available_assessment_rounds, default_assessment_round_id, selection_unavailable_reason_code,
             analysis_sequence_no, is_first_mini_project, candidate_evaluation_status, candidate_evaluation_reason_code,
             reason_evaluation_results, eligible_trainee_count, evaluated_trainee_count, matched_candidate_count,
             unevaluated_trainee_count, unavailable_evaluation_count, accessible_candidate_count, active_item_count,
             completed_interview_count, candidate_status_counts, interview_status_counts, mode_total_count,
             filtered_count, mode_total_count_is_partial, empty_state_code, aggregation_status, as_of_at,
             calculation_version)
as
WITH scope AS (SELECT ma.org_id,
                      ma.manager_user_id,
                      c.cohort_id,
                      array_agg(DISTINCT ma.class_id) AS class_ids
               FROM manager_assignment ma
                        JOIN class c ON c.class_id = ma.class_id
               WHERE ma.status::text = 'ACTIVE'::text
                 AND ma.unassigned_at IS NULL
               GROUP BY ma.org_id, ma.manager_user_id, c.cohort_id),
     x AS (SELECT s.org_id,
                  s.manager_user_id,
                  s.cohort_id,
                  s.class_ids,
                  cr.project_id                                                                                  AS current_project_id,
                  cr.assessment_round_id                                                                         AS current_round_id,
                  cr.round_no                                                                                    AS current_round_no,
                  (SELECT jsonb_agg(jsonb_build_object('assessmentRoundId', r.assessment_round_id, 'projectId',
                                                       r.project_id, 'roundNo', (SELECT s_1.analysis_sequence_no
                                                                                 FROM mini_project_round_sequence_view s_1
                                                                                 WHERE s_1.assessment_round_id = r.assessment_round_id),
                                                       'roundName', r.round_name, 'status', r.status)
                                    ORDER BY p.sequence_no, r.round_no) AS jsonb_agg
                   FROM project_assessment_round r
                            JOIN project p ON p.project_id = r.project_id
                   WHERE r.cohort_id = s.cohort_id
                     AND r.deleted_at IS NULL)                                                                   AS rounds,
                  ((SELECT count(*) AS count
                    FROM project_membership pm
                    WHERE pm.project_id = cr.project_id
                      AND (pm.class_id = ANY (s.class_ids))
                      AND pm.status::text = 'ACTIVE'::text))::integer                                            AS eligible_trainee_count,
                  ((SELECT count(DISTINCT ic.user_id) AS count
                    FROM interview_candidate ic
                    WHERE ic.assessment_round_id = cr.assessment_round_id
                      AND (ic.class_id = ANY (s.class_ids))))::integer                                           AS evaluated_trainee_count,
                  ((SELECT count(*) AS count
                    FROM interview_candidate ic
                    WHERE ic.assessment_round_id = cr.assessment_round_id
                      AND (ic.class_id = ANY (s.class_ids))
                      AND ic.status::text <> 'EXCLUDED'::text))::integer                                         AS matched_candidate_count,
                  0                                                                                              AS unavailable_count,
                  ((SELECT count(*) AS count
                    FROM interview_candidate ic
                    WHERE ic.assessment_round_id = cr.assessment_round_id
                      AND (ic.class_id = ANY (s.class_ids))
                      AND ic.status::text <> 'EXCLUDED'::text))::integer                                         AS accessible_candidate_count,
                  ((SELECT count(*) AS count
                    FROM interview_candidate ic
                             LEFT JOIN interview i ON i.candidate_id = ic.candidate_id
                    WHERE ic.assessment_round_id = cr.assessment_round_id
                      AND (ic.class_id = ANY (s.class_ids))
                      AND COALESCE(i.status, 'PENDING'::character varying)::text <>
                          'COMPLETED'::text))::integer                                                           AS active_item_count,
                  ((SELECT count(*) AS count
                    FROM interview i
                    WHERE i.assessment_round_id = cr.assessment_round_id
                      AND (i.class_id = ANY (s.class_ids))
                      AND i.status::text = 'COMPLETED'::text))::integer                                          AS completed_interview_count,
                  (SELECT COALESCE(jsonb_object_agg(q.status, q.cnt), '{}'::jsonb) AS "coalesce"
                   FROM (SELECT ic.status,
                                count(*) AS cnt
                         FROM interview_candidate ic
                         WHERE ic.assessment_round_id = cr.assessment_round_id
                           AND (ic.class_id = ANY (s.class_ids))
                         GROUP BY ic.status) q)                                                                  AS candidate_status_counts,
                  (SELECT COALESCE(jsonb_object_agg(q.status, q.cnt), '{}'::jsonb) AS "coalesce"
                   FROM (SELECT i.status,
                                count(*) AS cnt
                         FROM interview i
                         WHERE i.assessment_round_id = cr.assessment_round_id
                           AND (i.class_id = ANY (s.class_ids))
                         GROUP BY i.status) q)                                                                   AS interview_status_counts,
                  (SELECT COALESCE(jsonb_agg(jsonb_build_object('reasonCode', q.reason_code, 'count', q.cnt)),
                                   '[]'::jsonb) AS "coalesce"
                   FROM (SELECT icr.reason_code,
                                count(*) AS cnt
                         FROM interview_candidate_reason icr
                                  JOIN interview_candidate ic ON ic.candidate_id = icr.candidate_id
                         WHERE ic.assessment_round_id = cr.assessment_round_id
                           AND (ic.class_id = ANY (s.class_ids))
                           AND icr.reason_status::text = 'ACTIVE'::text
                         GROUP BY icr.reason_code) q)                                                            AS reason_results
           FROM scope s
                    LEFT JOIN LATERAL ( SELECT r.assessment_round_id,
                                               r.project_id,
                                               r.org_id,
                                               r.cohort_id,
                                               r.concept_set_id,
                                               r.round_no,
                                               r.round_name,
                                               r.analysis_role_code,
                                               r.trigger_type,
                                               r.scheduled_at,
                                               r.submission_due_at,
                                               r.assessment_open_at,
                                               r.assessment_due_at,
                                               r.report_publish_mode,
                                               r.report_publish_not_before_at,
                                               r.is_final,
                                               r.status,
                                               r.created_by,
                                               r.updated_by,
                                               r.created_at,
                                               r.updated_at,
                                               r.deleted_at
                                        FROM project_assessment_round r
                                                 JOIN project p ON p.project_id = r.project_id
                                        WHERE r.cohort_id = s.cohort_id
                                          AND r.deleted_at IS NULL
                                        ORDER BY (
                                                     CASE
                                                         WHEN r.status::text = ANY
                                                              (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                             THEN 0
                                                         ELSE 1
                                                         END), p.sequence_no DESC, r.round_no DESC
                                        LIMIT 1) cr ON true)
SELECT org_id,
       manager_user_id,
       cohort_id,
       'ACTIVE'::character varying(100)                              AS list_mode,
       'SELECTED_OR_CURRENT'::character varying(100)                 AS round_scope,
       current_project_id                                            AS selected_project_id,
       current_round_id                                              AS selected_assessment_round_id,
       current_round_id                                              AS current_assessment_round_id,
       'CURRENT'::character varying(100)                             AS round_relation_code,
       COALESCE(rounds, '[]'::jsonb)::text                           AS available_assessment_rounds,
       current_round_id                                              AS default_assessment_round_id,
       CASE
           WHEN current_round_id IS NULL THEN 'NO_ROUND'::text
           ELSE NULL::text
           END::character varying(100)                               AS selection_unavailable_reason_code,
       (SELECT s.analysis_sequence_no
        FROM mini_project_round_sequence_view s
        WHERE s.assessment_round_id = x.current_round_id)            AS analysis_sequence_no,
       ((SELECT s.analysis_sequence_no
         FROM mini_project_round_sequence_view s
         WHERE s.assessment_round_id = x.current_round_id)) = 1      AS is_first_mini_project,
       CASE
           WHEN evaluated_trainee_count = eligible_trainee_count THEN 'COMPLETED'::text
           WHEN evaluated_trainee_count = 0 THEN 'NOT_STARTED'::text
           ELSE 'PARTIAL'::text
           END::character varying(100)                               AS candidate_evaluation_status,
       NULL::character varying(100)                                  AS candidate_evaluation_reason_code,
       COALESCE(reason_results, '[]'::jsonb)                         AS reason_evaluation_results,
       eligible_trainee_count,
       evaluated_trainee_count,
       matched_candidate_count,
       GREATEST(eligible_trainee_count - evaluated_trainee_count, 0) AS unevaluated_trainee_count,
       unavailable_count                                             AS unavailable_evaluation_count,
       accessible_candidate_count,
       active_item_count,
       completed_interview_count,
       candidate_status_counts::text                                 AS candidate_status_counts,
       interview_status_counts::text                                 AS interview_status_counts,
       active_item_count + completed_interview_count                 AS mode_total_count,
       active_item_count                                             AS filtered_count,
       (unavailable_count > 0)::text                                 AS mode_total_count_is_partial,
       CASE
           WHEN current_round_id IS NULL THEN 'NO_ROUND'::text
           WHEN active_item_count = 0 THEN 'NO_CANDIDATE'::text
           ELSE NULL::text
           END::character varying(100)                               AS empty_state_code,
       CASE
           WHEN unavailable_count > 0 THEN 'PARTIAL'::text
           ELSE 'COMPLETE'::text
           END::character varying(20)                                AS aggregation_status,
       CURRENT_TIMESTAMP                                             AS as_of_at,
       1                                                             AS calculation_version
FROM x;

alter table manager_interview_list_context_view
    owner to postgres;

grant delete, insert, select, update on manager_interview_list_context_view to teamiz_app;

create view manager_interview_list_source_status_view
            (org_id, manager_user_id, cohort_id, assessment_round_id, list_mode, round_scope, assignment_scope_hash,
             source_code, source_readiness_status, aggregation_status, as_of_at, is_stale, source_watermark,
             last_successful_at, failure_code, affected_row_count, mode_total_count_is_partial, retryable,
             retry_scope_code)
as
WITH scope AS (SELECT ma.org_id,
                      ma.manager_user_id,
                      c.cohort_id,
                      ma.class_id
               FROM manager_assignment ma
                        JOIN class c ON c.class_id = ma.class_id
               WHERE ma.status::text = 'ACTIVE'::text
                 AND ma.unassigned_at IS NULL),
     rounds AS (SELECT DISTINCT s.org_id,
                                s.manager_user_id,
                                s.cohort_id,
                                r.assessment_round_id,
                                s.class_id
                FROM scope s
                         JOIN project_assessment_round r ON r.cohort_id = s.cohort_id AND r.deleted_at IS NULL),
     z AS (SELECT r.org_id,
                  r.manager_user_id,
                  r.cohort_id,
                  r.assessment_round_id,
                  'ACTIVE'::text                                                          AS list_mode,
                  'SELECTED_OR_CURRENT'::text                                             AS round_scope,
                  md5((r.manager_user_id::text || ':'::text) || r.class_id::text)         AS assignment_scope_hash,
                  'ANALYSIS_JOB'::text                                                    AS source_code,
                  CASE
                      WHEN count(aj.job_id) = 0 THEN 'NOT_STARTED'::text
                      WHEN bool_or(aj.status::text = 'FAILED'::text) THEN 'FAILED'::text
                      WHEN bool_and(aj.status::text = 'SUCCEEDED'::text) THEN 'READY'::text
                      ELSE 'RUNNING'::text
                      END                                                                 AS source_readiness_status,
                  CASE
                      WHEN bool_or(aj.status::text = 'FAILED'::text) THEN 'PARTIAL'::text
                      ELSE 'COMPLETE'::text
                      END                                                                 AS aggregation_status,
                  CURRENT_TIMESTAMP                                                       AS as_of_at,
                  false                                                                   AS is_stale,
                  max(aj.trace_id)                                                        AS source_watermark,
                  max(aj.completed_at) FILTER (WHERE aj.status::text = 'SUCCEEDED'::text) AS last_successful_at,
                  CASE
                      WHEN bool_or(aj.status::text = 'FAILED'::text) THEN 'ANALYSIS_FAILED'::text
                      ELSE NULL::text
                      END                                                                 AS failure_code,
                  count(*) FILTER (WHERE aj.status::text = 'FAILED'::text)::integer       AS affected_row_count,
                  bool_or(aj.status::text = 'FAILED'::text)                               AS mode_total_count_is_partial,
                  bool_or(aj.status::text = 'FAILED'::text)                               AS retryable,
                  CASE
                      WHEN bool_or(aj.status::text = 'FAILED'::text) THEN 'ASSESSMENT_ROUND'::text
                      ELSE NULL::text
                      END                                                                 AS retry_scope_code
           FROM rounds r
                    LEFT JOIN analysis_job aj ON aj.assessment_round_id = r.assessment_round_id
           GROUP BY r.org_id, r.manager_user_id, r.cohort_id, r.assessment_round_id, r.class_id
           UNION ALL
           SELECT r.org_id,
                  r.manager_user_id,
                  r.cohort_id,
                  r.assessment_round_id,
                  'ACTIVE'::text                                                        AS text,
                  'SELECTED_OR_CURRENT'::text                                           AS text,
                  md5((r.manager_user_id::text || ':'::text) || r.class_id::text)       AS md5,
                  'MEASUREMENT_ATTEMPT'::text                                           AS text,
                  CASE
                      WHEN count(ma.attempt_id) = 0 THEN 'NOT_STARTED'::text
                      WHEN bool_or(ma.status::text = 'FAILED'::text) THEN 'FAILED'::text
                      WHEN bool_and(ma.status::text = ANY
                                    (ARRAY ['COMPLETED'::character varying::text, 'EXPIRED'::character varying::text]))
                          THEN 'READY'::text
                      ELSE 'RUNNING'::text
                      END                                                               AS "case",
                  CASE
                      WHEN bool_or(ma.status::text = 'FAILED'::text) THEN 'PARTIAL'::text
                      ELSE 'COMPLETE'::text
                      END                                                               AS "case",
                  CURRENT_TIMESTAMP                                                     AS "current_timestamp",
                  false,
                  max(ma.updated_at)::text                                              AS max,
                  max(ma.updated_at) FILTER (WHERE ma.status::text = 'COMPLETED'::text) AS max,
                  CASE
                      WHEN bool_or(ma.status::text = 'FAILED'::text) THEN 'ATTEMPT_FAILED'::text
                      ELSE NULL::text
                      END                                                               AS "case",
                  count(*) FILTER (WHERE ma.status::text = 'FAILED'::text)::integer     AS count,
                  bool_or(ma.status::text = 'FAILED'::text)                             AS bool_or,
                  false,
                  NULL::text                                                            AS text
           FROM rounds r
                    LEFT JOIN measurement_attempt ma ON ma.assessment_round_id = r.assessment_round_id
           GROUP BY r.org_id, r.manager_user_id, r.cohort_id, r.assessment_round_id, r.class_id)
SELECT org_id,
       manager_user_id,
       cohort_id,
       assessment_round_id,
       list_mode::character varying(100)               AS list_mode,
       round_scope::character varying(100)             AS round_scope,
       assignment_scope_hash::character varying(128)   AS assignment_scope_hash,
       source_code::character varying(100)             AS source_code,
       source_readiness_status::character varying(100) AS source_readiness_status,
       aggregation_status::character varying(20)       AS aggregation_status,
       as_of_at,
       is_stale,
       source_watermark,
       last_successful_at,
       failure_code::character varying(100)            AS failure_code,
       affected_row_count,
       mode_total_count_is_partial::text               AS mode_total_count_is_partial,
       retryable::text                                 AS retryable,
       retry_scope_code::character varying(100)        AS retry_scope_code
FROM z;

alter table manager_interview_list_source_status_view
    owner to postgres;

grant delete, insert, select, update on manager_interview_list_source_status_view to teamiz_app;

create view manager_interview_list_view
            (org_id, manager_user_id, cohort_id, list_mode, round_scope, candidate_status_filter, class_id, class_name,
             team_id, team_name, candidate_id, candidate_status, candidate_row_version, candidate_qualification_status,
             active_matched_reason_count, active_matched_reason_codes, active_reason_summaries,
             matched_reason_codes_at_detection, matched_reason_summaries_at_detection, risk_policy_version_at_detection,
             candidate_detected_at, interview_id, interview_status, target_user_id, target_user_name, project_id,
             project_name, assessment_round_id, assessment_round_name, analysis_sequence_no, is_first_mini_project,
             validity_review_status, exclusion_reason_code, exclusion_note, excluded_by, excluded_at,
             can_reinclude_candidate, assignee_id, assignee_name, planned_at, started_at, completed_at, completed_by_id,
             completed_by_name, result_summary, confirmed_brief_id, interview_activity_count, follow_up_action_count,
             last_activity_at, primary_action_code, available_action_codes, action_unavailable_reason_code)
as
WITH scope AS (SELECT ma_1.manager_user_id,
                      ma_1.class_id
               FROM manager_assignment ma_1
               WHERE ma_1.status::text = 'ACTIVE'::text
                 AND ma_1.unassigned_at IS NULL)
SELECT ic.org_id,
       scope.manager_user_id,
       ic.cohort_id,
       'ACTIVE'::character varying(100)                                      AS list_mode,
       'SELECTED_OR_CURRENT'::character varying(100)                         AS round_scope,
       NULL::text                                                            AS candidate_status_filter,
       ic.class_id,
       c.name                                                                AS class_name,
       ic.team_id,
       t.name                                                                AS team_name,
       ic.candidate_id,
       ic.status                                                             AS candidate_status,
       ic.row_version                                                        AS candidate_row_version,
       CASE
           WHEN ic.status::text = 'EXCLUDED'::text THEN 'EXCLUDED'::text
           WHEN COALESCE(reason.active_count, 0) > 0 THEN 'QUALIFIED'::text
           ELSE 'PENDING'::text
           END::character varying(100)                                       AS candidate_qualification_status,
       COALESCE(reason.active_count, 0)                                      AS active_matched_reason_count,
       COALESCE(reason.codes, ARRAY []::text[]::character varying[])::text[] AS active_matched_reason_codes,
       to_jsonb(COALESCE(reason.summaries, ARRAY []::text[]))                AS active_reason_summaries,
       COALESCE(reason.codes, ARRAY []::text[]::character varying[])::text   AS matched_reason_codes_at_detection,
       to_jsonb(COALESCE(reason.summaries, ARRAY []::text[]))                AS matched_reason_summaries_at_detection,
       reason.policy_version::text                                           AS risk_policy_version_at_detection,
       ic.detected_at                                                        AS candidate_detected_at,
       i.interview_id,
       i.status                                                              AS interview_status,
       ic.user_id                                                            AS target_user_id,
       u.name                                                                AS target_user_name,
       ic.project_id,
       p.name                                                                AS project_name,
       ic.assessment_round_id,
       r.round_name                                                          AS assessment_round_name,
       r.round_no                                                            AS analysis_sequence_no,
       p.project_category::text = 'MINI_PROJECT'::text AND r.round_no = 1    AS is_first_mini_project,
       ma.validity_review_status,
       ic.exclusion_reason_code,
       ic.exclusion_note,
       ic.excluded_by,
       ic.excluded_at,
       ic.status::text = 'EXCLUDED'::text                                    AS can_reinclude_candidate,
       i.assignee_id,
       assignee.name                                                         AS assignee_name,
       i.planned_at,
       i.started_at,
       i.completed_at,
       i.completed_by                                                        AS completed_by_id,
       completer.name                                                        AS completed_by_name,
       i.result_summary,
       brief.brief_id                                                        AS confirmed_brief_id,
       COALESCE(act.activity_count, 0)                                       AS interview_activity_count,
       COALESCE(act.follow_up_count, 0)                                      AS follow_up_action_count,
       act.last_activity_at,
       CASE
           WHEN ic.status::text = 'EXCLUDED'::text THEN 'REINCLUDE'::text
           WHEN i.interview_id IS NULL THEN 'CREATE_INTERVIEW'::text
           WHEN i.status::text = 'PENDING'::text THEN 'START_INTERVIEW'::text
           WHEN i.status::text = 'IN_PROGRESS'::text THEN 'CONTINUE_INTERVIEW'::text
           ELSE 'VIEW_INTERVIEW'::text
           END::character varying(100)                                       AS primary_action_code,
       CASE
           WHEN ic.status::text = 'EXCLUDED'::text THEN ARRAY ['REINCLUDE'::text]
           WHEN i.interview_id IS NULL THEN ARRAY ['CREATE_INTERVIEW'::text, 'EXCLUDE'::text]
           WHEN i.status::text = 'PENDING'::text THEN ARRAY ['START_INTERVIEW'::text, 'EXCLUDE'::text]
           WHEN i.status::text = 'IN_PROGRESS'::text THEN ARRAY ['CONTINUE_INTERVIEW'::text, 'COMPLETE_INTERVIEW'::text]
           ELSE ARRAY ['VIEW_INTERVIEW'::text]
           END                                                               AS available_action_codes,
       NULL::character varying(100)                                          AS action_unavailable_reason_code
FROM interview_candidate ic
         JOIN scope ON scope.class_id = ic.class_id
         JOIN class c ON c.class_id = ic.class_id
         LEFT JOIN team t ON t.team_id = ic.team_id
         JOIN app_user u ON u.user_id = ic.user_id
         JOIN project p ON p.project_id = ic.project_id
         JOIN project_assessment_round r ON r.assessment_round_id = ic.assessment_round_id
         LEFT JOIN interview i ON i.candidate_id = ic.candidate_id
         LEFT JOIN app_user assignee ON assignee.user_id = i.assignee_id
         LEFT JOIN app_user completer ON completer.user_id = i.completed_by
         LEFT JOIN LATERAL ( SELECT count(*) FILTER (WHERE x.reason_status::text = 'ACTIVE'::text AND
                                                           x.evaluation_status::text =
                                                           'MATCHED'::text)::integer           AS active_count,
                                    array_agg(x.reason_code ORDER BY x.detected_at)
                                    FILTER (WHERE x.reason_status::text = 'ACTIVE'::text AND
                                                  x.evaluation_status::text = 'MATCHED'::text) AS codes,
                                    array_agg(x.reason_summary ORDER BY x.detected_at)
                                    FILTER (WHERE x.reason_status::text = 'ACTIVE'::text AND
                                                  x.evaluation_status::text = 'MATCHED'::text) AS summaries,
                                    max(x.policy_version)                                      AS policy_version
                             FROM interview_candidate_reason x
                             WHERE x.candidate_id = ic.candidate_id) reason ON true
         LEFT JOIN LATERAL ( SELECT x.brief_id
                             FROM interview_brief x
                             WHERE x.interview_id = i.interview_id
                               AND x.status::text = 'CONFIRMED'::text
                             ORDER BY x.version_no DESC
                             LIMIT 1) brief ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer                                          AS activity_count,
                                    count(*) FILTER (WHERE x.next_action IS NOT NULL)::integer AS follow_up_count,
                                    max(x.occurred_at)                                         AS last_activity_at
                             FROM interview_activity x
                             WHERE x.interview_id = i.interview_id) act ON true
         LEFT JOIN LATERAL ( SELECT x.attempt_id,
                                    x.org_id,
                                    x.cohort_id,
                                    x.assessment_round_id,
                                    x.project_id,
                                    x.user_id,
                                    x.assessment_contract_version,
                                    x.source_submission_id,
                                    x.code_analysis_id,
                                    x.attempt_type,
                                    x.source_attempt_id,
                                    x.attempt_sequence_no,
                                    x.assigned_at,
                                    x.assigned_by,
                                    x.review_source_report_id,
                                    x.review_source_report_snapshot_id,
                                    x.review_due_at,
                                    x.status,
                                    x.terminal_reason_code,
                                    x.terminal_at,
                                    x.analysis_completed_at,
                                    x.assessment_open_at,
                                    x.assessment_close_at,
                                    x.validity_review_status,
                                    x.validity_trigger_reason_code,
                                    x.validity_decision_reason_code,
                                    x.validity_decision_note,
                                    x.validity_review_started_at,
                                    x.validity_reviewed_by,
                                    x.validity_reviewed_at,
                                    x.row_version,
                                    x.updated_at,
                                    x.outcome_type_code,
                                    x.outcome_verdict,
                                    x.outcome_policy_version,
                                    x.outcome_judged_at
                             FROM measurement_attempt x
                             WHERE x.assessment_round_id = ic.assessment_round_id
                               AND x.user_id = ic.user_id
                               AND x.attempt_type::text = 'INITIAL'::text
                             ORDER BY x.attempt_sequence_no DESC
                             LIMIT 1) ma ON true;

alter table manager_interview_list_view
    owner to postgres;

grant delete, insert, select, update on manager_interview_list_view to teamiz_app;

create view manager_invalid_attempt_review_view
            (org_id, manager_user_id, cohort_id, class_id, class_name, team_id, team_name, project_id, project_name,
             assessment_round_id, assessment_round_name, target_user_id, target_user_name, attempt_id, session_id,
             attempt_status, terminal_reason_code, validity_review_status, validity_trigger_reason_code,
             validity_decision_reason_code, validity_decision_note, validity_review_started_at, validity_reviewed_by,
             validity_reviewed_by_name, validity_reviewed_at, attempt_updated_at, candidate_id, candidate_reason_id,
             interview_id, can_confirm_invalid, can_restore_valid, action_unavailable_reason_code, row_version,
             empty_state_code, aggregation_status, as_of_at)
as
WITH scope AS (SELECT ma_1.manager_user_id,
                      ma_1.class_id
               FROM manager_assignment ma_1
               WHERE ma_1.status::text = 'ACTIVE'::text
                 AND ma_1.unassigned_at IS NULL)
SELECT ma.org_id,
       scope.manager_user_id,
       ma.cohort_id,
       pm.class_id,
       c.name                                                      AS class_name,
       tm.team_id,
       t.name                                                      AS team_name,
       ma.project_id,
       p.name                                                      AS project_name,
       ma.assessment_round_id,
       r.round_name                                                AS assessment_round_name,
       ma.user_id                                                  AS target_user_id,
       u.name                                                      AS target_user_name,
       ma.attempt_id,
       s.session_id,
       ma.status                                                   AS attempt_status,
       ma.terminal_reason_code,
       ma.validity_review_status,
       ma.validity_trigger_reason_code,
       ma.validity_decision_reason_code,
       ma.validity_decision_note,
       ma.validity_review_started_at,
       ma.validity_reviewed_by::text                               AS validity_reviewed_by,
       reviewer.name                                               AS validity_reviewed_by_name,
       ma.validity_reviewed_at,
       ma.updated_at                                               AS attempt_updated_at,
       ic.candidate_id,
       icr.candidate_reason_id,
       i.interview_id,
       ma.validity_review_status::text = 'PENDING'::text           AS can_confirm_invalid,
       ma.validity_review_status::text = 'CONFIRMED_INVALID'::text AS can_restore_valid,
       CASE
           WHEN ma.validity_review_status::text <> ALL
                (ARRAY ['PENDING'::character varying::text, 'CONFIRMED_INVALID'::character varying::text])
               THEN 'REVIEW_NOT_ACTIONABLE'::text
           ELSE NULL::text
           END::character varying(100)                             AS action_unavailable_reason_code,
       ma.row_version,
       NULL::character varying(100)                                AS empty_state_code,
       'COMPLETE'::character varying(20)                           AS aggregation_status,
       CURRENT_TIMESTAMP                                           AS as_of_at
FROM measurement_attempt ma
         JOIN project_membership pm ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id
         JOIN scope ON scope.class_id = pm.class_id
         JOIN class c ON c.class_id = pm.class_id
         JOIN project p ON p.project_id = ma.project_id
         JOIN project_assessment_round r ON r.assessment_round_id = ma.assessment_round_id
         JOIN app_user u ON u.user_id = ma.user_id
         LEFT JOIN app_user reviewer ON reviewer.user_id = ma.validity_reviewed_by
         LEFT JOIN assessment_session s ON s.attempt_id = ma.attempt_id
         LEFT JOIN LATERAL ( SELECT x.team_id
                             FROM team_membership x
                             WHERE x.project_membership_id = pm.project_membership_id
                               AND x.from_at <= COALESCE(ma.assessment_open_at, CURRENT_TIMESTAMP)
                               AND (x.to_at IS NULL OR x.to_at > COALESCE(ma.assessment_open_at, CURRENT_TIMESTAMP))
                             ORDER BY x.from_at DESC
                             LIMIT 1) tm ON true
         LEFT JOIN team t ON t.team_id = tm.team_id
         LEFT JOIN interview_candidate ic ON ic.assessment_round_id = ma.assessment_round_id AND ic.user_id = ma.user_id
         LEFT JOIN interview_candidate_reason icr
                   ON icr.candidate_id = ic.candidate_id AND icr.reason_code::text = 'INVALID_ATTEMPT'::text AND
                      icr.reason_status::text = 'ACTIVE'::text
         LEFT JOIN interview i ON i.candidate_id = ic.candidate_id
WHERE ma.validity_review_status::text = ANY
      (ARRAY ['PENDING'::character varying::text, 'CONFIRMED_INVALID'::character varying::text, 'RESTORED_VALID'::character varying::text]);

alter table manager_invalid_attempt_review_view
    owner to postgres;

grant delete, insert, select, update on manager_invalid_attempt_review_view to teamiz_app;

create view manager_project_list_view
            (org_id, manager_user_id, cohort_id, selected_class_id, assigned_class_ids, project_id, project_sequence_no,
             project_name, project_category, project_status, assessment_round_id, assessment_round_name,
             assessment_round_no, round_display_status, curriculum_summary, concept_display_status,
             verification_concept_count, execution_stage, progress_unit_type, progress_completed_count,
             progress_target_count, assigned_class_count, assigned_trainee_count, lagging_class_summaries,
             action_summary_status, action_items, available_action_codes, action_unavailable_reason_codes,
             repository_ready_team_count, submitted_team_count, analysis_ready_team_count, assessment_completed_count,
             aggregation_status, as_of_at)
as
WITH manager_scope AS (SELECT ma.org_id,
                              ma.manager_user_id,
                              c.cohort_id,
                              array_agg(DISTINCT ma.class_id ORDER BY ma.class_id) AS assigned_class_ids
                       FROM manager_assignment ma
                                JOIN class c ON c.class_id = ma.class_id
                       WHERE ma.status::text = 'ACTIVE'::text
                         AND ma.unassigned_at IS NULL
                       GROUP BY ma.org_id, ma.manager_user_id, c.cohort_id),
     base AS (SELECT ms.org_id,
                     ms.manager_user_id,
                     ms.cohort_id,
                     ms.assigned_class_ids,
                     p.project_id,
                     p.sequence_no,
                     p.name                                                         AS project_name,
                     p.project_category,
                     p.lifecycle_status,
                     r.assessment_round_id,
                     r.round_name,
                     r.round_no,
                     r.status                                                       AS round_status,
                     (SELECT string_agg(cm.title::text, ', '::text ORDER BY pc.sequence_no) AS string_agg
                      FROM project_curriculum pc
                               JOIN curriculum_version cv ON cv.version_id = pc.curriculum_version_id
                               JOIN curriculum_material cm ON cm.material_id = cv.material_id
                      WHERE pc.project_id = p.project_id)                           AS curriculum_summary,
                     ((SELECT count(*) AS count
                       FROM project_verification_concept pvc
                                JOIN project_verification_concept_set pcs ON pcs.concept_set_id = pvc.concept_set_id
                       WHERE pcs.project_id = p.project_id
                         AND pcs.status::text = 'ACTIVE'::text))::integer           AS concept_count,
                     ((SELECT count(*) AS count
                       FROM project_membership pm
                       WHERE pm.project_id = p.project_id
                         AND (pm.class_id = ANY (ms.assigned_class_ids))
                         AND pm.status::text = 'ACTIVE'::text))::integer            AS assigned_trainee_count,
                     ((SELECT count(*) AS count
                       FROM team t
                       WHERE t.project_id = p.project_id
                         AND (t.class_id = ANY (ms.assigned_class_ids))
                         AND t.deleted_at IS NULL))::integer                        AS team_count,
                     ((SELECT count(*) AS count
                       FROM repository repo
                                JOIN team t ON t.team_id = repo.team_id
                       WHERE t.project_id = p.project_id
                         AND (t.class_id = ANY (ms.assigned_class_ids))
                         AND repo.status::text = 'ACTIVE'::text))::integer          AS repository_ready_team_count,
                     ((SELECT count(DISTINCT s.team_id) AS count
                       FROM submission s
                                JOIN team t ON t.team_id = s.team_id
                       WHERE t.project_id = p.project_id
                         AND (t.class_id = ANY (ms.assigned_class_ids))
                         AND s.assessment_round_id = r.assessment_round_id
                         AND s.is_current))::integer                                AS submitted_team_count,
                     ((SELECT count(DISTINCT aj.team_id) AS count
                       FROM analysis_job aj
                                JOIN team t ON t.team_id = aj.team_id
                       WHERE t.project_id = p.project_id
                         AND (t.class_id = ANY (ms.assigned_class_ids))
                         AND aj.assessment_round_id = r.assessment_round_id
                         AND aj.status::text = 'SUCCEEDED'::text))::integer         AS analysis_ready_team_count,
                     ((SELECT count(DISTINCT ma2.user_id) AS count
                       FROM measurement_attempt ma2
                                JOIN project_membership pm ON pm.user_id = ma2.user_id AND pm.project_id = p.project_id
                       WHERE ma2.assessment_round_id = r.assessment_round_id
                         AND ma2.attempt_type::text = 'INITIAL'::text
                         AND ma2.status::text = 'COMPLETED'::text
                         AND (pm.class_id = ANY (ms.assigned_class_ids))))::integer AS assessment_completed_count
              FROM manager_scope ms
                       JOIN project p ON p.cohort_id = ms.cohort_id AND p.deleted_at IS NULL
                       LEFT JOIN project_assessment_round r ON r.project_id = p.project_id AND r.deleted_at IS NULL)
SELECT org_id,
       manager_user_id,
       cohort_id,
       NULL::uuid                                                                                    AS selected_class_id,
       assigned_class_ids,
       project_id,
       sequence_no                                                                                   AS project_sequence_no,
       project_name,
       project_category,
       lifecycle_status                                                                              AS project_status,
       assessment_round_id,
       round_name                                                                                    AS assessment_round_name,
       round_no                                                                                      AS assessment_round_no,
       round_status                                                                                  AS round_display_status,
       curriculum_summary,
       CASE
           WHEN concept_count = 3 THEN 'READY'::text
           WHEN concept_count = 0 THEN 'EMPTY'::text
           ELSE 'INCOMPLETE'::text
           END::character varying(100)                                                               AS concept_display_status,
       concept_count                                                                                 AS verification_concept_count,
       CASE
           WHEN lifecycle_status::text = 'CLOSED'::text THEN 'CLOSED'::text
           WHEN submitted_team_count = 0 THEN 'SUBMISSION'::text
           WHEN analysis_ready_team_count < submitted_team_count THEN 'ANALYSIS'::text
           WHEN assessment_completed_count < assigned_trainee_count THEN 'ASSESSMENT'::text
           ELSE 'RESULT'::text
           END::character varying(30)                                                                AS execution_stage,
       'TRAINEE'::character varying(100)                                                             AS progress_unit_type,
       assessment_completed_count                                                                    AS progress_completed_count,
       assigned_trainee_count                                                                        AS progress_target_count,
       cardinality(assigned_class_ids)                                                               AS assigned_class_count,
       assigned_trainee_count,
       '[]'::jsonb                                                                                   AS lagging_class_summaries,
       CASE
           WHEN submitted_team_count < team_count THEN 'ACTION_REQUIRED'::text
           ELSE 'OK'::text
           END::character varying(100)                                                               AS action_summary_status,
       jsonb_build_array(
               jsonb_build_object('code', 'SUBMISSION', 'targetCount', GREATEST(team_count - submitted_team_count, 0)),
               jsonb_build_object('code', 'ANALYSIS', 'targetCount',
                                  GREATEST(submitted_team_count - analysis_ready_team_count, 0)),
               jsonb_build_object('code', 'ASSESSMENT', 'targetCount',
                                  GREATEST(assigned_trainee_count - assessment_completed_count, 0))) AS action_items,
       ARRAY ['OPEN_PROJECT'::text, 'OPEN_SUBMISSION'::text, 'OPEN_RESULTS'::text]                   AS available_action_codes,
       ARRAY []::text[]                                                                              AS action_unavailable_reason_codes,
       repository_ready_team_count,
       submitted_team_count,
       analysis_ready_team_count,
       assessment_completed_count,
       'COMPLETE'::character varying(20)                                                             AS aggregation_status,
       CURRENT_TIMESTAMP                                                                             AS as_of_at
FROM base x;

alter table manager_project_list_view
    owner to postgres;

grant delete, insert, select, update on manager_project_list_view to teamiz_app;

create view manager_project_result_view
            (org_id, cohort_id, project_id, assessment_round_id, class_id, team_id, user_id, report_id, report_type,
             lifecycle_status, latest_generation_status, report_snapshot_id, completion_status, published_at,
             trainee_release_status, trainee_disclosure_scope, eligible_trainee_count, assessed_trainee_count,
             sample_count, missing_count, is_provisional, attempt_id, validity_review_status, terminal_reason_code,
             problem_id, problem_scope, project_verification_concept_id, concept_display_name_snapshot,
             concept_display_order, highest_reached_level, result_display_status, axis_code, stage_status,
             answer_attempt_count, score, assistance_display_code, review_target_status, internal_evidence_available,
             trainee_evidence_available, aggregation_status, as_of_at, calculation_version)
as
SELECT rpt.org_id,
       rpt.cohort_id,
       ar.project_id,
       rpt.assessment_round_id,
       pm.class_id,
       tm.team_id,
       rpt.user_id,
       rpt.report_id,
       rpt.report_type,
       rpt.lifecycle_status,
       gr.status                                                                                  AS latest_generation_status,
       rs.snapshot_id                                                                             AS report_snapshot_id,
       rs.completion_status,
       rpt.published_at,
       rpt.trainee_release_status,
       rpt.trainee_disclosure_scope,
       COALESCE(rs.sample_count, 0) + COALESCE(rs.missing_count, 0)                               AS eligible_trainee_count,
       COALESCE(rs.sample_count, 0)                                                               AS assessed_trainee_count,
       rs.sample_count,
       rs.missing_count,
       rs.completion_status::text = 'PARTIAL'::text OR rpt.published_at IS NULL                   AS is_provisional,
       ma.attempt_id,
       ma.validity_review_status,
       ma.terminal_reason_code,
       ap.problem_id,
       ap.problem_scope,
       ap.project_verification_concept_id,
       COALESCE(re.subject_display_snapshot ->> 'conceptName'::text,
                tch.canonical_name::text)::character varying(200)                                 AS concept_display_name_snapshot,
       COALESCE(rm.display_order, pvc.sequence_no)                                                AS concept_display_order,
       CASE ap.best_success_stage
           WHEN 'L4'::text THEN 4
           WHEN 'L3'::text THEN 3
           WHEN 'L2'::text THEN 2
           WHEN 'L1'::text THEN 1
           ELSE 0
           END                                                                                    AS highest_reached_level,
       CASE
           WHEN ap.generation_status::text = 'NOT_GENERATED'::text THEN 'NO_PROBLEM'::text
           WHEN ma.validity_review_status::text = 'CONFIRMED_INVALID'::text THEN 'INVALID'::text
           WHEN ma.status::text = 'COMPLETED'::text THEN 'AVAILABLE'::text
           ELSE 'PENDING'::text
           END::character varying(100)                                                            AS result_display_status,
       ps.axis_code,
       ps.status::character varying(100)                                                          AS stage_status,
       (ps.question_answer_text IS NOT NULL)::integer + (ps.first_hint_answer_text IS NOT NULL)::integer +
       (ps.second_hint_answer_text IS NOT NULL)::integer                                          AS answer_attempt_count,
       COALESCE(ps.second_hint_score, ps.first_hint_score, ps.question_score)::numeric(18, 6)     AS score,
       CASE
           WHEN ps.second_hint_answer_text IS NOT NULL THEN 'SECOND_HINT'::text
           WHEN ps.first_hint_answer_text IS NOT NULL THEN 'FIRST_HINT'::text
           ELSE 'NONE'::text
           END::character varying(100)                                                            AS assistance_display_code,
       CASE
           WHEN re.evidence_id IS NOT NULL AND re.evidence_category::text = 'ANSWER_EXCERPT'::text THEN 'TARGET'::text
           ELSE 'NOT_TARGET'::text
           END::character varying(100)                                                            AS review_target_status,
       re.evidence_id IS NOT NULL                                                                 AS internal_evidence_available,
       re.evidence_id IS NOT NULL AND rpt.published_at IS NOT NULL                                AS trainee_evidence_available,
       COALESCE(rm.aggregation_status, 'SINGLE_SOURCE'::character varying)::character varying(20) AS aggregation_status,
       rs.as_of_at,
       rs.calculation_version
FROM report rpt
         JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
         LEFT JOIN LATERAL ( SELECT x.generation_run_id,
                                    x.report_id,
                                    x.trigger_type,
                                    x.idempotency_key,
                                    x.calculation_version,
                                    x.status,
                                    x.failure_reason,
                                    x.execution_no,
                                    x.started_at,
                                    x.completed_at,
                                    x.request_fingerprint
                             FROM report_generation_run x
                             WHERE x.report_id = rpt.report_id
                             ORDER BY x.execution_no DESC
                             LIMIT 1) gr ON true
         LEFT JOIN project_assessment_round ar ON ar.assessment_round_id = rpt.assessment_round_id
         LEFT JOIN measurement_attempt ma
                   ON ma.assessment_round_id = rpt.assessment_round_id AND ma.user_id = rpt.user_id AND
                      ma.attempt_type::text = 'INITIAL'::text
         LEFT JOIN project_membership pm ON pm.project_id = ar.project_id AND pm.user_id = rpt.user_id
         LEFT JOIN LATERAL ( SELECT x.team_id
                             FROM team_membership x
                             WHERE x.project_membership_id = pm.project_membership_id
                               AND x.from_at <= COALESCE(ma.assessment_open_at, rs.as_of_at)
                               AND (x.to_at IS NULL OR x.to_at > COALESCE(ma.assessment_open_at, rs.as_of_at))
                             ORDER BY x.from_at DESC
                             LIMIT 1) tm ON true
         LEFT JOIN assessment_problem ap
                   ON ap.measurement_attempt_id = ma.attempt_id OR ap.code_analysis_id = ma.code_analysis_id
         LEFT JOIN problem_stage ps ON ps.problem_id = ap.problem_id
         LEFT JOIN project_verification_concept pvc ON pvc.project_concept_id = ap.project_verification_concept_id
         LEFT JOIN teaches tch ON tch.teaches_id = pvc.teaches_id
         LEFT JOIN report_metric rm ON rm.snapshot_id = rs.snapshot_id AND
                                       (rm.project_verification_concept_id = ap.project_verification_concept_id OR
                                        rm.metric_grain_code::text = 'REPORT'::text)
         LEFT JOIN report_evidence re ON re.snapshot_id = rs.snapshot_id AND
                                         (re.problem_id = ap.problem_id OR re.problem_stage_id = ps.problem_stage_id)
WHERE rpt.user_id IS NOT NULL;

alter table manager_project_result_view
    owner to postgres;

grant delete, insert, select, update on manager_project_result_view to teamiz_app;

create view manager_project_submission_view
            (org_id, cohort_id, project_id, assessment_round_id, class_id, team_formation_stage, team_count,
             eligible_member_count, assigned_member_count, unassigned_member_count, submission_target_team_count,
             submitted_team_count, unsubmitted_team_count, analysis_failed_team_count, submission_due_at,
             remaining_submission_seconds, can_auto_assign, can_add_team, can_assign_unassigned_member, can_confirm,
             can_reopen, team_id, team_number, team_name, team_status, team_display_status, member_count,
             min_member_count, max_member_count, remaining_capacity, repository_url, submission_id, submission_method,
             submission_status, submitted_by_user_id, submitted_by_name, submitted_at, analysis_job_id, analysis_status,
             code_analysis_id, team_lag_reason_codes, project_membership_id, user_id, user_name, primary_attempt_id,
             primary_attempt_status, assessment_open_at, assessment_close_at, assessment_completed_at,
             problem_highest_levels, individual_lag_reason_codes, requirement_id, requirement_result, evidence_summary,
             evidence_locations, requirement_assessment_version, latest_reminder_at, reminder_reason_code,
             reminder_status, row_versions, as_of_at, aggregation_status)
as
WITH class_base AS (SELECT p.org_id,
                           p.cohort_id,
                           p.project_id,
                           p.lifecycle_status                                       AS project_status,
                           r.assessment_round_id,
                           r.submission_due_at,
                           c.class_id,
                           ((SELECT count(*) AS count
                             FROM project_membership pm_1
                             WHERE pm_1.project_id = p.project_id
                               AND pm_1.class_id = c.class_id
                               AND pm_1.status::text = 'ACTIVE'::text))::integer    AS eligible_member_count,
                           ((SELECT count(*) AS count
                             FROM team t_1
                             WHERE t_1.project_id = p.project_id
                               AND t_1.class_id = c.class_id
                               AND t_1.deleted_at IS NULL))::integer                AS team_count,
                           ((SELECT count(*) AS count
                             FROM team t_1
                             WHERE t_1.project_id = p.project_id
                               AND t_1.class_id = c.class_id
                               AND t_1.deleted_at IS NULL
                               AND t_1.status::text <> 'CONFIRMED'::text))::integer AS unconfirmed_team_count,
                           ((SELECT count(DISTINCT tm_1.project_membership_id) AS count
                             FROM team_membership tm_1
                                      JOIN team t_1 ON t_1.team_id = tm_1.team_id
                             WHERE t_1.project_id = p.project_id
                               AND t_1.class_id = c.class_id
                               AND tm_1.to_at IS NULL))::integer                    AS assigned_member_count,
                           ((SELECT count(*) AS count
                             FROM project_membership pm_1
                             WHERE pm_1.project_id = p.project_id
                               AND pm_1.class_id = c.class_id
                               AND pm_1.status::text = 'ACTIVE'::text))::integer -
                           ((SELECT count(DISTINCT tm_1.project_membership_id) AS count
                             FROM team_membership tm_1
                                      JOIN team t_1 ON t_1.team_id = tm_1.team_id
                             WHERE t_1.project_id = p.project_id
                               AND t_1.class_id = c.class_id
                               AND tm_1.to_at IS NULL))::integer                    AS unassigned_member_count,
                           ((SELECT count(DISTINCT s.team_id) AS count
                             FROM submission s
                                      JOIN team t_1 ON t_1.team_id = s.team_id
                             WHERE t_1.project_id = p.project_id
                               AND t_1.class_id = c.class_id
                               AND s.assessment_round_id = r.assessment_round_id
                               AND s.is_current))::integer                          AS submitted_team_count,
                           ((SELECT count(DISTINCT aj_1.team_id) AS count
                             FROM analysis_job aj_1
                                      JOIN team t_1 ON t_1.team_id = aj_1.team_id
                             WHERE t_1.project_id = p.project_id
                               AND t_1.class_id = c.class_id
                               AND aj_1.assessment_round_id = r.assessment_round_id
                               AND aj_1.status::text = 'FAILED'::text))::integer    AS analysis_failed_team_count
                    FROM project p
                             JOIN project_assessment_round r ON r.project_id = p.project_id AND r.deleted_at IS NULL
                             JOIN class c ON c.cohort_id = p.cohort_id AND c.deleted_at IS NULL
                    WHERE p.deleted_at IS NULL)
SELECT b.org_id,
       b.cohort_id,
       b.project_id,
       b.assessment_round_id,
       b.class_id,
       CASE
           WHEN b.project_status::text = 'CLOSED'::text THEN 'CLOSED'::text
           WHEN b.team_count = 0 THEN 'NOT_STARTED'::text
           WHEN b.unassigned_member_count > 0 THEN 'FORMING'::text
           WHEN b.unconfirmed_team_count > 0 THEN 'READY_TO_CONFIRM'::text
           ELSE 'CONFIRMED'::text
           END                                                                                                       AS team_formation_stage,
       b.team_count,
       b.eligible_member_count,
       b.assigned_member_count,
       b.unassigned_member_count,
       b.team_count                                                                                                  AS submission_target_team_count,
       b.submitted_team_count,
       GREATEST(b.team_count - b.submitted_team_count, 0)                                                            AS unsubmitted_team_count,
       b.analysis_failed_team_count,
       b.submission_due_at,
       GREATEST(EXTRACT(epoch FROM b.submission_due_at - CURRENT_TIMESTAMP)::bigint,
                0::bigint)::integer                                                                                  AS remaining_submission_seconds,
       (b.project_status::text <> ALL
        (ARRAY ['RUNNING'::character varying::text, 'CLOSED'::character varying::text])) AND b.submitted_team_count =
                                                                                             0                       AS can_auto_assign,
       (b.project_status::text <> ALL
        (ARRAY ['RUNNING'::character varying::text, 'CLOSED'::character varying::text])) AND b.submitted_team_count =
                                                                                             0                       AS can_add_team,
       b.project_status::text <> ALL
       (ARRAY ['RUNNING'::character varying::text, 'CLOSED'::character varying::text])                               AS can_assign_unassigned_member,
       b.unassigned_member_count = 0 AND b.team_count > 0 AND b.unconfirmed_team_count >
                                                              0                                                      AS can_confirm,
       b.submitted_team_count = 0 AND b.project_status::text <> 'CLOSED'::text                                       AS can_reopen,
       t.team_id,
       t.team_number,
       t.name                                                                                                        AS team_name,
       t.status::character varying(100)                                                                              AS team_status,
       CASE
           WHEN sub.submission_id IS NOT NULL THEN 'SUBMITTED'::character varying
           WHEN t.status::text = 'CONFIRMED'::text THEN 'CONFIRMED'::character varying
           WHEN t.team_id IS NULL THEN 'UNASSIGNED'::character varying
           ELSE t.status
           END::character varying(100)                                                                               AS team_display_status,
       ts.member_count,
       t.min_member_count,
       t.max_member_count,
       CASE
           WHEN t.max_member_count IS NULL THEN NULL::integer
           ELSE GREATEST(t.max_member_count - COALESCE(ts.member_count, 0), 0)
           END::text                                                                                                 AS remaining_capacity,
       repo.repo_url                                                                                                 AS repository_url,
       sub.submission_id,
       sub.method                                                                                                    AS submission_method,
       sub.status                                                                                                    AS submission_status,
       sub.submitted_by                                                                                              AS submitted_by_user_id,
       submitter.name                                                                                                AS submitted_by_name,
       sub.submitted_at,
       aj.job_id                                                                                                     AS analysis_job_id,
       aj.status                                                                                                     AS analysis_status,
       ca.analysis_id                                                                                                AS code_analysis_id,
       array_remove(ARRAY [
                        CASE
                            WHEN sub.submission_id IS NULL AND CURRENT_TIMESTAMP <= b.submission_due_at
                                THEN 'SUBMISSION_MISSING'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN sub.submission_id IS NULL AND CURRENT_TIMESTAMP > b.submission_due_at
                                THEN 'SUBMISSION_MISSED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN aj.status::text = 'FAILED'::text THEN 'ANALYSIS_FAILED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN t.status::text <> 'CONFIRMED'::text THEN 'TEAM_NOT_CONFIRMED'::text
                            ELSE NULL::text
                            END],
                    NULL::text)                                                                                      AS team_lag_reason_codes,
       pm.project_membership_id,
       pm.user_id,
       u.name                                                                                                        AS user_name,
       att.primary_attempt_id,
       att.primary_attempt_status,
       att.primary_assessment_open_at                                                                                AS assessment_open_at,
       att.primary_assessment_close_at                                                                               AS assessment_close_at,
       ma.terminal_at                                                                                                AS assessment_completed_at,
       COALESCE(pr.levels, '[]'::jsonb)::text                                                                        AS problem_highest_levels,
       array_remove(ARRAY [
                        CASE
                            WHEN att.primary_attempt_id IS NULL THEN 'ATTEMPT_NOT_CREATED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN (att.primary_attempt_status::text = ANY
                                  (ARRAY ['NOT_STARTED'::character varying::text, 'SESSION_READY'::character varying::text])) AND
                                 att.primary_assessment_close_at > CURRENT_TIMESTAMP THEN 'ASSESSMENT_NOT_STARTED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN att.primary_attempt_status::text = 'EXPIRED'::text THEN 'ASSESSMENT_EXPIRED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN ma.validity_review_status::text = 'CONFIRMED_INVALID'::text THEN 'INVALID_ATTEMPT'::text
                            ELSE NULL::text
                            END],
                    NULL::text)                                                                                      AS individual_lag_reason_codes,
       req.requirement_id,
       req.result::character varying(100)                                                                            AS requirement_result,
       req.evidence_summary,
       req.evidence_locations,
       req.assessment_version                                                                                        AS requirement_assessment_version,
       att.as_of_at                                                                                                  AS latest_reminder_at,
       att.nudge_reason_code                                                                                         AS reminder_reason_code,
       att.latest_reminder_status                                                                                    AS reminder_status,
       jsonb_build_object('team', t.row_version, 'attempt', ma.row_version)                                          AS row_versions,
       CURRENT_TIMESTAMP                                                                                             AS as_of_at,
       'COMPLETE'::character varying(20)                                                                             AS aggregation_status
FROM class_base b
         LEFT JOIN team t ON t.project_id = b.project_id AND t.class_id = b.class_id AND t.deleted_at IS NULL
         LEFT JOIN LATERAL ( SELECT count(*)::integer AS member_count
                             FROM team_membership x
                             WHERE x.team_id = t.team_id
                               AND x.to_at IS NULL) ts ON true
         LEFT JOIN team_membership tm ON tm.team_id = t.team_id AND tm.to_at IS NULL
         LEFT JOIN project_membership pm ON pm.project_membership_id = tm.project_membership_id
         LEFT JOIN app_user u ON u.user_id = pm.user_id
         LEFT JOIN LATERAL ( SELECT x.submission_id,
                                    x.org_id,
                                    x.team_id,
                                    x.assessment_round_id,
                                    x.method,
                                    x.repository_id,
                                    x.requested_branch,
                                    x.resolved_branch,
                                    x.source_commit_sha,
                                    x.source_commit_message,
                                    x.source_commit_committed_at,
                                    x.supersedes_submission_id,
                                    x.submitted_by,
                                    x.status,
                                    x.submitted_at,
                                    x.is_current,
                                    x.failure_reason,
                                    x.analysis_input_hash,
                                    x.git_history,
                                    x.analysis_input_file_count,
                                    x.analysis_input_captured_at,
                                    x.code_snippets,
                                    x.analysis_input_byte_count,
                                    x.repository_verification_id,
                                    x.request_idempotency_key
                             FROM submission x
                             WHERE x.team_id = t.team_id
                               AND x.assessment_round_id = b.assessment_round_id
                             ORDER BY x.is_current DESC, x.submitted_at DESC
                             LIMIT 1) sub ON true
         LEFT JOIN repository repo ON repo.repository_id = sub.repository_id
         LEFT JOIN app_user submitter ON submitter.user_id = sub.submitted_by
         LEFT JOIN LATERAL ( SELECT x.job_id,
                                    x.org_id,
                                    x.assessment_round_id,
                                    x.team_id,
                                    x.submission_id,
                                    x.analysis_id,
                                    x.question_focus_version_no,
                                    x.batch_key,
                                    x.job_type,
                                    x.execution_no,
                                    x.status,
                                    x.started_at,
                                    x.completed_at,
                                    x.failure_reason,
                                    x.trace_id,
                                    x.external_job_id,
                                    x.extraction_scope_id,
                                    x.requested_model_id,
                                    x.question_budget,
                                    x.request_payload,
                                    x.request_payload_hash,
                                    x.payload_schema_version,
                                    x.failure_code,
                                    x.created_at
                             FROM analysis_job x
                             WHERE x.team_id = t.team_id
                               AND x.assessment_round_id = b.assessment_round_id
                             ORDER BY x.execution_no DESC, x.started_at DESC NULLS LAST
                             LIMIT 1) aj ON true
         LEFT JOIN code_analysis ca ON ca.analysis_id = aj.analysis_id
         LEFT JOIN assessment_round_attendance att
                   ON att.assessment_round_id = b.assessment_round_id AND att.class_id = b.class_id AND
                      att.team_id = t.team_id AND att.user_id = pm.user_id
         LEFT JOIN measurement_attempt ma ON ma.attempt_id = att.primary_attempt_id
         LEFT JOIN LATERAL ( SELECT jsonb_agg(jsonb_build_object('problemId', ap.problem_id, 'problemNo', ap.problem_no,
                                                                 'bestSuccessStage', ap.best_success_stage,
                                                                 'generationStatus', ap.generation_status)
                                              ORDER BY ap.problem_no) AS levels
                             FROM assessment_problem ap
                             WHERE ap.measurement_attempt_id = att.primary_attempt_id
                                OR ap.code_analysis_id = ma.code_analysis_id AND
                                   ap.problem_scope::text = 'TEAM_SHARED_PROBLEM'::text) pr ON true
         LEFT JOIN LATERAL ( SELECT pra.assessment_id,
                                    pra.requirement_id,
                                    pra.assessment_round_id,
                                    pra.team_id,
                                    pra.org_id,
                                    pra.result,
                                    pra.evidence_summary,
                                    pra.evidence_locations,
                                    pra.source_submission_id,
                                    pra.analysis_id,
                                    pra.assessment_version,
                                    pra.supersedes_assessment_id,
                                    pra.assessed_at,
                                    pra.assessed_by,
                                    pra.created_at,
                                    pra.assessment_note
                             FROM project_requirement_assessment pra
                                      JOIN project_requirement rq ON rq.requirement_id = pra.requirement_id
                             WHERE pra.assessment_round_id = b.assessment_round_id
                               AND pra.team_id = t.team_id
                             ORDER BY rq.sequence_no, pra.assessed_at DESC
                             LIMIT 1) req ON true;

alter table manager_project_submission_view
    owner to postgres;

grant delete, insert, select, update on manager_project_submission_view to teamiz_app;

create view manager_retried_trainee_heatmap_view
            (org_id, cohort_id, class_id, project_id, assessment_round_id, team_id, user_id, initial_attempt_id,
             comparison_attempt_id, comparison_attempt_type, attempt_sequence_no, problem_id, problem_no,
             initial_highest_reached_level, comparison_highest_reached_level, initial_result_status,
             comparison_result_status, comparison_delta, aggregation_status, as_of_at, calculation_version)
as
WITH problem_result AS (SELECT ma.org_id,
                               ma.cohort_id,
                               ma.project_id,
                               ma.assessment_round_id,
                               ma.user_id,
                               ma.attempt_id,
                               ma.attempt_type,
                               ma.source_attempt_id,
                               ma.attempt_sequence_no,
                               ma.status                                                                            AS attempt_status,
                               ma.validity_review_status,
                               ma.terminal_reason_code,
                               pm.class_id,
                               tm.team_id,
                               ap.problem_id,
                               ap.problem_no,
                               max(
                                       CASE
                                           WHEN ps.status::text = 'PASSED'::text THEN
                                               CASE ps.axis_code
                                                   WHEN 'L4'::text THEN 4
                                                   WHEN 'L3'::text THEN 3
                                                   WHEN 'L2'::text THEN 2
                                                   WHEN 'L1'::text THEN 1
                                                   ELSE 0
                                                   END
                                           ELSE 0
                                           END)                                                                     AS highest_reached_level,
                               CASE
                                   WHEN ma.validity_review_status::text = 'CONFIRMED_INVALID'::text THEN 'INVALID'::text
                                   WHEN ma.status::text = 'COMPLETED'::text THEN 'VALID'::text
                                   WHEN ma.terminal_reason_code::text = ANY
                                        (ARRAY ['NOT_ATTENDED'::character varying::text, 'NOT_SUBMITTED'::character varying::text])
                                       THEN 'NOT_ATTENDED'::text
                                   WHEN ma.status::text = 'FAILED'::text OR
                                        ma.terminal_reason_code::text = 'SESSION_INCOMPLETE'::text THEN 'INTERRUPTED'::text
                                   WHEN ma.status::text = 'EXPIRED'::text THEN 'NOT_ATTENDED'::text
                                   ELSE 'PENDING'::text
                                   END                                                                              AS result_status,
                               sum((ps.question_answer_text IS NOT NULL)::integer +
                                   (ps.first_hint_answer_text IS NOT NULL)::integer +
                                   (ps.second_hint_answer_text IS NOT NULL)::integer)::integer                      AS answer_attempt_count,
                               jsonb_agg(jsonb_build_object('axisCode', ps.axis_code, 'status', ps.status,
                                                            'questionScore', ps.question_score, 'firstHintScore',
                                                            ps.first_hint_score, 'secondHintScore',
                                                            ps.second_hint_score)
                                         ORDER BY ps.question_sequence_no)                                          AS score_summary
                        FROM measurement_attempt ma
                                 LEFT JOIN project_membership pm
                                           ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id AND
                                              pm.joined_at <=
                                              COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP) AND
                                              (pm.left_at IS NULL OR pm.left_at >
                                                                     COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP))
                                 LEFT JOIN LATERAL ( SELECT x.team_id
                                                     FROM team_membership x
                                                     WHERE x.project_membership_id = pm.project_membership_id
                                                       AND x.from_at <=
                                                           COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP)
                                                       AND (x.to_at IS NULL OR x.to_at >
                                                                               COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP))
                                                     ORDER BY x.from_at DESC
                                                     LIMIT 1) tm ON true
                                 LEFT JOIN assessment_session s ON s.attempt_id = ma.attempt_id
                                 LEFT JOIN problem_stage ps ON ps.session_id = s.session_id
                                 LEFT JOIN assessment_problem ap ON ap.problem_id = ps.problem_id
                        GROUP BY ma.org_id, ma.cohort_id, ma.project_id, ma.assessment_round_id, ma.user_id,
                                 ma.attempt_id, ma.attempt_type, ma.source_attempt_id, ma.attempt_sequence_no,
                                 ma.status, ma.validity_review_status, ma.terminal_reason_code, pm.class_id, tm.team_id,
                                 ap.problem_id, ap.problem_no)
SELECT c.org_id,
       c.cohort_id,
       c.class_id,
       c.project_id,
       c.assessment_round_id,
       c.team_id,
       c.user_id,
       c.source_attempt_id                                                       AS initial_attempt_id,
       c.attempt_id                                                              AS comparison_attempt_id,
       c.attempt_type                                                            AS comparison_attempt_type,
       c.attempt_sequence_no,
       c.problem_id,
       c.problem_no,
       COALESCE(i.highest_reached_level, 0)                                      AS initial_highest_reached_level,
       c.highest_reached_level                                                   AS comparison_highest_reached_level,
       COALESCE(i.result_status, 'SOURCE_MISSING'::text)::character varying(100) AS initial_result_status,
       c.result_status::character varying(100)                                   AS comparison_result_status,
       c.highest_reached_level - COALESCE(i.highest_reached_level, 0)            AS comparison_delta,
       CASE
           WHEN i.attempt_id IS NULL THEN 'PARTIAL_SOURCE_MISSING'::text
           ELSE 'COMPLETE'::text
           END::character varying(20)                                            AS aggregation_status,
       CURRENT_TIMESTAMP                                                         AS as_of_at,
       1                                                                         AS calculation_version
FROM problem_result c
         LEFT JOIN problem_result i ON i.attempt_id = c.source_attempt_id AND i.problem_no = c.problem_no
WHERE c.attempt_type::text = ANY (ARRAY ['RETRY'::character varying::text, 'REVIEW'::character varying::text]);

alter table manager_retried_trainee_heatmap_view
    owner to postgres;

grant delete, insert, select, update on manager_retried_trainee_heatmap_view to teamiz_app;

create view manager_team_heatmap_view
            (org_id, cohort_id, class_id, project_id, assessment_round_id, team_id, problem_no,
             average_highest_reached_level, valid_result_count, not_attended_count, invalid_attempt_count,
             interrupted_count, aggregation_status, as_of_at, calculation_version)
as
WITH problem_result AS (SELECT ma.org_id,
                               ma.cohort_id,
                               ma.project_id,
                               ma.assessment_round_id,
                               ma.user_id,
                               ma.attempt_id,
                               ma.attempt_type,
                               ma.source_attempt_id,
                               ma.attempt_sequence_no,
                               ma.status                                                                            AS attempt_status,
                               ma.validity_review_status,
                               ma.terminal_reason_code,
                               pm.class_id,
                               tm.team_id,
                               ap.problem_id,
                               ap.problem_no,
                               max(
                                       CASE
                                           WHEN ps.status::text = 'PASSED'::text THEN
                                               CASE ps.axis_code
                                                   WHEN 'L4'::text THEN 4
                                                   WHEN 'L3'::text THEN 3
                                                   WHEN 'L2'::text THEN 2
                                                   WHEN 'L1'::text THEN 1
                                                   ELSE 0
                                                   END
                                           ELSE 0
                                           END)                                                                     AS highest_reached_level,
                               CASE
                                   WHEN ma.validity_review_status::text = 'CONFIRMED_INVALID'::text THEN 'INVALID'::text
                                   WHEN ma.status::text = 'COMPLETED'::text THEN 'VALID'::text
                                   WHEN ma.terminal_reason_code::text = ANY
                                        (ARRAY ['NOT_ATTENDED'::character varying::text, 'NOT_SUBMITTED'::character varying::text])
                                       THEN 'NOT_ATTENDED'::text
                                   WHEN ma.status::text = 'FAILED'::text OR
                                        ma.terminal_reason_code::text = 'SESSION_INCOMPLETE'::text THEN 'INTERRUPTED'::text
                                   WHEN ma.status::text = 'EXPIRED'::text THEN 'NOT_ATTENDED'::text
                                   ELSE 'PENDING'::text
                                   END                                                                              AS result_status,
                               sum((ps.question_answer_text IS NOT NULL)::integer +
                                   (ps.first_hint_answer_text IS NOT NULL)::integer +
                                   (ps.second_hint_answer_text IS NOT NULL)::integer)::integer                      AS answer_attempt_count,
                               jsonb_agg(jsonb_build_object('axisCode', ps.axis_code, 'status', ps.status,
                                                            'questionScore', ps.question_score, 'firstHintScore',
                                                            ps.first_hint_score, 'secondHintScore',
                                                            ps.second_hint_score)
                                         ORDER BY ps.question_sequence_no)                                          AS score_summary
                        FROM measurement_attempt ma
                                 LEFT JOIN project_membership pm
                                           ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id AND
                                              pm.joined_at <=
                                              COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP) AND
                                              (pm.left_at IS NULL OR pm.left_at >
                                                                     COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP))
                                 LEFT JOIN LATERAL ( SELECT x.team_id
                                                     FROM team_membership x
                                                     WHERE x.project_membership_id = pm.project_membership_id
                                                       AND x.from_at <=
                                                           COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP)
                                                       AND (x.to_at IS NULL OR x.to_at >
                                                                               COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP))
                                                     ORDER BY x.from_at DESC
                                                     LIMIT 1) tm ON true
                                 LEFT JOIN assessment_session s ON s.attempt_id = ma.attempt_id
                                 LEFT JOIN problem_stage ps ON ps.session_id = s.session_id
                                 LEFT JOIN assessment_problem ap ON ap.problem_id = ps.problem_id
                        GROUP BY ma.org_id, ma.cohort_id, ma.project_id, ma.assessment_round_id, ma.user_id,
                                 ma.attempt_id, ma.attempt_type, ma.source_attempt_id, ma.attempt_sequence_no,
                                 ma.status, ma.validity_review_status, ma.terminal_reason_code, pm.class_id, tm.team_id,
                                 ap.problem_id, ap.problem_no)
SELECT org_id,
       cohort_id,
       class_id,
       project_id,
       assessment_round_id,
       team_id,
       problem_no,
       avg(highest_reached_level) FILTER (WHERE result_status = 'VALID'::text)::numeric(10, 4) AS average_highest_reached_level,
       count(*) FILTER (WHERE result_status = 'VALID'::text)::integer                          AS valid_result_count,
       count(*) FILTER (WHERE result_status = 'NOT_ATTENDED'::text)::integer                   AS not_attended_count,
       count(*) FILTER (WHERE result_status = 'INVALID'::text)::integer                        AS invalid_attempt_count,
       count(*) FILTER (WHERE result_status = 'INTERRUPTED'::text)::integer                    AS interrupted_count,
       CASE
           WHEN count(*) = 0 THEN 'EMPTY'::text
           WHEN count(*) FILTER (WHERE result_status = 'VALID'::text) = 0 THEN 'NO_VALID_RESULT'::text
           ELSE 'COMPLETE'::text
           END::character varying(20)                                                          AS aggregation_status,
       CURRENT_TIMESTAMP                                                                       AS as_of_at,
       1                                                                                       AS calculation_version
FROM problem_result pr
WHERE attempt_type::text = 'INITIAL'::text
GROUP BY org_id, cohort_id, class_id, project_id, assessment_round_id, team_id, problem_no;

alter table manager_team_heatmap_view
    owner to postgres;

grant delete, insert, select, update on manager_team_heatmap_view to teamiz_app;

create view manager_trainee_detail_timeline_view
            (org_id, manager_user_id, cohort_id, target_user_id, project_id, assessment_round_id, analysis_sequence_no,
             round_name, project_name, team_id_at_round, team_name_at_round, round_activity_start_at,
             round_activity_end_at, round_sort_at, event_id, event_type, event_type_order, occurred_at,
             source_entity_type, source_entity_id, source_status, session_id, is_expandable, detail_action_code,
             payload, row_aggregation_status, is_stale, as_of_at, calculation_version)
as
WITH manager_scope AS (SELECT ma.org_id,
                              ma.manager_user_id,
                              c.cohort_id,
                              ma.class_id
                       FROM manager_assignment ma
                                JOIN class c ON c.class_id = ma.class_id
                       WHERE ma.status::text = 'ACTIVE'::text
                         AND ma.unassigned_at IS NULL),
     attempt_scope AS (SELECT ms.org_id,
                              ms.manager_user_id,
                              ms.cohort_id,
                              ma.user_id    AS target_user_id,
                              ma.attempt_id,
                              ma.attempt_type,
                              ma.source_attempt_id,
                              ma.code_analysis_id,
                              ma.status,
                              ma.terminal_reason_code,
                              ma.terminal_at,
                              ma.updated_at,
                              ma.assessment_open_at,
                              ma.assessment_close_at,
                              ma.review_due_at,
                              ma.attempt_sequence_no,
                              ma.project_id,
                              ma.assessment_round_id,
                              r.round_name,
                              p.name        AS project_name,
                              p.sequence_no AS analysis_sequence_no,
                              tm.team_id,
                              t.name        AS team_name,
                              s.session_id
                       FROM manager_scope ms
                                JOIN project_membership pm ON pm.class_id = ms.class_id AND pm.status::text = 'ACTIVE'::text
                                JOIN measurement_attempt ma ON ma.user_id = pm.user_id AND ma.project_id = pm.project_id
                                JOIN project_assessment_round r ON r.assessment_round_id = ma.assessment_round_id
                                JOIN project p ON p.project_id = r.project_id
                                LEFT JOIN LATERAL ( SELECT x.team_id
                                                    FROM team_membership x
                                                    WHERE x.project_membership_id = pm.project_membership_id
                                                      AND x.from_at <= COALESCE(ma.assessment_open_at, CURRENT_TIMESTAMP)
                                                      AND (x.to_at IS NULL OR
                                                           x.to_at > COALESCE(ma.assessment_open_at, CURRENT_TIMESTAMP))
                                                    ORDER BY x.from_at DESC
                                                    LIMIT 1) tm ON true
                                LEFT JOIN team t ON t.team_id = tm.team_id
                                LEFT JOIN assessment_session s ON s.attempt_id = ma.attempt_id),
     round_context AS (SELECT attempt_scope.manager_user_id,
                              attempt_scope.cohort_id,
                              attempt_scope.target_user_id,
                              attempt_scope.assessment_round_id,
                              max(attempt_scope.project_id::text)::uuid                               AS project_id,
                              max(attempt_scope.analysis_sequence_no)                                 AS analysis_sequence_no,
                              max(attempt_scope.round_name::text)                                     AS round_name,
                              max(attempt_scope.project_name::text)                                   AS project_name,
                              max(attempt_scope.team_id::text)
                              FILTER (WHERE attempt_scope.attempt_type::text = 'INITIAL'::text)::uuid AS team_id,
                              max(attempt_scope.team_name::text)
                              FILTER (WHERE attempt_scope.attempt_type::text = 'INITIAL'::text)       AS team_name,
                              min(attempt_scope.assessment_open_at)
                              FILTER (WHERE attempt_scope.attempt_type::text = 'INITIAL'::text)       AS activity_start_at,
                              max(COALESCE(attempt_scope.terminal_at, attempt_scope.assessment_close_at))
                              FILTER (WHERE attempt_scope.attempt_type::text = 'INITIAL'::text)       AS activity_end_at
                       FROM attempt_scope
                       GROUP BY attempt_scope.manager_user_id, attempt_scope.cohort_id, attempt_scope.target_user_id,
                                attempt_scope.assessment_round_id),
     problem_result AS (SELECT a.attempt_id,
                               ap.problem_id,
                               ap.problem_no,
                               ap.project_verification_concept_id                       AS concept_id,
                               COALESCE(tc.canonical_name, ap.title::character varying) AS concept_name,
                               ap.generation_status,
                               COALESCE(st.answered_axis_count, 0)                      AS answered_axis_count,
                               st.reach_level,
                               st.hint_used_count
                        FROM attempt_scope a
                                 JOIN assessment_problem ap ON ap.measurement_attempt_id = a.attempt_id OR
                                                               ap.code_analysis_id = a.code_analysis_id AND
                                                               ap.problem_scope::text = 'TEAM_SHARED_PROBLEM'::text
                                 LEFT JOIN project_verification_concept pvc
                                           ON pvc.project_concept_id = ap.project_verification_concept_id
                                 LEFT JOIN teaches tc ON tc.teaches_id = pvc.teaches_id
                                 LEFT JOIN LATERAL ( SELECT count(*) FILTER (WHERE ps.status::text = ANY
                                                                                   (ARRAY ['PASSED'::character varying::text, 'NOT_PASSED'::character varying::text]))::integer AS answered_axis_count,
                                                            COALESCE(max(SUBSTRING(ps.axis_code FROM 2)::integer)
                                                                     FILTER (WHERE ps.status::text = 'PASSED'::text),
                                                                     0)                                                                                                         AS reach_level,
                                                            (array_agg(
                                                             (ps.first_hint_answered_at IS NOT NULL)::integer +
                                                             (ps.second_hint_answered_at IS NOT NULL)::integer
                                                             ORDER BY (ps.status::text = 'PASSED'::text) DESC, (SUBSTRING(ps.axis_code FROM 2)::integer) DESC)
                                                             FILTER (WHERE ps.status::text = ANY
                                                                           (ARRAY ['PASSED'::character varying::text, 'NOT_PASSED'::character varying::text])))[1]              AS hint_used_count
                                                     FROM problem_stage ps
                                                     WHERE ps.session_id = a.session_id
                                                       AND ps.problem_id = ap.problem_id) st ON true),
     review_target AS (SELECT a.manager_user_id,
                              a.cohort_id,
                              a.target_user_id,
                              a.assessment_round_id,
                              COALESCE(rs.retry_target_count, 0) AS target_count
                       FROM attempt_scope a
                                LEFT JOIN LATERAL ( SELECT x.report_id
                                                    FROM report x
                                                    WHERE x.assessment_round_id = a.assessment_round_id
                                                      AND x.user_id = a.target_user_id
                                                      AND x.lifecycle_status::text <> 'SUPERSEDED'::text
                                                    ORDER BY x.published_at DESC NULLS LAST, x.report_id DESC
                                                    LIMIT 1) rpt ON true
                                LEFT JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
                       WHERE a.attempt_type::text = 'INITIAL'::text),
     review_problem AS (SELECT a.org_id,
                               a.manager_user_id,
                               a.cohort_id,
                               a.target_user_id,
                               a.attempt_id,
                               a.attempt_type,
                               a.source_attempt_id,
                               a.code_analysis_id,
                               a.status,
                               a.terminal_reason_code,
                               a.terminal_at,
                               a.updated_at,
                               a.assessment_open_at,
                               a.assessment_close_at,
                               a.review_due_at,
                               a.attempt_sequence_no,
                               a.project_id,
                               a.assessment_round_id,
                               a.round_name,
                               a.project_name,
                               a.analysis_sequence_no,
                               a.team_id,
                               a.team_name,
                               a.session_id,
                               pr.problem_id,
                               pr.problem_no,
                               pr.concept_id,
                               pr.concept_name,
                               pr.generation_status,
                               pr.answered_axis_count,
                               pr.reach_level  AS to_level,
                               src.reach_level AS from_level
                        FROM attempt_scope a
                                 JOIN problem_result pr ON pr.attempt_id = a.attempt_id
                                 LEFT JOIN problem_result src
                                           ON src.attempt_id = a.source_attempt_id AND src.problem_id = pr.problem_id
                        WHERE a.attempt_type::text = 'REVIEW'::text),
     assessment_events AS (SELECT a.org_id,
                                  a.manager_user_id,
                                  a.cohort_id,
                                  a.target_user_id,
                                  a.assessment_round_id,
                                  a.attempt_id                                           AS event_id,
                                  'ASSESSMENT'::text                                     AS event_type,
                                  10                                                     AS event_type_order,
                                  COALESCE(a.terminal_at, a.updated_at)                  AS occurred_at,
                                  'MEASUREMENT_ATTEMPT'::text                            AS source_entity_type,
                                  a.attempt_id                                           AS source_entity_id,
                                  a.status::text                                         AS source_status,
                                  a.session_id,
                                  a.session_id IS NOT NULL                               AS is_expandable,
                                  'OPEN_ASSESSMENT'::text                                AS detail_action_code,
                                  jsonb_build_object('attemptSequenceNo', a.attempt_sequence_no, 'problems',
                                                     COALESCE(pp.problems, '[]'::jsonb)) AS payload,
                                  'COMPLETE'::text                                       AS row_aggregation_status
                           FROM attempt_scope a
                                    LEFT JOIN LATERAL ( SELECT jsonb_agg(jsonb_build_object('problemNo', pr.problem_no,
                                                                                            'problemId', pr.problem_id,
                                                                                            'conceptId', pr.concept_id,
                                                                                            'conceptName',
                                                                                            pr.concept_name,
                                                                                            'generationStatus',
                                                                                            pr.generation_status,
                                                                                            'reachLevel',
                                                                                            CASE
                                                                                                WHEN
                                                                                                    pr.generation_status::text =
                                                                                                    'GENERATED'::text AND
                                                                                                    pr.answered_axis_count >
                                                                                                    0
                                                                                                    THEN pr.reach_level
                                                                                                ELSE NULL::integer
                                                                                                END, 'hintUsedCount',
                                                                                            CASE
                                                                                                WHEN
                                                                                                    pr.generation_status::text =
                                                                                                    'GENERATED'::text AND
                                                                                                    pr.answered_axis_count >
                                                                                                    0
                                                                                                    THEN COALESCE(pr.hint_used_count, 0)
                                                                                                ELSE NULL::integer
                                                                                                END)
                                                                         ORDER BY pr.problem_no) AS problems
                                                        FROM problem_result pr
                                                        WHERE pr.attempt_id = a.attempt_id) pp ON true
                           WHERE a.attempt_type::text = ANY
                                 (ARRAY ['INITIAL'::character varying::text, 'RETRY'::character varying::text])),
     report_events AS (SELECT ms.org_id,
                              ms.manager_user_id,
                              ms.cohort_id,
                              rpt.user_id                                                AS target_user_id,
                              rpt.assessment_round_id,
                              rpt.report_id                                              AS event_id,
                              'REPORT'::text                                             AS event_type,
                              20                                                         AS event_type_order,
                              COALESCE(rpt.published_at, rs.as_of_at)                    AS occurred_at,
                              'REPORT'::text                                             AS source_entity_type,
                              rpt.report_id                                              AS source_entity_id,
                              rpt.lifecycle_status::text                                 AS source_status,
                              NULL::uuid                                                 AS session_id,
                              true                                                       AS is_expandable,
                              'OPEN_REPORT'::text                                        AS detail_action_code,
                              jsonb_build_object('reviewTargetCount', COALESCE(rt.target_count, 0), 'summary',
                                                 rs.summary_payload ->> 'summary'::text) AS payload,
                              rs.completion_status::text                                 AS row_aggregation_status
                       FROM manager_scope ms
                                JOIN project_membership pm ON pm.class_id = ms.class_id AND pm.status::text = 'ACTIVE'::text
                                JOIN report rpt ON rpt.user_id = pm.user_id
                                JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
                                JOIN project_assessment_round ar ON ar.assessment_round_id = rpt.assessment_round_id
                                JOIN project p ON p.project_id = ar.project_id AND p.project_id = pm.project_id
                                LEFT JOIN review_target rt
                                          ON rt.manager_user_id = ms.manager_user_id AND rt.cohort_id = ms.cohort_id AND
                                             rt.target_user_id = rpt.user_id AND
                                             rt.assessment_round_id = rpt.assessment_round_id),
     review_events AS (SELECT rp.org_id,
                              rp.manager_user_id,
                              rp.cohort_id,
                              rp.target_user_id,
                              rp.assessment_round_id,
                              rp.attempt_id                           AS event_id,
                              'REVIEW'::text                          AS event_type,
                              30                                      AS event_type_order,
                              COALESCE(rp.terminal_at, rp.updated_at) AS occurred_at,
                              'MEASUREMENT_ATTEMPT'::text             AS source_entity_type,
                              rp.attempt_id                           AS source_entity_id,
                              rp.status::text                         AS source_status,
                              max(rp.session_id::text)::uuid          AS session_id,
                              max(rp.session_id::text) IS NOT NULL    AS is_expandable,
                              'OPEN_ASSESSMENT'::text                 AS detail_action_code,
                              jsonb_build_object('dueAt', max(rp.review_due_at), 'changes', jsonb_agg(
                                      jsonb_build_object('problemNo', rp.problem_no, 'problemId', rp.problem_id,
                                                         'conceptId', rp.concept_id, 'conceptName', rp.concept_name,
                                                         'fromReachLevel', rp.from_level, 'toReachLevel', rp.to_level,
                                                         'improved', COALESCE(rp.to_level, '-1'::integer) >
                                                                     COALESCE(rp.from_level, '-1'::integer))
                                      ORDER BY rp.problem_no))        AS payload,
                              'COMPLETE'::text                        AS row_aggregation_status
                       FROM review_problem rp
                       WHERE rp.answered_axis_count > 0
                       GROUP BY rp.org_id, rp.manager_user_id, rp.cohort_id, rp.target_user_id, rp.assessment_round_id,
                                rp.attempt_id, rp.terminal_at, rp.updated_at, rp.status),
     review_closed_events AS (SELECT rp.org_id,
                                     rp.manager_user_id,
                                     rp.cohort_id,
                                     rp.target_user_id,
                                     rp.assessment_round_id,
                                     rp.attempt_id                                                  AS event_id,
                                     'REVIEW_CLOSED'::text                                          AS event_type,
                                     40                                                             AS event_type_order,
                                     max(COALESCE(rp.review_due_at, rp.terminal_at, rp.updated_at)) AS occurred_at,
                                     'MEASUREMENT_ATTEMPT'::text                                    AS source_entity_type,
                                     rp.attempt_id                                                  AS source_entity_id,
                                     rp.status::text                                                AS source_status,
                                     NULL::uuid                                                     AS session_id,
                                     false                                                          AS is_expandable,
                                     NULL::text                                                     AS detail_action_code,
                                     jsonb_build_object('dueAt', max(rp.review_due_at), 'missedCount', count(*),
                                                        'missed', jsonb_agg(
                                                                jsonb_build_object('problemNo', rp.problem_no,
                                                                                   'problemId', rp.problem_id,
                                                                                   'conceptId', rp.concept_id,
                                                                                   'conceptName', rp.concept_name)
                                                                ORDER BY rp.problem_no))            AS payload,
                                     'COMPLETE'::text                                               AS row_aggregation_status
                              FROM review_problem rp
                              WHERE rp.answered_axis_count = 0
                                AND rp.generation_status::text = 'GENERATED'::text
                                AND (rp.review_due_at <= CURRENT_TIMESTAMP OR (rp.status::text = ANY
                                                                               (ARRAY ['EXPIRED'::character varying::text, 'FAILED'::character varying::text])) OR
                                     (rp.terminal_reason_code::text = ANY
                                      (ARRAY ['NOT_ATTENDED'::character varying::text, 'REVIEW_NOT_COMPLETED'::character varying::text])))
                              GROUP BY rp.org_id, rp.manager_user_id, rp.cohort_id, rp.target_user_id,
                                       rp.assessment_round_id, rp.attempt_id, rp.terminal_at, rp.updated_at, rp.status),
     interview_events AS (SELECT ms.org_id,
                                 ms.manager_user_id,
                                 ms.cohort_id,
                                 i.target_user_id,
                                 i.assessment_round_id,
                                 i.interview_id                                                        AS event_id,
                                 'INTERVIEW'::text                                                     AS event_type,
                                 50                                                                    AS event_type_order,
                                 COALESCE(i.completed_at, i.started_at, i.created_at)                  AS occurred_at,
                                 'INTERVIEW'::text                                                     AS source_entity_type,
                                 i.interview_id                                                        AS source_entity_id,
                                 i.status::text                                                        AS source_status,
                                 NULL::uuid                                                            AS session_id,
                                 true                                                                  AS is_expandable,
                                 'OPEN_INTERVIEW'::text                                                AS detail_action_code,
                                 jsonb_build_object('recordStatus', i.status, 'identifiedCause', i.result_summary,
                                                    'managerNote', ib.manager_note, 'nextAction', act.next_action,
                                                    'nextActionConfirmedAt',
                                                    (SELECT min(COALESCE(nx.started_at, nx.created_at)) AS min
                                                     FROM interview nx
                                                     WHERE nx.target_user_id = i.target_user_id
                                                       AND nx.class_id = i.class_id
                                                       AND COALESCE(nx.started_at, nx.created_at) >
                                                           COALESCE(i.completed_at, i.started_at, i.created_at)),
                                                    'managerActions', COALESCE(bi.items, '[]'::jsonb)) AS payload,
                                 CASE
                                     WHEN ib.brief_id IS NULL THEN 'PARTIAL'::text
                                     ELSE 'COMPLETE'::text
                                     END                                                               AS row_aggregation_status
                          FROM manager_scope ms
                                   JOIN interview i ON i.class_id = ms.class_id
                                   LEFT JOIN LATERAL ( SELECT x.brief_id,
                                                              x.interview_id,
                                                              x.org_id,
                                                              x.cohort_id,
                                                              x.user_id,
                                                              x.assessment_round_id,
                                                              x.brief_type,
                                                              x.version_no,
                                                              x.is_first_interview,
                                                              x.brief_generation_policy_version,
                                                              x.status,
                                                              x.manager_note,
                                                              x.opening_remark_text,
                                                              x.opening_remark_generated_at,
                                                              x.created_by,
                                                              x.created_at,
                                                              x.updated_by,
                                                              x.updated_at,
                                                              x.confirmed_by,
                                                              x.confirmed_at,
                                                              x.row_version,
                                                              x.last_request_id,
                                                              x.last_request_fingerprint
                                                       FROM interview_brief x
                                                       WHERE x.interview_id = i.interview_id
                                                       ORDER BY x.version_no DESC
                                                       LIMIT 1) ib ON true
                                   LEFT JOIN LATERAL ( SELECT jsonb_agg(jsonb_build_object('displayOrder',
                                                                                           bii.display_order,
                                                                                           'question',
                                                                                           bii.question_text,
                                                                                           'rationale',
                                                                                           bii.question_rationale)
                                                                        ORDER BY bii.display_order) AS items
                                                       FROM interview_brief_item bii
                                                       WHERE bii.brief_id = ib.brief_id
                                                         AND bii.is_selected) bi ON true
                                   LEFT JOIN LATERAL ( SELECT ia.next_action
                                                       FROM interview_activity ia
                                                       WHERE ia.interview_id = i.interview_id
                                                       ORDER BY ia.occurred_at DESC
                                                       LIMIT 1) act ON true),
     z AS (SELECT assessment_events.org_id,
                  assessment_events.manager_user_id,
                  assessment_events.cohort_id,
                  assessment_events.target_user_id,
                  assessment_events.assessment_round_id,
                  assessment_events.event_id,
                  assessment_events.event_type,
                  assessment_events.event_type_order,
                  assessment_events.occurred_at,
                  assessment_events.source_entity_type,
                  assessment_events.source_entity_id,
                  assessment_events.source_status,
                  assessment_events.session_id,
                  assessment_events.is_expandable,
                  assessment_events.detail_action_code,
                  assessment_events.payload,
                  assessment_events.row_aggregation_status
           FROM assessment_events
           UNION ALL
           SELECT report_events.org_id,
                  report_events.manager_user_id,
                  report_events.cohort_id,
                  report_events.target_user_id,
                  report_events.assessment_round_id,
                  report_events.event_id,
                  report_events.event_type,
                  report_events.event_type_order,
                  report_events.occurred_at,
                  report_events.source_entity_type,
                  report_events.source_entity_id,
                  report_events.source_status,
                  report_events.session_id,
                  report_events.is_expandable,
                  report_events.detail_action_code,
                  report_events.payload,
                  report_events.row_aggregation_status
           FROM report_events
           UNION ALL
           SELECT review_events.org_id,
                  review_events.manager_user_id,
                  review_events.cohort_id,
                  review_events.target_user_id,
                  review_events.assessment_round_id,
                  review_events.event_id,
                  review_events.event_type,
                  review_events.event_type_order,
                  review_events.occurred_at,
                  review_events.source_entity_type,
                  review_events.source_entity_id,
                  review_events.source_status,
                  review_events.session_id,
                  review_events.is_expandable,
                  review_events.detail_action_code,
                  review_events.payload,
                  review_events.row_aggregation_status
           FROM review_events
           UNION ALL
           SELECT review_closed_events.org_id,
                  review_closed_events.manager_user_id,
                  review_closed_events.cohort_id,
                  review_closed_events.target_user_id,
                  review_closed_events.assessment_round_id,
                  review_closed_events.event_id,
                  review_closed_events.event_type,
                  review_closed_events.event_type_order,
                  review_closed_events.occurred_at,
                  review_closed_events.source_entity_type,
                  review_closed_events.source_entity_id,
                  review_closed_events.source_status,
                  review_closed_events.session_id,
                  review_closed_events.is_expandable,
                  review_closed_events.detail_action_code,
                  review_closed_events.payload,
                  review_closed_events.row_aggregation_status
           FROM review_closed_events
           UNION ALL
           SELECT interview_events.org_id,
                  interview_events.manager_user_id,
                  interview_events.cohort_id,
                  interview_events.target_user_id,
                  interview_events.assessment_round_id,
                  interview_events.event_id,
                  interview_events.event_type,
                  interview_events.event_type_order,
                  interview_events.occurred_at,
                  interview_events.source_entity_type,
                  interview_events.source_entity_id,
                  interview_events.source_status,
                  interview_events.session_id,
                  interview_events.is_expandable,
                  interview_events.detail_action_code,
                  interview_events.payload,
                  interview_events.row_aggregation_status
           FROM interview_events)
SELECT z.org_id,
       z.manager_user_id,
       z.cohort_id,
       z.target_user_id,
       rc.project_id,
       z.assessment_round_id,
       rc.analysis_sequence_no,
       rc.round_name,
       rc.project_name,
       rc.team_id                                       AS team_id_at_round,
       rc.team_name                                     AS team_name_at_round,
       rc.activity_start_at                             AS round_activity_start_at,
       rc.activity_end_at                               AS round_activity_end_at,
       rc.analysis_sequence_no                          AS round_sort_at,
       z.event_id,
       z.event_type::character varying(100)             AS event_type,
       z.event_type_order,
       z.occurred_at,
       z.source_entity_type::character varying(100)     AS source_entity_type,
       z.source_entity_id,
       z.source_status::character varying(100)          AS source_status,
       z.session_id,
       z.is_expandable,
       z.detail_action_code::character varying(100)     AS detail_action_code,
       z.payload,
       z.row_aggregation_status::character varying(100) AS row_aggregation_status,
       false                                            AS is_stale,
       CURRENT_TIMESTAMP                                AS as_of_at,
       2                                                AS calculation_version
FROM z
         JOIN round_context rc ON rc.manager_user_id = z.manager_user_id AND rc.cohort_id = z.cohort_id AND
                                  rc.target_user_id = z.target_user_id AND
                                  rc.assessment_round_id = z.assessment_round_id;

alter table manager_trainee_detail_timeline_view
    owner to postgres;

grant delete, insert, select, update on manager_trainee_detail_timeline_view to teamiz_app;

create view manager_trainee_heatmap_view
            (org_id, cohort_id, class_id, project_id, assessment_round_id, team_id, user_id, problem_id, problem_no,
             highest_reached_level, problem_result_status, answer_attempt_count, score_summary, aggregation_status,
             as_of_at, calculation_version)
as
WITH problem_result AS (SELECT ma.org_id,
                               ma.cohort_id,
                               ma.project_id,
                               ma.assessment_round_id,
                               ma.user_id,
                               ma.attempt_id,
                               ma.attempt_type,
                               ma.source_attempt_id,
                               ma.attempt_sequence_no,
                               ma.status                                                                            AS attempt_status,
                               ma.validity_review_status,
                               ma.terminal_reason_code,
                               pm.class_id,
                               tm.team_id,
                               ap.problem_id,
                               ap.problem_no,
                               max(
                                       CASE
                                           WHEN ps.status::text = 'PASSED'::text THEN
                                               CASE ps.axis_code
                                                   WHEN 'L4'::text THEN 4
                                                   WHEN 'L3'::text THEN 3
                                                   WHEN 'L2'::text THEN 2
                                                   WHEN 'L1'::text THEN 1
                                                   ELSE 0
                                                   END
                                           ELSE 0
                                           END)                                                                     AS highest_reached_level,
                               CASE
                                   WHEN ma.validity_review_status::text = 'CONFIRMED_INVALID'::text THEN 'INVALID'::text
                                   WHEN ma.status::text = 'COMPLETED'::text THEN 'VALID'::text
                                   WHEN ma.terminal_reason_code::text = ANY
                                        (ARRAY ['NOT_ATTENDED'::character varying::text, 'NOT_SUBMITTED'::character varying::text])
                                       THEN 'NOT_ATTENDED'::text
                                   WHEN ma.status::text = 'FAILED'::text OR
                                        ma.terminal_reason_code::text = 'SESSION_INCOMPLETE'::text THEN 'INTERRUPTED'::text
                                   WHEN ma.status::text = 'EXPIRED'::text THEN 'NOT_ATTENDED'::text
                                   ELSE 'PENDING'::text
                                   END                                                                              AS result_status,
                               sum((ps.question_answer_text IS NOT NULL)::integer +
                                   (ps.first_hint_answer_text IS NOT NULL)::integer +
                                   (ps.second_hint_answer_text IS NOT NULL)::integer)::integer                      AS answer_attempt_count,
                               jsonb_agg(jsonb_build_object('axisCode', ps.axis_code, 'status', ps.status,
                                                            'questionScore', ps.question_score, 'firstHintScore',
                                                            ps.first_hint_score, 'secondHintScore',
                                                            ps.second_hint_score)
                                         ORDER BY ps.question_sequence_no)                                          AS score_summary
                        FROM measurement_attempt ma
                                 LEFT JOIN project_membership pm
                                           ON pm.project_id = ma.project_id AND pm.user_id = ma.user_id AND
                                              pm.joined_at <=
                                              COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP) AND
                                              (pm.left_at IS NULL OR pm.left_at >
                                                                     COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP))
                                 LEFT JOIN LATERAL ( SELECT x.team_id
                                                     FROM team_membership x
                                                     WHERE x.project_membership_id = pm.project_membership_id
                                                       AND x.from_at <=
                                                           COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP)
                                                       AND (x.to_at IS NULL OR x.to_at >
                                                                               COALESCE(ma.assessment_open_at, ma.assigned_at, CURRENT_TIMESTAMP))
                                                     ORDER BY x.from_at DESC
                                                     LIMIT 1) tm ON true
                                 LEFT JOIN assessment_session s ON s.attempt_id = ma.attempt_id
                                 LEFT JOIN problem_stage ps ON ps.session_id = s.session_id
                                 LEFT JOIN assessment_problem ap ON ap.problem_id = ps.problem_id
                        GROUP BY ma.org_id, ma.cohort_id, ma.project_id, ma.assessment_round_id, ma.user_id,
                                 ma.attempt_id, ma.attempt_type, ma.source_attempt_id, ma.attempt_sequence_no,
                                 ma.status, ma.validity_review_status, ma.terminal_reason_code, pm.class_id, tm.team_id,
                                 ap.problem_id, ap.problem_no)
SELECT org_id,
       cohort_id,
       class_id,
       project_id,
       assessment_round_id,
       team_id,
       user_id,
       problem_id,
       problem_no,
       highest_reached_level,
       result_status::character varying(100) AS problem_result_status,
       answer_attempt_count,
       score_summary::text                   AS score_summary,
       CASE
           WHEN problem_id IS NULL THEN 'PARTIAL'::text
           ELSE 'COMPLETE'::text
           END::character varying(20)        AS aggregation_status,
       CURRENT_TIMESTAMP                     AS as_of_at,
       1                                     AS calculation_version
FROM problem_result pr
WHERE attempt_type::text = 'INITIAL'::text;

alter table manager_trainee_heatmap_view
    owner to postgres;

grant delete, insert, select, update on manager_trainee_heatmap_view to teamiz_app;

create view manager_trainee_risk_view
            (user_id, assessment_round_id, contribution_status, comprehension_status, risk_type_code, is_applicable,
             policy_version, calculated_at, source_trace)
as
SELECT ma.user_id,
       ma.assessment_round_id,
       COALESCE(pcs.status, 'UNAVAILABLE'::character varying)::character varying(100) AS contribution_status,
       CASE
           WHEN ma.validity_review_status::text = 'CONFIRMED_INVALID'::text THEN 'INVALID'::text
           WHEN ma.status::text = 'COMPLETED'::text THEN 'AVAILABLE'::text
           WHEN ma.status::text = ANY (ARRAY ['FAILED'::character varying::text, 'EXPIRED'::character varying::text])
               THEN 'UNAVAILABLE'::text
           ELSE 'PENDING'::text
           END::character varying(100)                                                AS comprehension_status,
       CASE
           WHEN ma.validity_review_status::text = 'CONFIRMED_INVALID'::text THEN 'INVALID_ATTEMPT'::text
           ELSE NULL::text
           END::character varying(100)                                                AS risk_type_code,
       ma.validity_review_status::text = 'CONFIRMED_INVALID'::text                    AS is_applicable,
       COALESCE(pcs.calculation_policy_version, 1)                                    AS policy_version,
       COALESCE(pcs.calculated_at, ma.updated_at)                                     AS calculated_at,
       jsonb_build_object('attemptId', ma.attempt_id, 'attemptStatus', ma.status, 'validityReviewStatus',
                          ma.validity_review_status, 'contributionSnapshotId', pcs.contribution_snapshot_id,
                          'contributionStatus', pcs.status)::text                     AS source_trace
FROM measurement_attempt ma
         LEFT JOIN participant_contribution_snapshot pcs
                   ON pcs.assessment_round_id = ma.assessment_round_id AND pcs.user_id = ma.user_id
WHERE ma.attempt_type::text = 'INITIAL'::text;

alter table manager_trainee_risk_view
    owner to postgres;

grant delete, insert, select, update on manager_trainee_risk_view to teamiz_app;

create view manager_trainee_roster_view
            (org_id, manager_user_id, cohort_id, cohort_member_id, user_id, invitation_id, name, email, app_user_status,
             invitation_status, account_display_status, account_display_reason_code, account_status_as_of_at,
             current_cohort_membership_status, current_class_membership_status, current_class_id, project_id,
             assessment_round_id, analysis_sequence_no, class_id_at_round, project_membership_id, team_id_at_round,
             membership_as_of_at, attempt_id, session_id, result_source_attempt_id, result_source_session_id,
             snapshot_id, result_basis_code, current_round_result_readiness_status, row_result_status,
             result_pending_reason_code, round_result_availability_status, concept_result_items, expected_concept_count,
             scored_concept_count, unscored_concept_count, low_stage_concept_count, excellent_occurrence_count,
             latest_excellent_assessment_round_id, excellent_profile_readiness_status, risk_profile_readiness_status,
             current_round_primary_status_code, current_round_matched_risk_type_codes, risk_policy_version,
             risk_evaluated_at, sort_mode, risk_sort_key, primary_action_code, action_target_user_id,
             action_unavailable_reason_code, row_aggregation_status, as_of_at, calculation_version,
             excellent_assessment_sequence_nos, round_terminal_at, risk_reason_summary,
             current_round_not_attended_reason_code)
as
WITH scope AS (SELECT ma_1.org_id,
                      ma_1.manager_user_id,
                      c.cohort_id,
                      ma_1.class_id
               FROM manager_assignment ma_1
                        JOIN class c ON c.class_id = ma_1.class_id
               WHERE ma_1.status::text = 'ACTIVE'::text
                 AND ma_1.unassigned_at IS NULL),
     members AS (SELECT sc.org_id,
                        sc.manager_user_id,
                        sc.cohort_id,
                        cm.cohort_member_id,
                        cm.user_id,
                        NULL::uuid      AS invitation_id,
                        u.name,
                        u.email,
                        u.status::text  AS app_user_status,
                        NULL::text      AS invitation_status,
                        cm.status::text AS cohort_member_status,
                        cm.joined_at,
                        cm.left_at,
                        csm.class_membership_id,
                        csm.class_id
                 FROM scope sc
                          JOIN class_membership csm ON csm.class_id = sc.class_id
                          JOIN cohort_member cm ON cm.cohort_member_id = csm.cohort_member_id
                          JOIN app_user u ON u.user_id = cm.user_id
                 WHERE csm.unassigned_at IS NULL
                    OR cm.status::text = 'LEFT'::text AND cm.left_at IS NOT NULL AND csm.unassigned_at >= cm.left_at
                 UNION ALL
                 SELECT sc.org_id,
                        sc.manager_user_id,
                        sc.cohort_id,
                        NULL::uuid                     AS uuid,
                        NULL::uuid                     AS uuid,
                        ui.invitation_id,
                        NULL::text                     AS text,
                        ui.target_email,
                        NULL::text                     AS text,
                        ui.status::text                AS status,
                        NULL::text                     AS text,
                        ui.invited_at,
                        NULL::timestamp with time zone AS timestamptz,
                        NULL::uuid                     AS uuid,
                        ui.target_class_id
                 FROM scope sc
                          JOIN user_invitation ui ON ui.org_id = sc.org_id AND ui.target_class_id = sc.class_id
                 WHERE ui.target_role_code::text = 'TRAINEE'::text
                   AND ui.accepted_at IS NULL
                   AND ui.cancelled_at IS NULL),
     b
         AS (SELECT DISTINCT ON (members.manager_user_id, members.cohort_id, (COALESCE(members.user_id::text, members.invitation_id::text))) members.org_id,
                                                                                                                                             members.manager_user_id,
                                                                                                                                             members.cohort_id,
                                                                                                                                             members.cohort_member_id,
                                                                                                                                             members.user_id,
                                                                                                                                             members.invitation_id,
                                                                                                                                             members.name,
                                                                                                                                             members.email,
                                                                                                                                             members.app_user_status,
                                                                                                                                             members.invitation_status,
                                                                                                                                             members.cohort_member_status,
                                                                                                                                             members.joined_at,
                                                                                                                                             members.left_at,
                                                                                                                                             members.class_membership_id,
                                                                                                                                             members.class_id
             FROM members
             ORDER BY members.manager_user_id, members.cohort_id,
                      (COALESCE(members.user_id::text, members.invitation_id::text)), members.joined_at DESC),
     rd AS (SELECT p.cohort_id,
                   p.project_id,
                   r.assessment_round_id,
                   r.round_no,
                   r.submission_due_at,
                   p.sequence_no AS project_sequence_no
            FROM project p
                     JOIN project_assessment_round r ON r.project_id = p.project_id AND r.deleted_at IS NULL
            WHERE p.deleted_at IS NULL)
SELECT b.org_id,
       b.manager_user_id,
       b.cohort_id,
       b.cohort_member_id,
       b.user_id,
       b.invitation_id,
       b.name::character varying(200)                                                AS name,
       b.email::character varying(320)                                               AS email,
       b.app_user_status::character varying(100)                                     AS app_user_status,
       b.invitation_status::character varying(30)                                    AS invitation_status,
       CASE
           WHEN b.user_id IS NULL THEN COALESCE(b.invitation_status, 'INVITED'::text)
           WHEN b.app_user_status = 'ACTIVE'::text THEN 'ACTIVE'::text
           ELSE b.app_user_status
           END::character varying(100)                                               AS account_display_status,
       CASE
           WHEN b.user_id IS NULL THEN 'ACCOUNT_NOT_ACTIVATED'::text
           WHEN b.app_user_status <> 'ACTIVE'::text THEN 'ACCOUNT_INACTIVE'::text
           ELSE NULL::text
           END::character varying(100)                                               AS account_display_reason_code,
       CURRENT_TIMESTAMP                                                             AS account_status_as_of_at,
       b.cohort_member_status::character varying(100)                                AS current_cohort_membership_status,
       CASE
           WHEN b.class_membership_id IS NULL THEN 'UNASSIGNED'::text
           ELSE 'ASSIGNED'::text
           END::character varying(100)                                               AS current_class_membership_status,
       b.class_id                                                                    AS current_class_id,
       rd.project_id,
       rd.assessment_round_id,
       rd.project_sequence_no                                                        AS analysis_sequence_no,
       pm.class_id                                                                   AS class_id_at_round,
       pm.project_membership_id,
       tm.team_id                                                                    AS team_id_at_round,
       COALESCE(tm.from_at, pm.joined_at, b.joined_at)                               AS membership_as_of_at,
       ma.attempt_id,
       s.session_id,
       ma.attempt_id                                                                 AS result_source_attempt_id,
       s.session_id                                                                  AS result_source_session_id,
       rs.snapshot_id,
       CASE
           WHEN ma.attempt_id IS NULL THEN 'NO_ATTEMPT'::text
           WHEN ma.validity_review_status::text = 'CONFIRMED_INVALID'::text THEN 'INVALID'::text
           ELSE 'INITIAL'::text
           END::character varying(100)                                               AS result_basis_code,
       CASE
           WHEN rd.assessment_round_id IS NULL THEN 'NO_ROUND'::text
           WHEN ma.attempt_id IS NULL THEN 'NOT_STARTED'::text
           WHEN ma.status::text = 'COMPLETED'::text THEN 'READY'::text
           ELSE 'IN_PROGRESS'::text
           END::character varying(100)                                               AS current_round_result_readiness_status,
       COALESCE(ma.status, 'NOT_STARTED'::character varying)::character varying(100) AS row_result_status,
       CASE
           WHEN ma.attempt_id IS NULL THEN 'ATTEMPT_NOT_CREATED'::character varying
           WHEN ma.status::text <> 'COMPLETED'::text THEN ma.status
           ELSE NULL::character varying
           END::character varying(100)                                               AS result_pending_reason_code,
       CASE
           WHEN ma.status::text = 'COMPLETED'::text AND ma.validity_review_status::text <> 'CONFIRMED_INVALID'::text
               THEN 'AVAILABLE'::text
           ELSE 'UNAVAILABLE'::text
           END::character varying(100)                                               AS round_result_availability_status,
       COALESCE(pr.items, '[]'::jsonb)                                               AS concept_result_items,
       COALESCE(pr.assigned_count, 0)                                                AS expected_concept_count,
       COALESCE(pr.scored_count, 0)                                                  AS scored_concept_count,
       GREATEST(COALESCE(pr.assigned_count, 0) - COALESCE(pr.scored_count, 0), 0)    AS unscored_concept_count,
       CASE
           WHEN COALESCE(pr.scored_count, 0) > 0 THEN pr.low_count
           ELSE NULL::integer
           END                                                                       AS low_stage_concept_count,
       COALESCE(ex.excellent_count, 0)                                               AS excellent_occurrence_count,
       ex.latest_round_id                                                            AS latest_excellent_assessment_round_id,
       CASE
           WHEN COALESCE(ex.excellent_count, 0) > 0 THEN 'AVAILABLE'::text
           ELSE 'EMPTY'::text
           END::character varying(100)                                               AS excellent_profile_readiness_status,
       CASE
           WHEN ma.attempt_id IS NULL THEN 'NOT_READY'::text
           ELSE 'AVAILABLE'::text
           END::character varying(100)                                               AS risk_profile_readiness_status,
       CASE
           WHEN ma.terminal_reason_code::text = ANY
                (ARRAY ['NOT_ATTENDED'::character varying::text, 'NOT_SUBMITTED'::character varying::text,
                    'ANALYSIS_FAILED'::character varying::text]) THEN 'NOT_ATTENDED'::text
           WHEN ma.terminal_reason_code::text = 'SESSION_INCOMPLETE'::text THEN 'SESSION_INCOMPLETE'::text
           WHEN 'INVALID_ATTEMPT'::text = ANY (irr.codes::text[]) THEN 'INVALID_ATTEMPT'::text
           WHEN 'PERSISTENT_LOW'::text = ANY (irr.codes::text[]) THEN 'PERSISTENT_LOW'::text
           WHEN 'STAGE_DECLINE'::text = ANY (irr.codes::text[]) THEN 'STAGE_DECLINE'::text
           WHEN 'CONTRIBUTION_UNDERSTANDING_GAP'::text = ANY (irr.codes::text[]) THEN 'CONTRIBUTION_UNDERSTANDING_GAP'::text
           WHEN 'LOW_PARTICIPATION'::text = ANY (irr.codes::text[]) THEN 'LOW_PARTICIPATION'::text
           ELSE NULL::text
           END::character varying(100)                                               AS current_round_primary_status_code,
       COALESCE(irr.codes, ARRAY []::text[]::character varying[])::text[]            AS current_round_matched_risk_type_codes,
       COALESCE(irr.policy_version, 1)                                               AS risk_policy_version,
       irr.detected_at                                                               AS risk_evaluated_at,
       'RISK_DESC'::character varying(100)                                           AS sort_mode,
       COALESCE(pr.low_count, 0) * 100 +
       CASE
           WHEN ma.validity_review_status::text = 'CONFIRMED_INVALID'::text THEN 1000
           ELSE 0
           END                                                                       AS risk_sort_key,
       CASE
           WHEN b.user_id IS NULL THEN 'RESEND_INVITATION'::text
           WHEN ic.status::text = 'INTERVIEW_CREATED'::text THEN 'VIEW_INTERVIEW'::text
           WHEN ic.status::text = 'ELIGIBLE'::text THEN 'CREATE_INTERVIEW'::text
           WHEN ma.attempt_id IS NULL THEN 'VIEW_TRAINEE'::text
           ELSE 'VIEW_DETAIL'::text
           END::character varying(100)                                               AS primary_action_code,
       b.user_id                                                                     AS action_target_user_id,
       CASE
           WHEN b.user_id IS NULL THEN 'ACCOUNT_NOT_ACTIVATED'::text
           ELSE NULL::text
           END::character varying(100)                                               AS action_unavailable_reason_code,
       'COMPLETE'::character varying(100)                                            AS row_aggregation_status,
       CURRENT_TIMESTAMP                                                             AS as_of_at,
       1                                                                             AS calculation_version,
       ex.sequence_nos                                                               AS excellent_assessment_sequence_nos,
       CASE
           WHEN ma.terminal_reason_code::text = ANY
                (ARRAY ['NOT_ATTENDED'::character varying::text, 'SESSION_INCOMPLETE'::character varying::text,
                    'NOT_SUBMITTED'::character varying::text, 'ANALYSIS_FAILED'::character varying::text])
               THEN ma.terminal_at
           ELSE NULL::timestamp with time zone
           END                                                                       AS round_terminal_at,
       irr.reason_summary                                                            AS risk_reason_summary,
       CASE ma.terminal_reason_code::text
           WHEN 'NOT_ATTENDED'::text THEN 'NO_SHOW'::text
           WHEN 'NOT_SUBMITTED'::text THEN 'NOT_SUBMITTED'::text
           WHEN 'ANALYSIS_FAILED'::text THEN 'ANALYSIS_FAILED'::text
           ELSE NULL::text
           END::character varying(100)                                               AS current_round_not_attended_reason_code
FROM b
         LEFT JOIN rd ON rd.cohort_id = b.cohort_id
         LEFT JOIN project_membership pm
                   ON pm.project_id = rd.project_id AND pm.user_id = b.user_id AND pm.status::text = 'ACTIVE'::text
         LEFT JOIN LATERAL ( SELECT x.membership_id,
                                    x.team_id,
                                    x.project_membership_id,
                                    x.org_id,
                                    x.assignment_method,
                                    x.from_at,
                                    x.to_at,
                                    x.assigned_by,
                                    x.created_at
                             FROM team_membership x
                             WHERE x.project_membership_id = pm.project_membership_id
                               AND x.from_at <= CURRENT_TIMESTAMP
                               AND (x.to_at IS NULL OR x.to_at > CURRENT_TIMESTAMP)
                             ORDER BY x.from_at DESC
                             LIMIT 1) tm ON true
         LEFT JOIN measurement_attempt ma
                   ON ma.assessment_round_id = rd.assessment_round_id AND ma.user_id = b.user_id AND
                      ma.attempt_type::text = 'INITIAL'::text
         LEFT JOIN assessment_session s ON s.attempt_id = ma.attempt_id
         LEFT JOIN participant_contribution_snapshot pcs
                   ON pcs.assessment_round_id = rd.assessment_round_id AND pcs.user_id = b.user_id
         LEFT JOIN interview_candidate ic
                   ON ic.org_id = b.org_id AND ic.assessment_round_id = rd.assessment_round_id AND
                      ic.user_id = b.user_id
         LEFT JOIN LATERAL ( SELECT array_agg(icr.reason_code ORDER BY icr.reason_code) AS codes,
                                    max(icr.policy_version)                             AS policy_version,
                                    max(icr.detected_at)                                AS detected_at,
                                    string_agg(icr.reason_summary, ' · '::text ORDER BY icr.reason_code)
                                    FILTER (WHERE icr.reason_summary IS NOT NULL)       AS reason_summary
                             FROM interview_candidate_reason icr
                             WHERE icr.candidate_id = ic.candidate_id
                               AND icr.evaluation_status::text = 'MATCHED'::text
                               AND icr.reason_status::text = 'ACTIVE'::text
                               AND icr.source_assessment_round_id = rd.assessment_round_id) irr ON true
         LEFT JOIN LATERAL ( SELECT count(*)
                                    FILTER (WHERE ap.generation_status::text = 'GENERATED'::text)::integer AS assigned_count,
                                    count(*) FILTER (WHERE ap.generation_status::text = 'GENERATED'::text AND
                                                           st.answered_axis_count >
                                                           0)::integer                                     AS scored_count,
                                    count(*) FILTER (WHERE ap.generation_status::text = 'GENERATED'::text AND
                                                           st.answered_axis_count > 0 AND
                                                           st.reach_level <= 1)::integer                   AS low_count,
                                    jsonb_agg(jsonb_build_object('problemId', ap.problem_id, 'problemNo', ap.problem_no,
                                                                 'conceptId', ap.project_verification_concept_id,
                                                                 'conceptName',
                                                                 COALESCE(tc.canonical_name, ap.title::character varying),
                                                                 'generationStatus', ap.generation_status, 'reachLevel',
                                                                 CASE
                                                                     WHEN ap.generation_status::text =
                                                                          'GENERATED'::text AND
                                                                          st.answered_axis_count > 0 THEN st.reach_level
                                                                     ELSE NULL::integer
                                                                     END) ORDER BY ap.problem_no)          AS items
                             FROM assessment_problem ap
                                      LEFT JOIN project_verification_concept pvc
                                                ON pvc.project_concept_id = ap.project_verification_concept_id
                                      LEFT JOIN teaches tc ON tc.teaches_id = pvc.teaches_id
                                      LEFT JOIN LATERAL ( SELECT count(*) FILTER (WHERE ps.status::text = ANY
                                                                                        (ARRAY ['PASSED'::character varying::text, 'NOT_PASSED'::character varying::text]))::integer AS answered_axis_count,
                                                                 COALESCE(max(SUBSTRING(ps.axis_code FROM 2)::integer)
                                                                          FILTER (WHERE ps.status::text = 'PASSED'::text),
                                                                          0)                                                                                                         AS reach_level
                                                          FROM problem_stage ps
                                                          WHERE ps.session_id = s.session_id
                                                            AND ps.problem_id = ap.problem_id) st ON true
                             WHERE ap.measurement_attempt_id = ma.attempt_id
                                OR ap.code_analysis_id = ma.code_analysis_id AND
                                   ap.problem_scope::text = 'TEAM_SHARED_PROBLEM'::text) pr ON true
         LEFT JOIN LATERAL ( SELECT rs_1.snapshot_id
                             FROM report rpt
                                      JOIN report_snapshot rs_1 ON rs_1.report_id = rpt.report_id AND rs_1.is_active
                             WHERE rpt.user_id = b.user_id
                               AND rpt.assessment_round_id = rd.assessment_round_id
                             ORDER BY rs_1.snapshot_version DESC
                             LIMIT 1) rs ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer                                                               AS excellent_count,
                                    array_agg(x.round_id
                                              ORDER BY x.sequence_no DESC, (x.round_id::text) DESC)                 AS round_ids,
                                    array_agg(x.sequence_no ORDER BY x.sequence_no DESC)                            AS sequence_nos,
                                    (array_agg(x.round_id
                                               ORDER BY x.sequence_no DESC, (x.round_id::text) DESC))[1]            AS latest_round_id
                             FROM (SELECT DISTINCT re.source_assessment_round_id AS round_id,
                                                   p.sequence_no
                                   FROM report_evidence re
                                            JOIN report_snapshot rsn
                                                 ON rsn.snapshot_id = re.snapshot_id AND rsn.is_active
                                            JOIN project_assessment_round par
                                                 ON par.assessment_round_id = re.source_assessment_round_id
                                            JOIN project p ON p.project_id = par.project_id
                                   WHERE re.subject_user_id = b.user_id
                                     AND re.evidence_category::text = 'PARTICIPANT_RESULT_OCCURRENCE'::text
                                     AND re.participant_section_code::text = 'EXCELLENT_TRAINEE'::text) x) ex ON true;

alter table manager_trainee_roster_view
    owner to postgres;

grant delete, insert, select, update on manager_trainee_roster_view to teamiz_app;

create view operational_alert
            (org_id, cohort_id, alert_type, scope_type, scope_id, severity, reason_code, occurred_as_of_at) as
WITH manager_unassigned AS (SELECT c.org_id,
                                   c.cohort_id,
                                   'MANAGER_UNASSIGNED'::text AS alert_type,
                                   'CLASS'::text              AS scope_type,
                                   c.class_id                 AS scope_id,
                                   'HIGH'::text               AS severity,
                                   'NO_ACTIVE_MANAGER'::text  AS reason_code,
                                   CURRENT_TIMESTAMP          AS occurred_as_of_at
                            FROM class c
                            WHERE c.deleted_at IS NULL
                              AND c.lifecycle_status::text <> 'CLOSED'::text
                              AND NOT (EXISTS (SELECT 1
                                               FROM manager_assignment ma
                                               WHERE ma.class_id = c.class_id
                                                 AND ma.status::text = 'ACTIVE'::text
                                                 AND ma.unassigned_at IS NULL))),
     project_not_ready AS (SELECT p.org_id,
                                  p.cohort_id,
                                  'PROJECT_NOT_READY'::text AS "?column?",
                                  'PROJECT'::text           AS "?column?",
                                  p.project_id,
                                  'MEDIUM'::text            AS "?column?",
                                  CASE
                                      WHEN NOT p.curriculum_not_applicable AND NOT (EXISTS (SELECT 1
                                                                                            FROM project_curriculum pc
                                                                                            WHERE pc.project_id = p.project_id))
                                          THEN 'CURRICULUM_REQUIRED'::text
                                      WHEN ((SELECT count(*) AS count
                                             FROM project_verification_concept pvc
                                                      JOIN project_verification_concept_set pcs
                                                           ON pcs.concept_set_id = pvc.concept_set_id
                                             WHERE pcs.project_id = p.project_id
                                               AND pcs.status::text = 'ACTIVE'::text)) <> 3
                                          THEN 'THREE_CONCEPTS_REQUIRED'::text
                                      ELSE 'ROUND_REQUIRED'::text
                                      END                   AS "case",
                                  CURRENT_TIMESTAMP         AS "current_timestamp"
                           FROM project p
                           WHERE p.deleted_at IS NULL
                             AND (p.lifecycle_status::text <> ALL
                                  (ARRAY ['RUNNING'::character varying::text, 'CLOSED'::character varying::text]))
                             AND (NOT p.curriculum_not_applicable AND NOT (EXISTS (SELECT 1
                                                                                   FROM project_curriculum pc
                                                                                   WHERE pc.project_id = p.project_id)) OR
                                  ((SELECT count(*) AS count
                                    FROM project_verification_concept pvc
                                             JOIN project_verification_concept_set pcs
                                                  ON pcs.concept_set_id = pvc.concept_set_id
                                    WHERE pcs.project_id = p.project_id
                                      AND pcs.status::text = 'ACTIVE'::text)) <> 3 OR NOT (EXISTS (SELECT 1
                                                                                                   FROM project_assessment_round r
                                                                                                   WHERE r.project_id = p.project_id
                                                                                                     AND r.deleted_at IS NULL)))),
     analysis_failed AS (SELECT aj.org_id,
                                r.cohort_id,
                                'ANALYSIS_FAILED'::text                                     AS "?column?",
                                'TEAM'::text                                                AS "?column?",
                                aj.team_id,
                                'HIGH'::text                                                AS "?column?",
                                'ANALYSIS_JOB_FAILED'::text                                 AS "?column?",
                                COALESCE(aj.completed_at, aj.started_at, CURRENT_TIMESTAMP) AS "coalesce"
                         FROM analysis_job aj
                                  JOIN project_assessment_round r ON r.assessment_round_id = aj.assessment_round_id
                         WHERE aj.status::text = 'FAILED'::text),
     submission_overdue AS (SELECT r.org_id,
                                   r.cohort_id,
                                   'SUBMISSION_OVERDUE'::text   AS "?column?",
                                   'TEAM'::text                 AS "?column?",
                                   t.team_id,
                                   'HIGH'::text                 AS "?column?",
                                   'SUBMISSION_NOT_FOUND'::text AS "?column?",
                                   r.submission_due_at
                            FROM project_assessment_round r
                                     JOIN team t ON t.project_id = r.project_id AND t.deleted_at IS NULL
                            WHERE r.submission_due_at < CURRENT_TIMESTAMP
                              AND NOT (EXISTS (SELECT 1
                                               FROM submission s
                                               WHERE s.assessment_round_id = r.assessment_round_id
                                                 AND s.team_id = t.team_id
                                                 AND s.is_current))),
     z AS (SELECT manager_unassigned.org_id,
                  manager_unassigned.cohort_id,
                  manager_unassigned.alert_type,
                  manager_unassigned.scope_type,
                  manager_unassigned.scope_id,
                  manager_unassigned.severity,
                  manager_unassigned.reason_code,
                  manager_unassigned.occurred_as_of_at
           FROM manager_unassigned
           UNION ALL
           SELECT project_not_ready.org_id,
                  project_not_ready.cohort_id,
                  project_not_ready."?column?",
                  project_not_ready."?column?_1" AS "?column?",
                  project_not_ready.project_id,
                  project_not_ready."?column?_2" AS "?column?",
                  project_not_ready."case",
                  project_not_ready."current_timestamp"
           FROM project_not_ready project_not_ready(org_id, cohort_id, "?column?", "?column?_1", project_id,
                                                    "?column?_2", "case", "current_timestamp")
           UNION ALL
           SELECT analysis_failed.org_id,
                  analysis_failed.cohort_id,
                  analysis_failed."?column?",
                  analysis_failed."?column?_1" AS "?column?",
                  analysis_failed.team_id,
                  analysis_failed."?column?_2" AS "?column?",
                  analysis_failed."?column?_3" AS "?column?",
                  analysis_failed."coalesce"
           FROM analysis_failed analysis_failed(org_id, cohort_id, "?column?", "?column?_1", team_id, "?column?_2",
                                                "?column?_3", "coalesce")
           UNION ALL
           SELECT submission_overdue.org_id,
                  submission_overdue.cohort_id,
                  submission_overdue."?column?",
                  submission_overdue."?column?_1" AS "?column?",
                  submission_overdue.team_id,
                  submission_overdue."?column?_2" AS "?column?",
                  submission_overdue."?column?_3" AS "?column?",
                  submission_overdue.submission_due_at
           FROM submission_overdue submission_overdue(org_id, cohort_id, "?column?", "?column?_1", team_id,
                                                      "?column?_2", "?column?_3", submission_due_at))
SELECT org_id,
       cohort_id,
       alert_type::character varying(100)  AS alert_type,
       scope_type::character varying(100)  AS scope_type,
       scope_id,
       severity::character varying(100)    AS severity,
       reason_code::character varying(100) AS reason_code,
       occurred_as_of_at
FROM z;

alter table operational_alert
    owner to postgres;

grant delete, insert, select, update on operational_alert to teamiz_app;

create view operator_cohort_class_risk_view
            (snapshot_id, class_id, class_name_snapshot, eligible_trainee_count, risk_trainee_count, risk_rate,
             missing_count, aggregation_status, display_order)
as
SELECT rm.snapshot_id,
       rm.class_id,
       COALESCE(rm.dimension_snapshot ->> 'className'::text, c.name::text)::character varying(200) AS class_name_snapshot,
       COALESCE(rm.denominator, 0::numeric)::integer                                               AS eligible_trainee_count,
       COALESCE(rm.numerator, rm.metric_value, 0::numeric)::integer                                AS risk_trainee_count,
       CASE
           WHEN rm.denominator IS NULL OR rm.denominator = 0::numeric THEN NULL::numeric
           ELSE COALESCE(rm.numerator, rm.metric_value, 0::numeric) / rm.denominator
           END::numeric(18, 6)                                                                     AS risk_rate,
       COALESCE(rm.missing_count, 0)                                                               AS missing_count,
       rm.aggregation_status,
       rm.display_order
FROM report_metric rm
         LEFT JOIN class c ON c.class_id = rm.class_id
WHERE rm.section_code::text = 'CLASS_RISK'::text
  AND rm.metric_grain_code::text = 'CLASS'::text;

alter table operator_cohort_class_risk_view
    owner to postgres;

grant delete, insert, select, update on operator_cohort_class_risk_view to teamiz_app;

create view operator_cohort_group_underperformance_view
            (snapshot_id, class_id, class_name_snapshot, teaches_id, concept_name_snapshot, low_level_count,
             denominator, underperformance_flag, evaluated_class_concept_count, empty_reason_code, aggregation_status)
as
SELECT rm.snapshot_id,
       rm.class_id,
       COALESCE(rm.dimension_snapshot ->> 'className'::text, c.name::text)::character varying(200)             AS class_name_snapshot,
       rm.teaches_id,
       COALESCE(rm.dimension_snapshot ->> 'conceptName'::text,
                t.canonical_name::text)::character varying(200)                                                AS concept_name_snapshot,
       COALESCE(rm.numerator, rm.metric_value, 0::numeric)::integer                                            AS low_level_count,
       rm.denominator,
       CASE
           WHEN rm.metric_value IS NULL THEN NULL::boolean
           ELSE rm.metric_value > 0::numeric
           END                                                                                                 AS underperformance_flag,
       count(*) OVER (PARTITION BY rm.snapshot_id)::integer                                                    AS evaluated_class_concept_count,
       CASE
           WHEN rm.denominator IS NULL OR rm.denominator = 0::numeric THEN 'NO_ELIGIBLE_PARTICIPANT'::text
           ELSE NULL::text
           END::character varying(100)                                                                         AS empty_reason_code,
       rm.aggregation_status
FROM report_metric rm
         LEFT JOIN class c ON c.class_id = rm.class_id
         LEFT JOIN teaches t ON t.teaches_id = rm.teaches_id
WHERE rm.section_code::text = 'GROUP_UNDERPERFORMANCE'::text
  AND rm.metric_grain_code::text = 'CLASS_CONCEPT'::text;

alter table operator_cohort_group_underperformance_view
    owner to postgres;

grant delete, insert, select, update on operator_cohort_group_underperformance_view to teamiz_app;

create view operator_cohort_growth_transition_view
            (snapshot_id, transition_category, participant_count, denominator, baseline_boundary, outcome_boundary,
             eligible_count, excluded_count, policy_parameter_snapshot)
as
SELECT rm.snapshot_id,
       COALESCE(rm.dimension_key, re.result_category_code::text)::character varying(100)        AS transition_category,
       COALESCE(rm.numerator, rm.metric_value, 0::numeric)::integer                             AS participant_count,
       rm.denominator,
       COALESCE((rm.policy_parameter_snapshot ->> 'baselineBoundary'::text)::numeric,
                re.baseline_value)::numeric(18, 6)                                              AS baseline_boundary,
       COALESCE((rm.policy_parameter_snapshot ->> 'outcomeBoundary'::text)::numeric,
                re.outcome_value)::numeric(18, 6)                                               AS outcome_boundary,
       (COALESCE(rm.denominator, 0::numeric) - COALESCE(rm.missing_count, 0)::numeric)::integer AS eligible_count,
       COALESCE(rm.missing_count, 0)                                                            AS excluded_count,
       rm.policy_parameter_snapshot
FROM report_metric rm
         LEFT JOIN report_evidence re
                   ON re.metric_id = rm.metric_id AND re.participant_section_code::text = 'GROWTH_TRANSITION'::text
WHERE rm.section_code::text = 'COHORT_GROWTH_TRANSITION'::text
  AND rm.metric_grain_code::text = 'TRANSITION_CATEGORY'::text;

alter table operator_cohort_growth_transition_view
    owner to postgres;

grant delete, insert, select, update on operator_cohort_growth_transition_view to teamiz_app;

create view operator_cohort_outcome_occurrence_view
            (snapshot_id, subject_user_id, source_assessment_round_id, user_id, class_id, round_id,
             occurrence_result_code, is_joint_top, top_tie_count, source_role_code, subject_display_snapshot)
as
SELECT snapshot_id,
       subject_user_id,
       source_assessment_round_id,
       subject_user_id            AS user_id,
       source_class_id            AS class_id,
       source_assessment_round_id AS round_id,
       result_category_code       AS occurrence_result_code,
       is_joint_top,
       top_tie_count,
       source_role_code,
       subject_display_snapshot
FROM report_evidence re
WHERE evidence_category::text = 'PARTICIPANT_RESULT_OCCURRENCE'::text;

alter table operator_cohort_outcome_occurrence_view
    owner to postgres;

grant delete, insert, select, update on operator_cohort_outcome_occurrence_view to teamiz_app;

create view operator_cohort_outcome_participant_view
            (snapshot_id, participant_section_code, subject_user_id, user_id, display_name_snapshot, class_id,
             class_name_snapshot, eligibility_status, exclusion_reason_code, result_category_code, baseline_value,
             outcome_value, occurrence_count, is_joint_top, top_tie_count)
as
SELECT re.snapshot_id,
       re.participant_section_code,
       re.subject_user_id,
       re.subject_user_id                                                                                  AS user_id,
       COALESCE(re.subject_display_snapshot ->> 'displayName'::text,
                u.name::text)::character varying(200)                                                      AS display_name_snapshot,
       re.subject_class_id                                                                                 AS class_id,
       COALESCE(re.subject_display_snapshot ->> 'className'::text,
                c.name::text)::character varying(200)                                                      AS class_name_snapshot,
       re.eligibility_status,
       re.exclusion_reason_code,
       re.result_category_code,
       re.baseline_value,
       re.outcome_value,
       re.occurrence_count,
       re.is_joint_top,
       re.top_tie_count
FROM report_evidence re
         LEFT JOIN app_user u ON u.user_id = re.subject_user_id
         LEFT JOIN class c ON c.class_id = re.subject_class_id
WHERE re.evidence_category::text = 'PARTICIPANT_RESULT'::text;

alter table operator_cohort_outcome_participant_view
    owner to postgres;

grant delete, insert, select, update on operator_cohort_outcome_participant_view to teamiz_app;

create view operator_cohort_outcome_summary_view
            (snapshot_id, cohort_id, report_id, eligible_count, excluded_count, transition_counts,
             excellent_trainee_count, risky_class_count, group_underperformance_count, aggregation_status,
             policy_version)
as
SELECT rs.snapshot_id,
       rpt.cohort_id,
       rpt.report_id,
       COALESCE((rs.summary_payload ->> 'eligibleCount'::text)::integer, rs.sample_count)                 AS eligible_count,
       COALESCE((rs.summary_payload ->> 'excludedCount'::text)::integer,
                rs.missing_count)                                                                         AS excluded_count,
       COALESCE(rs.summary_payload -> 'transitionCounts'::text,
                '{}'::jsonb)                                                                              AS transition_counts,
       COALESCE((rs.summary_payload ->> 'excellentTraineeCount'::text)::integer, met.excellent_count,
                0)                                                                                        AS excellent_trainee_count,
       COALESCE((rs.summary_payload ->> 'riskyClassCount'::text)::integer, met.risky_class_count,
                0)                                                                                        AS risky_class_count,
       COALESCE((rs.summary_payload ->> 'groupUnderperformanceCount'::text)::integer, met.group_count,
                0)                                                                                        AS group_underperformance_count,
       CASE
           WHEN rs.completion_status::text = 'FULL'::text THEN 'COMPLETE'::text
           ELSE 'PARTIAL'::text
           END::character varying(20)                                                                     AS aggregation_status,
       met.policy_version
FROM report rpt
         JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
         LEFT JOIN LATERAL ( SELECT count(*)
                                    FILTER (WHERE x.section_code::text = 'EXCELLENT_TRAINEE'::text)::integer      AS excellent_count,
                                    count(*) FILTER (WHERE x.section_code::text = 'CLASS_RISK'::text)::integer    AS risky_class_count,
                                    count(*)
                                    FILTER (WHERE x.section_code::text = 'GROUP_UNDERPERFORMANCE'::text)::integer AS group_count,
                                    max(x.policy_version)                                                         AS policy_version
                             FROM report_metric x
                             WHERE x.snapshot_id = rs.snapshot_id) met ON true
WHERE rpt.report_type::text = 'COHORT_OUTCOME'::text;

alter table operator_cohort_outcome_summary_view
    owner to postgres;

grant delete, insert, select, update on operator_cohort_outcome_summary_view to teamiz_app;

create view operator_cohort_report_status_view
            (org_id, cohort_id, report_type, required_source_project_count, completed_source_project_count,
             required_source_round_count, completed_source_round_count, published_source_round_count,
             remaining_source_round_count, source_status, report_status, report_id, active_snapshot_id,
             scheduled_publish_at, published_at, latest_generation_run_id, latest_generation_status, can_export_pdf,
             pdf_unavailable_reason_code, aggregation_status, as_of_at)
as
WITH report_types(report_type) AS (VALUES ('COHORT_CURRICULUM_DIAGNOSIS'::text), ('COHORT_OUTCOME'::text))
SELECT c.org_id,
       c.cohort_id,
       rt.report_type::character varying(100)                                                   AS report_type,
       stats.required_project_count                                                             AS required_source_project_count,
       stats.completed_project_count                                                            AS completed_source_project_count,
       stats.required_round_count                                                               AS required_source_round_count,
       stats.completed_round_count                                                              AS completed_source_round_count,
       stats.published_round_count                                                              AS published_source_round_count,
       GREATEST(stats.required_round_count - stats.completed_round_count, 0)                    AS remaining_source_round_count,
       CASE
           WHEN stats.required_round_count = 0 THEN 'NO_SOURCE'::text
           WHEN stats.completed_round_count = stats.required_round_count THEN 'COMPLETE'::text
           ELSE 'IN_PROGRESS'::text
           END::character varying(100)                                                          AS source_status,
       COALESCE(rpt.lifecycle_status, 'NOT_CREATED'::character varying)::character varying(100) AS report_status,
       rpt.report_id,
       rs.snapshot_id                                                                           AS active_snapshot_id,
       rpt.scheduled_publish_at,
       rpt.published_at,
       gr.generation_run_id                                                                     AS latest_generation_run_id,
       gr.status                                                                                AS latest_generation_status,
       rpt.published_at IS NOT NULL AND rs.snapshot_id IS NOT NULL                              AS can_export_pdf,
       CASE
           WHEN rpt.report_id IS NULL THEN 'REPORT_NOT_CREATED'::text
           WHEN rpt.published_at IS NULL THEN 'REPORT_NOT_PUBLISHED'::text
           WHEN rs.snapshot_id IS NULL THEN 'SNAPSHOT_NOT_READY'::text
           ELSE NULL::text
           END::character varying(100)                                                          AS pdf_unavailable_reason_code,
       'COMPLETE'::character varying(20)                                                        AS aggregation_status,
       CURRENT_TIMESTAMP                                                                        AS as_of_at
FROM cohort c
         CROSS JOIN report_types rt
         LEFT JOIN LATERAL ( SELECT count(DISTINCT p.project_id)::integer                                                                      AS required_project_count,
                                    count(DISTINCT p.project_id)
                                    FILTER (WHERE p.lifecycle_status::text = 'CLOSED'::text)::integer                                          AS completed_project_count,
                                    count(DISTINCT r.assessment_round_id)::integer                                                             AS required_round_count,
                                    count(DISTINCT r.assessment_round_id)
                                    FILTER (WHERE r.status::text = 'COMPLETED'::text)::integer                                                 AS completed_round_count,
                                    count(DISTINCT r.assessment_round_id) FILTER (WHERE (EXISTS (SELECT 1
                                                                                                 FROM report rr
                                                                                                 WHERE rr.assessment_round_id = r.assessment_round_id
                                                                                                   AND rr.published_at IS NOT NULL)))::integer AS published_round_count
                             FROM project p
                                      LEFT JOIN project_assessment_round r
                                                ON r.project_id = p.project_id AND r.deleted_at IS NULL
                             WHERE p.cohort_id = c.cohort_id
                               AND p.deleted_at IS NULL) stats ON true
         LEFT JOIN LATERAL ( SELECT x.report_id,
                                    x.org_id,
                                    x.cohort_id,
                                    x.class_id,
                                    x.user_id,
                                    x.assessment_round_id,
                                    x.report_type,
                                    x.lifecycle_status,
                                    x.scheduled_publish_at,
                                    x.published_at,
                                    x.trainee_release_status,
                                    x.trainee_disclosure_scope,
                                    x.trainee_released_at,
                                    x.trainee_released_by
                             FROM report x
                             WHERE x.cohort_id = c.cohort_id
                               AND x.report_type::text = rt.report_type
                               AND x.class_id IS NULL
                               AND x.user_id IS NULL
                             ORDER BY (
                                          CASE
                                              WHEN x.lifecycle_status::text = 'ACTIVE'::text THEN 0
                                              ELSE 1
                                              END), x.published_at DESC NULLS LAST
                             LIMIT 1) rpt ON true
         LEFT JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
         LEFT JOIN LATERAL ( SELECT x.generation_run_id,
                                    x.report_id,
                                    x.trigger_type,
                                    x.idempotency_key,
                                    x.calculation_version,
                                    x.status,
                                    x.failure_reason,
                                    x.execution_no,
                                    x.started_at,
                                    x.completed_at,
                                    x.request_fingerprint
                             FROM report_generation_run x
                             WHERE x.report_id = rpt.report_id
                             ORDER BY x.execution_no DESC
                             LIMIT 1) gr ON true
WHERE c.deleted_at IS NULL;

alter table operator_cohort_report_status_view
    owner to postgres;

grant delete, insert, select, update on operator_cohort_report_status_view to teamiz_app;

create view operator_cost_overview_view
            (org_id, period_start_at, period_end_at, as_of_at, currency_code, organization_effective_cost,
             monthly_ai_budget, budget_utilization_rate, budget_remaining, budget_status,
             previous_period_effective_cost, cost_change_rate, comparison_status, aggregation_status, stale, cohort_id,
             cohort_name, cohort_effective_cost, trainee_count, cost_per_trainee, class_id, class_name, manager_names,
             class_trainee_count, started_session_count, class_effective_cost, allocated_class_cost, cohort_common_cost,
             class_unallocated_cost, class_attribution_status, class_unallocated_reason_code)
as
WITH current_policy AS (SELECT DISTINCT ON (op.org_id) op.policy_id,
                                                       op.org_id,
                                                       op.policy_version,
                                                       op.monthly_ai_budget,
                                                       op.currency_code,
                                                       op.monthly_token_limit,
                                                       op.storage_limit_bytes,
                                                       op.retention_days,
                                                       op.default_disclosure_scope,
                                                       op.code_session_tier_code,
                                                       op.allow_manager_invite,
                                                       op.allow_data_export,
                                                       op.allow_zip_submission,
                                                       op.allow_github_integration,
                                                       op.enable_big_project_contribution_analysis,
                                                       op.effective_from,
                                                       op.effective_to,
                                                       op.status,
                                                       op.created_by,
                                                       op.created_at,
                                                       op.updated_by,
                                                       op.updated_at
                        FROM organization_policy op
                        WHERE op.status::text = 'ACTIVE'::text
                          AND op.effective_from <= CURRENT_TIMESTAMP
                          AND (op.effective_to IS NULL OR op.effective_to > CURRENT_TIMESTAMP)
                        ORDER BY op.org_id, op.policy_version DESC, op.effective_from DESC),
     latest_usage AS (SELECT DISTINCT ON (ous.org_id) ous.usage_snapshot_id,
                                                      ous.org_id,
                                                      ous.period_type,
                                                      ous.period_start_at,
                                                      ous.period_end_at,
                                                      ous.as_of_at,
                                                      ous.active_trainee_count,
                                                      ous.completed_session_count,
                                                      ous.grading_execution_count,
                                                      ous.published_report_count,
                                                      ous.ai_call_count,
                                                      ous.input_token_count,
                                                      ous.output_token_count,
                                                      ous.cached_token_count,
                                                      ous.unpriced_call_count,
                                                      ous.unpriced_input_token_count,
                                                      ous.unpriced_output_token_count,
                                                      ous.cost_completeness_status,
                                                      ous.estimated_cost,
                                                      ous.actual_cost,
                                                      ous.effective_cost,
                                                      ous.currency_code,
                                                      ous.aggregation_status,
                                                      ous.failure_stage,
                                                      ous.failure_code,
                                                      ous.failure_reason,
                                                      ous.failed_at,
                                                      ous.is_retryable,
                                                      ous.source_watermark,
                                                      ous.calculation_version,
                                                      ous.created_at
                      FROM organization_usage_snapshot ous
                      ORDER BY ous.org_id, ous.period_end_at DESC, ous.as_of_at DESC, ous.created_at DESC),
     previous_usage AS (SELECT DISTINCT ON (cur.org_id) cur.org_id,
                                                        prev.effective_cost
                        FROM latest_usage cur
                                 JOIN organization_usage_snapshot prev
                                      ON prev.org_id = cur.org_id AND prev.period_end_at <= cur.period_start_at
                        ORDER BY cur.org_id, prev.period_end_at DESC, prev.as_of_at DESC),
     org_base AS (SELECT o.org_id,
                         COALESCE(lu.period_start_at, date_trunc('month'::text, CURRENT_TIMESTAMP))                   AS period_start_at,
                         COALESCE(lu.period_end_at, date_trunc('month'::text, CURRENT_TIMESTAMP) +
                                                    '1 mon'::interval)                                                AS period_end_at,
                         COALESCE(lu.as_of_at, CURRENT_TIMESTAMP)                                                     AS as_of_at,
                         COALESCE(lu.currency_code, cp.currency_code,
                                  'USD'::character varying)                                                           AS currency_code,
                         COALESCE(lu.effective_cost, 0::numeric)::numeric(18, 6)                                      AS organization_effective_cost,
                         cp.monthly_ai_budget,
                         pu.effective_cost                                                                            AS previous_period_effective_cost,
                         COALESCE(lu.aggregation_status, 'LIVE'::character varying)                                   AS aggregation_status,
                         lu.as_of_at IS NOT NULL AND lu.as_of_at <
                                                     (CURRENT_TIMESTAMP - '24:00:00'::interval)                       AS stale
                  FROM organization o
                           LEFT JOIN current_policy cp ON cp.org_id = o.org_id
                           LEFT JOIN latest_usage lu ON lu.org_id = o.org_id
                           LEFT JOIN previous_usage pu ON pu.org_id = o.org_id
                  WHERE o.deleted_at IS NULL),
     ai_current AS (SELECT au.org_id,
                           au.cohort_id,
                           au.class_id,
                           sum(COALESCE(au.actual_cost, au.estimated_cost, 0::numeric))::numeric(18, 6) AS effective_cost,
                           sum(
                                   CASE
                                       WHEN au.class_attribution_status::text = 'ALLOCATED'::text
                                           THEN COALESCE(au.actual_cost, au.estimated_cost, 0::numeric)
                                       ELSE 0::numeric
                                       END)::numeric(18, 6)                                             AS allocated_class_cost,
                           sum(
                                   CASE
                                       WHEN au.cohort_id IS NOT NULL AND au.class_id IS NULL
                                           THEN COALESCE(au.actual_cost, au.estimated_cost, 0::numeric)
                                       ELSE 0::numeric
                                       END)::numeric(18, 6)                                             AS cohort_common_cost,
                           sum(
                                   CASE
                                       WHEN au.class_attribution_status::text = 'UNALLOCATED'::text
                                           THEN COALESCE(au.actual_cost, au.estimated_cost, 0::numeric)
                                       ELSE 0::numeric
                                       END)::numeric(18, 6)                                             AS class_unallocated_cost,
                           max(au.class_attribution_status::text)                                       AS class_attribution_status,
                           max(au.class_unallocated_reason_code::text)                                  AS class_unallocated_reason_code
                    FROM ai_usage au
                    WHERE au.occurred_at >= date_trunc('month'::text, CURRENT_TIMESTAMP)
                      AND au.occurred_at < (date_trunc('month'::text, CURRENT_TIMESTAMP) + '1 mon'::interval)
                    GROUP BY au.org_id, au.cohort_id, au.class_id),
     cohort_cost AS (SELECT ai_current.org_id,
                            ai_current.cohort_id,
                            sum(ai_current.effective_cost)         AS effective_cost,
                            sum(ai_current.cohort_common_cost)     AS cohort_common_cost,
                            sum(ai_current.class_unallocated_cost) AS class_unallocated_cost
                     FROM ai_current
                     WHERE ai_current.cohort_id IS NOT NULL
                     GROUP BY ai_current.org_id, ai_current.cohort_id),
     class_cost AS (SELECT ai_current.org_id,
                           ai_current.cohort_id,
                           ai_current.class_id,
                           sum(ai_current.effective_cost)                AS effective_cost,
                           sum(ai_current.allocated_class_cost)          AS allocated_class_cost,
                           max(ai_current.class_attribution_status)      AS class_attribution_status,
                           max(ai_current.class_unallocated_reason_code) AS class_unallocated_reason_code
                    FROM ai_current
                    WHERE ai_current.class_id IS NOT NULL
                    GROUP BY ai_current.org_id, ai_current.cohort_id, ai_current.class_id),
     cohort_trainees AS (SELECT cohort_member.org_id,
                                cohort_member.cohort_id,
                                count(DISTINCT cohort_member.user_id)::integer AS trainee_count
                         FROM cohort_member
                         WHERE cohort_member.status::text = 'ACTIVE'::text
                           AND cohort_member.left_at IS NULL
                         GROUP BY cohort_member.org_id, cohort_member.cohort_id),
     class_trainees AS (SELECT cl.org_id,
                               cl.cohort_id,
                               cl.class_id,
                               count(DISTINCT cm.user_id)::integer AS trainee_count
                        FROM class cl
                                 LEFT JOIN class_membership csm
                                           ON csm.class_id = cl.class_id AND csm.unassigned_at IS NULL
                                 LEFT JOIN cohort_member cm ON cm.cohort_member_id = csm.cohort_member_id AND
                                                               cm.status::text = 'ACTIVE'::text AND cm.left_at IS NULL
                        WHERE cl.deleted_at IS NULL
                        GROUP BY cl.org_id, cl.cohort_id, cl.class_id),
     class_managers AS (SELECT ma.org_id,
                               ma.class_id,
                               array_agg(DISTINCT u.name ORDER BY u.name)
                               FILTER (WHERE u.name IS NOT NULL) AS manager_names
                        FROM manager_assignment ma
                                 JOIN app_user u ON u.user_id = ma.manager_user_id
                        WHERE ma.status::text = 'ACTIVE'::text
                          AND ma.unassigned_at IS NULL
                          AND u.deleted_at IS NULL
                        GROUP BY ma.org_id, ma.class_id),
     class_sessions AS (SELECT pm.class_id,
                               count(DISTINCT s.session_id)::integer AS started_session_count
                        FROM project_membership pm
                                 JOIN measurement_attempt ma
                                      ON ma.project_id = pm.project_id AND ma.user_id = pm.user_id
                                 JOIN assessment_session s ON s.attempt_id = ma.attempt_id AND s.started_at IS NOT NULL
                        WHERE pm.status::text = 'ACTIVE'::text
                        GROUP BY pm.class_id),
     org_rows AS (SELECT ob.org_id,
                         ob.period_start_at,
                         ob.period_end_at,
                         ob.as_of_at,
                         ob.currency_code,
                         ob.organization_effective_cost,
                         ob.monthly_ai_budget,
                         ob.previous_period_effective_cost,
                         ob.aggregation_status,
                         ob.stale,
                         NULL::uuid                   AS cohort_id,
                         NULL::character varying(200) AS cohort_name,
                         NULL::numeric                AS cohort_effective_cost,
                         NULL::integer                AS trainee_count,
                         NULL::numeric                AS cost_per_trainee,
                         NULL::uuid                   AS class_id,
                         NULL::character varying(200) AS class_name,
                         ARRAY []::text[]             AS manager_names,
                         NULL::integer                AS class_trainee_count,
                         NULL::integer                AS started_session_count,
                         NULL::numeric                AS class_effective_cost,
                         NULL::numeric                AS allocated_class_cost,
                         NULL::numeric                AS cohort_common_cost,
                         NULL::numeric                AS class_unallocated_cost,
                         NULL::character varying      AS class_attribution_status,
                         NULL::character varying      AS class_unallocated_reason_code
                  FROM org_base ob),
     cohort_rows AS (SELECT ob.org_id,
                            ob.period_start_at,
                            ob.period_end_at,
                            ob.as_of_at,
                            ob.currency_code,
                            ob.organization_effective_cost,
                            ob.monthly_ai_budget,
                            ob.previous_period_effective_cost,
                            ob.aggregation_status,
                            ob.stale,
                            c.cohort_id,
                            c.name                                          AS cohort_name,
                            COALESCE(cc.effective_cost, 0::numeric)         AS cohort_effective_cost,
                            COALESCE(ct.trainee_count, 0)                   AS trainee_count,
                            CASE
                                WHEN COALESCE(ct.trainee_count, 0) = 0 THEN NULL::numeric
                                ELSE COALESCE(cc.effective_cost, 0::numeric) / ct.trainee_count::numeric
                                END                                         AS cost_per_trainee,
                            NULL::uuid                                      AS class_id,
                            NULL::character varying(200)                    AS class_name,
                            ARRAY []::text[]                                AS manager_names,
                            NULL::integer                                   AS class_trainee_count,
                            NULL::integer                                   AS started_session_count,
                            NULL::numeric                                   AS class_effective_cost,
                            NULL::numeric                                   AS allocated_class_cost,
                            COALESCE(cc.cohort_common_cost, 0::numeric)     AS cohort_common_cost,
                            COALESCE(cc.class_unallocated_cost, 0::numeric) AS class_unallocated_cost,
                            NULL::character varying                         AS class_attribution_status,
                            NULL::character varying                         AS class_unallocated_reason_code
                     FROM org_base ob
                              JOIN cohort c ON c.org_id = ob.org_id AND c.deleted_at IS NULL
                              LEFT JOIN cohort_cost cc ON cc.org_id = c.org_id AND cc.cohort_id = c.cohort_id
                              LEFT JOIN cohort_trainees ct ON ct.org_id = c.org_id AND ct.cohort_id = c.cohort_id),
     class_rows AS (SELECT ob.org_id,
                           ob.period_start_at,
                           ob.period_end_at,
                           ob.as_of_at,
                           ob.currency_code,
                           ob.organization_effective_cost,
                           ob.monthly_ai_budget,
                           ob.previous_period_effective_cost,
                           ob.aggregation_status,
                           ob.stale,
                           c.cohort_id,
                           c.name                                                            AS cohort_name,
                           COALESCE(cc.effective_cost, 0::numeric)                           AS cohort_effective_cost,
                           COALESCE(ct.trainee_count, 0)                                     AS trainee_count,
                           CASE
                               WHEN COALESCE(ct.trainee_count, 0) = 0 THEN NULL::numeric
                               ELSE COALESCE(cc.effective_cost, 0::numeric) / ct.trainee_count::numeric
                               END                                                           AS cost_per_trainee,
                           cl.class_id,
                           cl.name                                                           AS class_name,
                           COALESCE(cm.manager_names, ARRAY []::text[]::character varying[]) AS manager_names,
                           COALESCE(clt.trainee_count, 0)                                    AS class_trainee_count,
                           COALESCE(cs.started_session_count, 0)                             AS started_session_count,
                           COALESCE(clc.effective_cost, 0::numeric)                          AS class_effective_cost,
                           COALESCE(clc.allocated_class_cost, 0::numeric)                    AS allocated_class_cost,
                           COALESCE(cc.cohort_common_cost, 0::numeric)                       AS cohort_common_cost,
                           COALESCE(cc.class_unallocated_cost, 0::numeric)                   AS class_unallocated_cost,
                           COALESCE(clc.class_attribution_status, 'ALLOCATED'::text)         AS class_attribution_status,
                           clc.class_unallocated_reason_code
                    FROM org_base ob
                             JOIN cohort c ON c.org_id = ob.org_id AND c.deleted_at IS NULL
                             JOIN class cl ON cl.cohort_id = c.cohort_id AND cl.deleted_at IS NULL
                             LEFT JOIN cohort_cost cc ON cc.org_id = c.org_id AND cc.cohort_id = c.cohort_id
                             LEFT JOIN class_cost clc ON clc.org_id = cl.org_id AND clc.class_id = cl.class_id
                             LEFT JOIN cohort_trainees ct ON ct.org_id = c.org_id AND ct.cohort_id = c.cohort_id
                             LEFT JOIN class_trainees clt ON clt.org_id = cl.org_id AND clt.class_id = cl.class_id
                             LEFT JOIN class_managers cm ON cm.org_id = cl.org_id AND cm.class_id = cl.class_id
                             LEFT JOIN class_sessions cs ON cs.class_id = cl.class_id),
     all_rows AS (SELECT org_rows.org_id,
                         org_rows.period_start_at,
                         org_rows.period_end_at,
                         org_rows.as_of_at,
                         org_rows.currency_code,
                         org_rows.organization_effective_cost,
                         org_rows.monthly_ai_budget,
                         org_rows.previous_period_effective_cost,
                         org_rows.aggregation_status,
                         org_rows.stale,
                         org_rows.cohort_id,
                         org_rows.cohort_name,
                         org_rows.cohort_effective_cost,
                         org_rows.trainee_count,
                         org_rows.cost_per_trainee,
                         org_rows.class_id,
                         org_rows.class_name,
                         org_rows.manager_names,
                         org_rows.class_trainee_count,
                         org_rows.started_session_count,
                         org_rows.class_effective_cost,
                         org_rows.allocated_class_cost,
                         org_rows.cohort_common_cost,
                         org_rows.class_unallocated_cost,
                         org_rows.class_attribution_status,
                         org_rows.class_unallocated_reason_code
                  FROM org_rows
                  UNION ALL
                  SELECT cohort_rows.org_id,
                         cohort_rows.period_start_at,
                         cohort_rows.period_end_at,
                         cohort_rows.as_of_at,
                         cohort_rows.currency_code,
                         cohort_rows.organization_effective_cost,
                         cohort_rows.monthly_ai_budget,
                         cohort_rows.previous_period_effective_cost,
                         cohort_rows.aggregation_status,
                         cohort_rows.stale,
                         cohort_rows.cohort_id,
                         cohort_rows.cohort_name,
                         cohort_rows.cohort_effective_cost,
                         cohort_rows.trainee_count,
                         cohort_rows.cost_per_trainee,
                         cohort_rows.class_id,
                         cohort_rows.class_name,
                         cohort_rows.manager_names,
                         cohort_rows.class_trainee_count,
                         cohort_rows.started_session_count,
                         cohort_rows.class_effective_cost,
                         cohort_rows.allocated_class_cost,
                         cohort_rows.cohort_common_cost,
                         cohort_rows.class_unallocated_cost,
                         cohort_rows.class_attribution_status,
                         cohort_rows.class_unallocated_reason_code
                  FROM cohort_rows
                  UNION ALL
                  SELECT class_rows.org_id,
                         class_rows.period_start_at,
                         class_rows.period_end_at,
                         class_rows.as_of_at,
                         class_rows.currency_code,
                         class_rows.organization_effective_cost,
                         class_rows.monthly_ai_budget,
                         class_rows.previous_period_effective_cost,
                         class_rows.aggregation_status,
                         class_rows.stale,
                         class_rows.cohort_id,
                         class_rows.cohort_name,
                         class_rows.cohort_effective_cost,
                         class_rows.trainee_count,
                         class_rows.cost_per_trainee,
                         class_rows.class_id,
                         class_rows.class_name,
                         class_rows.manager_names,
                         class_rows.class_trainee_count,
                         class_rows.started_session_count,
                         class_rows.class_effective_cost,
                         class_rows.allocated_class_cost,
                         class_rows.cohort_common_cost,
                         class_rows.class_unallocated_cost,
                         class_rows.class_attribution_status,
                         class_rows.class_unallocated_reason_code
                  FROM class_rows)
SELECT org_id,
       period_start_at,
       period_end_at,
       as_of_at,
       currency_code::character varying(3)                   AS currency_code,
       organization_effective_cost,
       monthly_ai_budget,
       CASE
           WHEN monthly_ai_budget IS NULL OR monthly_ai_budget = 0::numeric THEN NULL::numeric
           ELSE organization_effective_cost / monthly_ai_budget
           END::numeric(9, 4)                                AS budget_utilization_rate,
       CASE
           WHEN monthly_ai_budget IS NULL THEN NULL::numeric
           ELSE monthly_ai_budget - organization_effective_cost
           END::numeric(18, 6)                               AS budget_remaining,
       CASE
           WHEN monthly_ai_budget IS NULL THEN 'NOT_CONFIGURED'::text
           WHEN organization_effective_cost > monthly_ai_budget THEN 'EXCEEDED'::text
           WHEN organization_effective_cost >= (monthly_ai_budget * 0.8) THEN 'WARNING'::text
           ELSE 'NORMAL'::text
           END::character varying(30)                        AS budget_status,
       previous_period_effective_cost,
       CASE
           WHEN previous_period_effective_cost IS NULL OR previous_period_effective_cost = 0::numeric THEN NULL::numeric
           ELSE (organization_effective_cost - previous_period_effective_cost) / previous_period_effective_cost
           END::numeric(9, 4)                                AS cost_change_rate,
       CASE
           WHEN previous_period_effective_cost IS NULL THEN 'NO_BASELINE'::text
           WHEN previous_period_effective_cost = 0::numeric THEN 'ZERO_BASELINE'::text
           ELSE 'COMPARABLE'::text
           END::character varying(30)                        AS comparison_status,
       aggregation_status::character varying(20)             AS aggregation_status,
       stale,
       cohort_id,
       cohort_name,
       cohort_effective_cost::numeric(18, 6)                 AS cohort_effective_cost,
       trainee_count,
       cost_per_trainee::numeric(18, 6)                      AS cost_per_trainee,
       class_id,
       class_name,
       manager_names,
       class_trainee_count,
       started_session_count,
       class_effective_cost::numeric(18, 6)                  AS class_effective_cost,
       allocated_class_cost::numeric(18, 6)                  AS allocated_class_cost,
       cohort_common_cost::numeric(18, 6)                    AS cohort_common_cost,
       class_unallocated_cost::numeric(18, 6)                AS class_unallocated_cost,
       class_attribution_status::character varying(30)       AS class_attribution_status,
       class_unallocated_reason_code::character varying(100) AS class_unallocated_reason_code
FROM all_rows;

alter table operator_cost_overview_view
    owner to postgres;

grant delete, insert, select, update on operator_cost_overview_view to teamiz_app;

create view operator_curriculum_concept_distribution_view
            (snapshot_id, curriculum_version_id, teaches_id, reached_level, concept_name_snapshot, participant_count,
             denominator, low_level_count, aggregation_status, aggregation_policy_version, policy_parameter_snapshot)
as
SELECT rm.snapshot_id,
       rm.curriculum_version_id,
       rm.teaches_id,
       rm.reached_level,
       COALESCE(rm.dimension_snapshot ->> 'conceptName'::text,
                t.canonical_name::text)::character varying(200)     AS concept_name_snapshot,
       COALESCE(rm.numerator, rm.metric_value, 0::numeric)::integer AS participant_count,
       rm.denominator,
       CASE
           WHEN rm.reached_level = ANY (ARRAY [0, 1, 2]) THEN COALESCE(rm.numerator, rm.metric_value, 0::numeric)
           ELSE 0::numeric
           END::integer                                             AS low_level_count,
       rm.aggregation_status,
       rm.aggregation_policy_version,
       rm.policy_parameter_snapshot
FROM report_metric rm
         LEFT JOIN teaches t ON t.teaches_id = rm.teaches_id
WHERE rm.section_code::text = 'CONCEPT_REACH_DISTRIBUTION'::text
  AND rm.metric_grain_code::text = 'CONCEPT_REACHED_LEVEL'::text;

alter table operator_curriculum_concept_distribution_view
    owner to postgres;

grant delete, insert, select, update on operator_curriculum_concept_distribution_view to teamiz_app;

create view operator_curriculum_diagnosis_round_view
            (snapshot_id, source_assessment_round_id, project_verification_concept_id, project_id, round_id, round_name,
             concept_id, concept_name_snapshot, reached_level, participant_count, denominator, missing_count,
             source_snapshot_version)
as
SELECT rm.snapshot_id,
       rm.source_assessment_round_id,
       rm.project_verification_concept_id,
       rm.project_id,
       rm.source_assessment_round_id                                                                           AS round_id,
       r.round_name,
       rm.project_verification_concept_id                                                                      AS concept_id,
       COALESCE(rm.dimension_snapshot ->> 'conceptName'::text,
                t.canonical_name::text)::character varying(200)                                                AS concept_name_snapshot,
       rm.reached_level,
       COALESCE(rm.numerator, rm.metric_value, 0::numeric)::integer                                            AS participant_count,
       rm.denominator,
       rm.missing_count,
       rm.source_snapshot_version
FROM report_metric rm
         LEFT JOIN project_assessment_round r ON r.assessment_round_id = rm.source_assessment_round_id
         LEFT JOIN project_verification_concept pvc ON pvc.project_concept_id = rm.project_verification_concept_id
         LEFT JOIN teaches t ON t.teaches_id = pvc.teaches_id
WHERE rm.section_code::text = 'ROUND_CONCEPT_RESULT'::text
  AND (rm.metric_grain_code::text = ANY
       (ARRAY ['ROUND_CONCEPT'::character varying::text, 'ROUND_CONCEPT_REACHED_LEVEL'::character varying::text]));

alter table operator_curriculum_diagnosis_round_view
    owner to postgres;

grant delete, insert, select, update on operator_curriculum_diagnosis_round_view to teamiz_app;

create view operator_curriculum_diagnosis_summary_view
            (snapshot_id, curriculum_version_id, org_id, cohort_id, report_id, material_id, version_id,
             curriculum_name_snapshot, eligible_count, assessed_count, missing_count, average_reached_level,
             aggregation_status, display_order)
as
SELECT rs.snapshot_id,
       rm.curriculum_version_id,
       rpt.org_id,
       rpt.cohort_id,
       rpt.report_id,
       cv.material_id,
       cv.version_id,
       COALESCE(rm.dimension_snapshot ->> 'curriculumName'::text,
                cm.title::text)::character varying(200)                                                      AS curriculum_name_snapshot,
       COALESCE((rs.summary_payload ->> 'eligibleCount'::text)::integer, rs.sample_count +
                                                                         rs.missing_count)                   AS eligible_count,
       COALESCE((rs.summary_payload ->> 'assessedCount'::text)::integer,
                rs.sample_count)                                                                             AS assessed_count,
       rs.missing_count,
       avg(rm.metric_value)
       OVER (PARTITION BY rs.snapshot_id, rm.curriculum_version_id)::numeric(10, 4)                          AS average_reached_level,
       CASE
           WHEN rs.completion_status::text = 'FULL'::text THEN 'COMPLETE'::text
           ELSE 'PARTIAL'::text
           END::character varying(20)                                                                        AS aggregation_status,
       min(rm.display_order)
       OVER (PARTITION BY rs.snapshot_id, rm.curriculum_version_id)                                          AS display_order
FROM report rpt
         JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
         JOIN report_metric rm
              ON rm.snapshot_id = rs.snapshot_id AND rm.section_code::text = 'CURRICULUM_DIAGNOSIS_SUMMARY'::text
         LEFT JOIN curriculum_version cv ON cv.version_id = rm.curriculum_version_id
         LEFT JOIN curriculum_material cm ON cm.material_id = cv.material_id
WHERE rpt.report_type::text = 'COHORT_CURRICULUM_DIAGNOSIS'::text;

alter table operator_curriculum_diagnosis_summary_view
    owner to postgres;

grant delete, insert, select, update on operator_curriculum_diagnosis_summary_view to teamiz_app;

create view operator_curriculum_list_view
            (org_id, material_id, title, normalized_title, topic, material_type, registered_at, deleted_at,
             latest_version_id, latest_version_no, latest_version_status, original_file_name, file_size_bytes,
             page_count, latest_analysis_attempt_id, latest_analysis_attempt_status, latest_analysis_requested_at,
             latest_failure_code, latest_failure_stage, latest_recovery_action, effective_analysis_id,
             effective_analysis_version, effective_analysis_completed_at, fallback_used, display_analysis_status,
             analysis_quality_status, section_count, teaching_item_count, distinct_teaches_count, linked_project_count,
             linked_project_names, linked_projects, aggregation_status, as_of_at)
as
SELECT m.org_id,
       m.material_id,
       m.title,
       m.normalized_title,
       m.topic,
       m.material_type,
       m.created_at                                                        AS registered_at,
       m.deleted_at,
       v.version_id                                                        AS latest_version_id,
       v.version_no                                                        AS latest_version_no,
       v.status::character varying(100)                                    AS latest_version_status,
       v.original_file_name,
       v.file_size_bytes,
       v.page_count,
       la.analysis_id                                                      AS latest_analysis_attempt_id,
       la.status::character varying(100)                                   AS latest_analysis_attempt_status,
       la.requested_at                                                     AS latest_analysis_requested_at,
       la.failure_code                                                     AS latest_failure_code,
       la.failure_stage::text                                              AS latest_failure_stage,
       la.recovery_action::text                                            AS latest_recovery_action,
       ea.analysis_id                                                      AS effective_analysis_id,
       ea.analysis_version                                                 AS effective_analysis_version,
       ea.completed_at                                                     AS effective_analysis_completed_at,
       ea.fallback_used,
       CASE
           WHEN la.analysis_id IS NULL THEN 'NOT_ANALYZED'::character varying
           WHEN la.status::text = 'FAILED'::text AND ea.analysis_id IS NOT NULL
               THEN 'FAILED_USING_PREVIOUS'::character varying
           ELSE la.status
           END::character varying(50)                                      AS display_analysis_status,
       CASE
           WHEN ea.analysis_id IS NULL THEN 'UNAVAILABLE'::text
           WHEN ea.fallback_used THEN 'FALLBACK'::text
           ELSE 'NORMAL'::text
           END::character varying(100)                                     AS analysis_quality_status,
       COALESCE(stats.section_count, 0)                                    AS section_count,
       COALESCE(stats.teaching_item_count, 0)                              AS teaching_item_count,
       COALESCE(stats.distinct_teaches_count, 0)                           AS distinct_teaches_count,
       COALESCE(proj.linked_project_count, 0)                              AS linked_project_count,
       COALESCE(proj.names, ARRAY []::text[]::character varying[])::text[] AS linked_project_names,
       COALESCE(proj.items, '[]'::jsonb)                                   AS linked_projects,
       'COMPLETE'::character varying(20)                                   AS aggregation_status,
       CURRENT_TIMESTAMP                                                   AS as_of_at
FROM curriculum_material m
         LEFT JOIN LATERAL ( SELECT x.version_id,
                                    x.material_id,
                                    x.version_no,
                                    x.original_file_name,
                                    x.file_uri,
                                    x.mime_type,
                                    x.file_size_bytes,
                                    x.content_hash,
                                    x.page_count,
                                    x.status,
                                    x.created_by,
                                    x.created_at
                             FROM curriculum_version x
                             WHERE x.material_id = m.material_id
                             ORDER BY x.version_no DESC, x.created_at DESC
                             LIMIT 1) v ON true
         LEFT JOIN LATERAL ( SELECT x.analysis_id,
                                    x.version_id,
                                    x.model_id,
                                    x.retry_of_analysis_id,
                                    x.analysis_version,
                                    x.status,
                                    x.fallback_used,
                                    x.idempotency_key,
                                    x.request_fingerprint,
                                    x.request_reason,
                                    x.impact_acknowledged,
                                    x.requested_by,
                                    x.requested_at,
                                    x.started_at,
                                    x.completed_at,
                                    x.failed_at,
                                    x.failure_code,
                                    x.failure_stage,
                                    x.failure_reason,
                                    x.is_retryable,
                                    x.recovery_action,
                                    x.created_at,
                                    x.external_job_id
                             FROM curriculum_analysis x
                             WHERE x.version_id = v.version_id
                             ORDER BY x.requested_at DESC, x.created_at DESC
                             LIMIT 1) la ON true
         LEFT JOIN LATERAL ( SELECT x.analysis_id,
                                    x.version_id,
                                    x.model_id,
                                    x.retry_of_analysis_id,
                                    x.analysis_version,
                                    x.status,
                                    x.fallback_used,
                                    x.idempotency_key,
                                    x.request_fingerprint,
                                    x.request_reason,
                                    x.impact_acknowledged,
                                    x.requested_by,
                                    x.requested_at,
                                    x.started_at,
                                    x.completed_at,
                                    x.failed_at,
                                    x.failure_code,
                                    x.failure_stage,
                                    x.failure_reason,
                                    x.is_retryable,
                                    x.recovery_action,
                                    x.created_at,
                                    x.external_job_id
                             FROM curriculum_analysis x
                             WHERE x.version_id = v.version_id
                               AND x.status::text = 'SUCCEEDED'::text
                             ORDER BY x.completed_at DESC, x.analysis_version DESC
                             LIMIT 1) ea ON true
         LEFT JOIN LATERAL ( SELECT count(DISTINCT s.section_id)::integer   AS section_count,
                                    count(map.mapping_id)::integer          AS teaching_item_count,
                                    count(DISTINCT map.teaches_id)::integer AS distinct_teaches_count
                             FROM curriculum_section s
                                      LEFT JOIN curriculum_teaches_mapping map
                                                ON map.source_analysis_id = s.source_analysis_id AND
                                                   map.version_id = s.version_id
                             WHERE s.version_id = v.version_id
                               AND s.source_analysis_id = ea.analysis_id) stats ON true
         LEFT JOIN LATERAL ( SELECT count(DISTINCT p.project_id)::integer             AS linked_project_count,
                                    array_agg(DISTINCT p.name ORDER BY p.name)        AS names,
                                    jsonb_agg(DISTINCT
                                              jsonb_build_object('projectId', p.project_id, 'projectName', p.name,
                                                                 'cohortId', p.cohort_id, 'status',
                                                                 p.lifecycle_status)) AS items
                             FROM project_curriculum pc
                                      JOIN project p ON p.project_id = pc.project_id
                             WHERE pc.curriculum_version_id = v.version_id
                               AND p.deleted_at IS NULL) proj ON true;

alter table operator_curriculum_list_view
    owner to postgres;

grant delete, insert, select, update on operator_curriculum_list_view to teamiz_app;

create view operator_manager_list_view
            (org_id, row_type, name, email, display_status, source_status_at, sort_at, user_id, status,
             is_email_verified, last_login_at, inactivated_at, inactivated_reason_code, invitation_id, target_cohort_id,
             target_class_id, invited_at, sent_at, accepted_at, expired_at, failure_stage, failure_code,
             current_token_id, purpose, expires_at, used_at, invalidated_reason, active_assignment_count,
             assigned_cohort_ids, assigned_class_ids, assigned_cohort_names, assigned_class_names,
             assigned_trainee_count, is_unassigned, can_suspend, can_reactivate, can_resend, can_cancel,
             action_block_code, account_count, pending_invitation_count, aggregation_status, as_of_at)
as
WITH account_stats AS (SELECT u.org_id,
                              count(*)::integer               AS account_count,
                              (SELECT count(*)::integer AS count
                               FROM user_invitation ui
                               WHERE ui.org_id = u.org_id
                                 AND ui.target_role_code::text = 'MANAGER'::text
                                 AND (ui.status::text = ANY
                                      (ARRAY ['PENDING'::character varying::text, 'SENT'::character varying::text, 'FAILED'::character varying::text]))
                                 AND ui.accepted_at IS NULL
                                 AND ui.cancelled_at IS NULL) AS pending_invitation_count
                       FROM app_user u
                       WHERE u.role_code::text = 'MANAGER'::text
                         AND u.deleted_at IS NULL
                       GROUP BY u.org_id),
     assignment_summary AS (SELECT ma.manager_user_id,
                                   count(*)
                                   FILTER (WHERE ma.status::text = 'ACTIVE'::text AND ma.unassigned_at IS NULL)::integer AS active_assignment_count,
                                   array_agg(DISTINCT c.cohort_id)
                                   FILTER (WHERE ma.status::text = 'ACTIVE'::text AND ma.unassigned_at IS NULL)          AS assigned_cohort_ids,
                                   array_agg(DISTINCT ma.class_id)
                                   FILTER (WHERE ma.status::text = 'ACTIVE'::text AND ma.unassigned_at IS NULL)          AS assigned_class_ids,
                                   array_agg(DISTINCT co.name ORDER BY co.name)
                                   FILTER (WHERE ma.status::text = 'ACTIVE'::text AND ma.unassigned_at IS NULL)          AS assigned_cohort_names,
                                   array_agg(DISTINCT c.name ORDER BY c.name)
                                   FILTER (WHERE ma.status::text = 'ACTIVE'::text AND ma.unassigned_at IS NULL)          AS assigned_class_names,
                                   count(DISTINCT cm.user_id)
                                   FILTER (WHERE ma.status::text = 'ACTIVE'::text AND ma.unassigned_at IS NULL AND
                                                 cm.status::text = 'ACTIVE'::text AND
                                                 cm.left_at IS NULL)::integer                                            AS assigned_trainee_count
                            FROM manager_assignment ma
                                     JOIN class c ON c.class_id = ma.class_id
                                     JOIN cohort co ON co.cohort_id = c.cohort_id
                                     LEFT JOIN class_membership csm
                                               ON csm.class_id = c.class_id AND csm.unassigned_at IS NULL
                                     LEFT JOIN cohort_member cm ON cm.cohort_member_id = csm.cohort_member_id
                            GROUP BY ma.manager_user_id),
     accounts AS (SELECT u.org_id,
                         'ACCOUNT'::character varying(20)                                         AS row_type,
                         u.name::text                                                             AS name,
                         u.email::text                                                            AS email,
                         u.status::text                                                           AS display_status,
                         COALESCE(u.updated_at, u.created_at)                                     AS source_status_at,
                         COALESCE(u.last_login_at, u.created_at)                                  AS sort_at,
                         u.user_id,
                         u.status::text                                                           AS status,
                         u.is_email_verified,
                         u.last_login_at,
                         u.inactivated_at,
                         u.inactivated_reason_code::text                                          AS inactivated_reason_code,
                         NULL::uuid                                                               AS invitation_id,
                         NULL::uuid                                                               AS target_cohort_id,
                         NULL::uuid                                                               AS target_class_id,
                         NULL::timestamp with time zone                                           AS invited_at,
                         NULL::timestamp with time zone                                           AS sent_at,
                         NULL::timestamp with time zone                                           AS accepted_at,
                         NULL::timestamp with time zone                                           AS expired_at,
                         NULL::text                                                               AS failure_stage,
                         NULL::text                                                               AS failure_code,
                         NULL::uuid                                                               AS current_token_id,
                         NULL::text                                                               AS purpose,
                         NULL::timestamp with time zone                                           AS expires_at,
                         NULL::timestamp with time zone                                           AS used_at,
                         NULL::text                                                               AS invalidated_reason,
                         COALESCE(a.active_assignment_count, 0)                                   AS active_assignment_count,
                         COALESCE(a.assigned_cohort_ids, ARRAY []::uuid[])                        AS assigned_cohort_ids,
                         COALESCE(a.assigned_class_ids, ARRAY []::uuid[])                         AS assigned_class_ids,
                         COALESCE(a.assigned_cohort_names,
                                  ARRAY []::text[]::character varying[])                          AS assigned_cohort_names,
                         COALESCE(a.assigned_class_names,
                                  ARRAY []::text[]::character varying[])                          AS assigned_class_names,
                         COALESCE(a.assigned_trainee_count, 0)                                    AS assigned_trainee_count,
                         COALESCE(a.active_assignment_count, 0) = 0                               AS is_unassigned,
                         u.status::text = 'ACTIVE'::text                                          AS can_suspend,
                         u.status::text = 'INACTIVE'::text                                        AS can_reactivate,
                         false                                                                    AS can_resend,
                         false                                                                    AS can_cancel,
                         NULL::text                                                               AS action_block_code,
                         COALESCE(s.account_count, 0)                                             AS account_count,
                         COALESCE(s.pending_invitation_count, 0)                                  AS pending_invitation_count,
                         'COMPLETE'::text                                                         AS aggregation_status,
                         CURRENT_TIMESTAMP                                                        AS as_of_at
                  FROM app_user u
                           LEFT JOIN assignment_summary a ON a.manager_user_id = u.user_id
                           LEFT JOIN account_stats s ON s.org_id = u.org_id
                  WHERE u.role_code::text = 'MANAGER'::text
                    AND u.deleted_at IS NULL),
     invites AS (SELECT ui.org_id,
                        'INVITATION'::character varying(20)                AS "varchar",
                        NULL::text                                         AS text,
                        ui.target_email::text                              AS target_email,
                        ui.status::text                                    AS status,
                        COALESCE(ui.updated_at, ui.invited_at)             AS "coalesce",
                        COALESCE(ui.sent_at, ui.invited_at)                AS "coalesce",
                        NULL::uuid                                         AS uuid,
                        NULL::text                                         AS text,
                        NULL::boolean                                      AS bool,
                        NULL::timestamp with time zone                     AS timestamptz,
                        NULL::timestamp with time zone                     AS timestamptz,
                        NULL::text                                         AS text,
                        ui.invitation_id,
                        ui.target_cohort_id,
                        ui.target_class_id,
                        ui.invited_at,
                        ui.sent_at,
                        ui.accepted_at,
                        ui.expired_at,
                        ui.failure_stage::text                             AS failure_stage,
                        ui.failure_code::text                              AS failure_code,
                        t.token_id,
                        t.purpose::text                                    AS purpose,
                        t.expires_at,
                        t.used_at,
                        t.invalidated_reason,
                        0                                                  AS "?column?",
                        ARRAY []::uuid[]                                   AS "array",
                        ARRAY []::uuid[]                                   AS "array",
                        ARRAY []::text[]                                   AS "array",
                        ARRAY []::text[]                                   AS "array",
                        0                                                  AS "?column?",
                        true                                               AS "?column?",
                        false                                              AS "?column?",
                        false                                              AS "?column?",
                        (ui.status::text = ANY
                         (ARRAY ['PENDING'::character varying::text, 'SENT'::character varying::text, 'FAILED'::character varying::text])) AND
                        ui.accepted_at IS NULL AND ui.cancelled_at IS NULL AS "?column?",
                        ui.accepted_at IS NULL AND ui.cancelled_at IS NULL AS "?column?",
                        CASE
                            WHEN ui.accepted_at IS NOT NULL THEN 'ALREADY_ACCEPTED'::text
                            WHEN ui.cancelled_at IS NOT NULL THEN 'CANCELLED'::text
                            ELSE NULL::text
                            END                                            AS "case",
                        COALESCE(s.account_count, 0)                       AS "coalesce",
                        COALESCE(s.pending_invitation_count, 0)            AS "coalesce",
                        'COMPLETE'::text                                   AS "?column?",
                        CURRENT_TIMESTAMP                                  AS "current_timestamp"
                 FROM user_invitation ui
                          LEFT JOIN LATERAL ( SELECT x.token_id,
                                                     x.org_id,
                                                     x.user_id,
                                                     x.invitation_id,
                                                     x.target_email,
                                                     x.target_email_normalized,
                                                     x.purpose,
                                                     x.token_hash,
                                                     x.payload,
                                                     x.issued_at,
                                                     x.expires_at,
                                                     x.used_at,
                                                     x.invalidated_at,
                                                     x.invalidated_reason,
                                                     x.replaced_by_token_id,
                                                     x.issued_by,
                                                     x.issued_request_id,
                                                     x.used_request_id,
                                                     x.created_at
                                              FROM one_time_token x
                                              WHERE x.token_id = ui.current_token_id
                                                 OR x.invitation_id = ui.invitation_id AND x.replaced_by_token_id IS NULL
                                              ORDER BY (x.token_id = ui.current_token_id) DESC, x.issued_at DESC
                                              LIMIT 1) t ON true
                          LEFT JOIN account_stats s ON s.org_id = ui.org_id
                 WHERE ui.target_role_code::text = 'MANAGER'::text
                   AND ui.accepted_at IS NULL),
     z AS (SELECT accounts.org_id,
                  accounts.row_type,
                  accounts.name,
                  accounts.email,
                  accounts.display_status,
                  accounts.source_status_at,
                  accounts.sort_at,
                  accounts.user_id,
                  accounts.status,
                  accounts.is_email_verified,
                  accounts.last_login_at,
                  accounts.inactivated_at,
                  accounts.inactivated_reason_code,
                  accounts.invitation_id,
                  accounts.target_cohort_id,
                  accounts.target_class_id,
                  accounts.invited_at,
                  accounts.sent_at,
                  accounts.accepted_at,
                  accounts.expired_at,
                  accounts.failure_stage,
                  accounts.failure_code,
                  accounts.current_token_id,
                  accounts.purpose,
                  accounts.expires_at,
                  accounts.used_at,
                  accounts.invalidated_reason,
                  accounts.active_assignment_count,
                  accounts.assigned_cohort_ids,
                  accounts.assigned_class_ids,
                  accounts.assigned_cohort_names,
                  accounts.assigned_class_names,
                  accounts.assigned_trainee_count,
                  accounts.is_unassigned,
                  accounts.can_suspend,
                  accounts.can_reactivate,
                  accounts.can_resend,
                  accounts.can_cancel,
                  accounts.action_block_code,
                  accounts.account_count,
                  accounts.pending_invitation_count,
                  accounts.aggregation_status,
                  accounts.as_of_at
           FROM accounts
           UNION ALL
           SELECT invites.org_id,
                  invites."varchar",
                  invites.text,
                  invites.target_email,
                  invites.status,
                  invites."coalesce",
                  invites.coalesce_1    AS "coalesce",
                  invites.uuid,
                  invites.text_1        AS text,
                  invites.bool,
                  invites.timestamptz,
                  invites.timestamptz_1 AS timestamptz,
                  invites.text_2        AS text,
                  invites.invitation_id,
                  invites.target_cohort_id,
                  invites.target_class_id,
                  invites.invited_at,
                  invites.sent_at,
                  invites.accepted_at,
                  invites.expired_at,
                  invites.failure_stage,
                  invites.failure_code,
                  invites.token_id,
                  invites.purpose,
                  invites.expires_at,
                  invites.used_at,
                  invites.invalidated_reason,
                  invites."?column?",
                  invites."array",
                  invites.array_1       AS "array",
                  invites.array_2       AS "array",
                  invites.array_3       AS "array",
                  invites."?column?_1"  AS "?column?",
                  invites."?column?_2"  AS "?column?",
                  invites."?column?_3"  AS "?column?",
                  invites."?column?_4"  AS "?column?",
                  invites."?column?_5"  AS "?column?",
                  invites."?column?_6"  AS "?column?",
                  invites."case",
                  invites.coalesce_2    AS "coalesce",
                  invites.coalesce_3    AS "coalesce",
                  invites."?column?_7"  AS "?column?",
                  invites."current_timestamp"
           FROM invites invites(org_id, "varchar", text, target_email, status, "coalesce", coalesce_1, uuid, text_1,
                                bool, timestamptz, timestamptz_1, text_2, invitation_id, target_cohort_id,
                                target_class_id, invited_at, sent_at, accepted_at, expired_at, failure_stage,
                                failure_code, token_id, purpose, expires_at, used_at, invalidated_reason, "?column?",
                                "array", array_1, array_2, array_3, "?column?_1", "?column?_2", "?column?_3",
                                "?column?_4", "?column?_5", "?column?_6", "case", coalesce_2, coalesce_3, "?column?_7",
                                "current_timestamp"))
SELECT org_id,
       row_type,
       name::character varying(200)                   AS name,
       email::character varying(320)                  AS email,
       display_status::character varying(30)          AS display_status,
       source_status_at,
       sort_at,
       user_id,
       status::character varying(30)                  AS status,
       is_email_verified,
       last_login_at,
       inactivated_at,
       inactivated_reason_code::character varying(30) AS inactivated_reason_code,
       invitation_id,
       target_cohort_id,
       target_class_id,
       invited_at,
       sent_at,
       accepted_at,
       expired_at,
       failure_stage::character varying(100)          AS failure_stage,
       failure_code::character varying(100)           AS failure_code,
       current_token_id,
       purpose::character varying(100)                AS purpose,
       expires_at,
       used_at,
       invalidated_reason,
       active_assignment_count,
       assigned_cohort_ids,
       assigned_class_ids,
       assigned_cohort_names::text[]                  AS assigned_cohort_names,
       assigned_class_names::text[]                   AS assigned_class_names,
       assigned_trainee_count,
       is_unassigned,
       can_suspend,
       can_reactivate,
       can_resend,
       can_cancel,
       action_block_code::character varying(100)      AS action_block_code,
       account_count,
       pending_invitation_count,
       aggregation_status::character varying(20)      AS aggregation_status,
       as_of_at
FROM z;

alter table operator_manager_list_view
    owner to postgres;

grant delete, insert, select, update on operator_manager_list_view to teamiz_app;

create view operator_project_concept_candidate_view
            (org_id, project_id, material_id, version_id, effective_analysis_id, section_id, page_range, mapping_id,
             teaches_id, extracted_name, source_description, description_status, usage_count, latest_project_id,
             latest_project_name, latest_project_sequence_no, latest_analysis_sequence_no, average_reached_stage,
             underperforming_class_count, evaluated_trainee_count, result_coverage_status, matched_team_count,
             all_unmatched_team_count, evidence_warning_codes, selectable, unavailable_reason_code,
             source_mapping_status, aggregation_status, as_of_at)
as
SELECT p.org_id,
       p.project_id,
       v.material_id,
       v.version_id,
       m.source_analysis_id                                                        AS effective_analysis_id,
       m.section_id,
       (m.page_start::text || '-'::text) || m.page_end::text                       AS page_range,
       m.mapping_id,
       m.teaches_id,
       m.extracted_name,
       m.source_description,
       CASE
           WHEN m.source_description IS NULL OR btrim(m.source_description) = ''::text THEN 'MISSING'::text
           ELSE 'AVAILABLE'::text
           END::character varying(30)                                              AS description_status,
       COALESCE(hist.usage_count, 0)                                               AS usage_count,
       hist.latest_project_id,
       hist.latest_project_name::character varying(200)                            AS latest_project_name,
       hist.latest_project_sequence_no,
       hist.latest_analysis_sequence_no,
       hist.average_reached_stage,
       COALESCE(hist.underperforming_class_count, 0)                               AS underperforming_class_count,
       COALESCE(hist.evaluated_trainee_count, 0)                                   AS evaluated_trainee_count,
       CASE
           WHEN COALESCE(hist.evaluated_trainee_count, 0) = 0 THEN 'NO_RESULT'::text
           ELSE 'AVAILABLE'::text
           END::character varying(100)                                             AS result_coverage_status,
       COALESCE(hist.matched_team_count, 0)                                        AS matched_team_count,
       COALESCE(hist.all_unmatched_team_count, 0)                                  AS all_unmatched_team_count,
       COALESCE(hist.evidence_warning_codes, ARRAY []::text[])                     AS evidence_warning_codes,
       m.mapping_status::text = 'ACTIVE'::text AND t.status::text = 'ACTIVE'::text AS selectable,
       CASE
           WHEN m.mapping_status::text <> 'ACTIVE'::text THEN 'MAPPING_INACTIVE'::text
           WHEN t.status::text <> 'ACTIVE'::text THEN 'TEACHES_INACTIVE'::text
           ELSE NULL::text
           END::character varying(100)                                             AS unavailable_reason_code,
       m.mapping_status::character varying(100)                                    AS source_mapping_status,
       'COMPLETE'::character varying(20)                                           AS aggregation_status,
       CURRENT_TIMESTAMP                                                           AS as_of_at
FROM project p
         JOIN project_curriculum pc ON pc.project_id = p.project_id
         JOIN curriculum_version v ON v.version_id = pc.curriculum_version_id
         JOIN curriculum_teaches_mapping m ON m.version_id = v.version_id
         JOIN teaches t ON t.teaches_id = m.teaches_id
         LEFT JOIN LATERAL ( SELECT count(DISTINCT pvc.project_concept_id)::integer                            AS usage_count,
                                    (array_agg(ph.project_id ORDER BY ph.sequence_no DESC))[1]                 AS latest_project_id,
                                    (array_agg(ph.name ORDER BY ph.sequence_no DESC))[1]                       AS latest_project_name,
                                    max(ph.sequence_no)                                                        AS latest_project_sequence_no,
                                    max((SELECT s.analysis_sequence_no
                                         FROM mini_project_round_sequence_view s
                                         WHERE s.assessment_round_id = r.assessment_round_id))                 AS latest_analysis_sequence_no,
                                    round(avg(
                                                  CASE ap.best_success_stage
                                                      WHEN 'L4'::text THEN 4
                                                      WHEN 'L3'::text THEN 3
                                                      WHEN 'L2'::text THEN 2
                                                      WHEN 'L1'::text THEN 1
                                                      ELSE 0
                                                      END),
                                          2)::text                                                             AS average_reached_stage,
                                    0                                                                          AS underperforming_class_count,
                                    count(DISTINCT ma.user_id)::integer                                        AS evaluated_trainee_count,
                                    count(DISTINCT ca.team_id)::integer                                        AS matched_team_count,
                                    count(DISTINCT ca.team_id)
                                    FILTER (WHERE ap.generation_status::text = 'NOT_GENERATED'::text)::integer AS all_unmatched_team_count,
                                    array_remove(ARRAY [
                                                     CASE
                                                         WHEN count(*) FILTER (WHERE
                                                             ap.generation_status::text = 'NOT_GENERATED'::text AND
                                                             ap.not_generated_reason_code::text =
                                                             'NO_MATCHING_CODE_EVIDENCE'::text) > 0
                                                             THEN 'NO_MATCHING_CODE_EVIDENCE'::text
                                                         ELSE NULL::text
                                                         END],
                                                 NULL::text)                                                   AS evidence_warning_codes
                             FROM project_verification_concept pvc
                                      JOIN project_verification_concept_set pcs
                                           ON pcs.concept_set_id = pvc.concept_set_id
                                      JOIN project ph ON ph.project_id = pcs.project_id AND ph.org_id = p.org_id
                                      LEFT JOIN project_assessment_round r ON r.project_id = ph.project_id
                                      LEFT JOIN assessment_problem ap
                                                ON ap.project_verification_concept_id = pvc.project_concept_id
                                      LEFT JOIN code_analysis ca ON ca.analysis_id = ap.code_analysis_id
                                      LEFT JOIN measurement_attempt ma ON ma.code_analysis_id = ca.analysis_id
                             WHERE pvc.teaches_id = m.teaches_id
                               AND ph.project_id <> p.project_id) hist ON true
WHERE p.deleted_at IS NULL;

alter table operator_project_concept_candidate_view
    owner to postgres;

grant delete, insert, select, update on operator_project_concept_candidate_view to teamiz_app;

create view operator_project_detail_view
            (org_id, cohort_id, project_id, sequence_no, name, category, lifecycle_status, start_date, end_date,
             curriculum_version_ids, active_concept_set_id, concept_set_version, verification_concepts, requirements,
             active_round_count, rounds, display_round_id, submission_due_at, assessment_window_mode,
             assessment_window_hours, time_limit_sec, time_enforcement_mode, policy_time_limit_status,
             can_access_schedule, can_access_status, tab_lock_reason_codes, report_publish_mode,
             report_publish_not_before_at, round_assessment_open_at, round_assessment_due_at)
as
SELECT p.org_id,
       p.cohort_id,
       p.project_id,
       p.sequence_no,
       p.name,
       p.project_category::character varying(100)         AS category,
       p.lifecycle_status,
       p.start_date,
       p.end_date,
       COALESCE(cur.version_ids, ARRAY []::uuid[])        AS curriculum_version_ids,
       cs.concept_set_id                                  AS active_concept_set_id,
       cs.version_no                                      AS concept_set_version,
       COALESCE(concepts.items, '[]'::jsonb)              AS verification_concepts,
       COALESCE(req.items, '[]'::jsonb)                   AS requirements,
       COALESCE(rounds.active_count, 0)                   AS active_round_count,
       COALESCE(rounds.items, '[]'::jsonb)                AS rounds,
       rounds.display_round_id,
       rounds.submission_due_at,
       'ROUND_COMMON_WINDOW'::character varying(30)       AS assessment_window_mode,
       NULL::integer                                      AS assessment_window_hours,
       NULL::integer                                      AS time_limit_sec,
       'ADVISORY'::character varying(100)                 AS time_enforcement_mode,
       'NOT_CONFIGURED'::character varying(100)           AS policy_time_limit_status,
       p.lifecycle_status::text <> 'CLOSED'::text         AS can_access_schedule,
       true                                               AS can_access_status,
       array_remove(ARRAY [
                        CASE
                            WHEN NOT p.curriculum_not_applicable AND COALESCE(cardinality(cur.version_ids), 0) = 0
                                THEN 'CURRICULUM_REQUIRED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN COALESCE(concepts.concept_count, 0) <> 3 THEN 'THREE_CONCEPTS_REQUIRED'::text
                            ELSE NULL::text
                            END], NULL::text)             AS tab_lock_reason_codes,
       rounds.report_publish_mode::character varying(100) AS report_publish_mode,
       rounds.report_publish_not_before_at,
       rounds.assessment_open_at                          AS round_assessment_open_at,
       rounds.assessment_due_at                           AS round_assessment_due_at
FROM project p
         LEFT JOIN LATERAL ( SELECT array_agg(pc.curriculum_version_id ORDER BY pc.sequence_no) AS version_ids
                             FROM project_curriculum pc
                             WHERE pc.project_id = p.project_id) cur ON true
         LEFT JOIN LATERAL ( SELECT x.concept_set_id,
                                    x.project_id,
                                    x.org_id,
                                    x.version_no,
                                    x.status,
                                    x.effective_from,
                                    x.effective_to,
                                    x.created_by,
                                    x.change_reason,
                                    x.created_at
                             FROM project_verification_concept_set x
                             WHERE x.project_id = p.project_id
                               AND x.status::text = 'ACTIVE'::text
                               AND x.effective_from <= CURRENT_TIMESTAMP
                               AND (x.effective_to IS NULL OR x.effective_to > CURRENT_TIMESTAMP)
                             ORDER BY x.version_no DESC
                             LIMIT 1) cs ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer                 AS concept_count,
                                    jsonb_agg(jsonb_build_object('projectConceptId', c.project_concept_id, 'sequenceNo',
                                                                 c.sequence_no, 'teachesId', c.teaches_id, 'mappingId',
                                                                 c.source_mapping_id, 'name', t.canonical_name)
                                              ORDER BY c.sequence_no) AS items
                             FROM project_verification_concept c
                                      LEFT JOIN teaches t ON t.teaches_id = c.teaches_id
                             WHERE c.concept_set_id = cs.concept_set_id) concepts ON true
         LEFT JOIN LATERAL ( SELECT jsonb_agg(jsonb_build_object('requirementId', r.requirement_id, 'key',
                                                                 r.requirement_key, 'sequenceNo', r.sequence_no,
                                                                 'title', r.title, 'description', r.description)
                                              ORDER BY r.sequence_no) AS items
                             FROM project_requirement r
                             WHERE r.project_id = p.project_id
                               AND r.active
                               AND r.effective_from <= CURRENT_TIMESTAMP
                               AND (r.effective_to IS NULL OR r.effective_to > CURRENT_TIMESTAMP)) req ON true
         LEFT JOIN LATERAL ( SELECT count(*) FILTER (WHERE r.deleted_at IS NULL)::integer AS active_count,
                                    jsonb_agg(jsonb_build_object('assessmentRoundId', r.assessment_round_id, 'roundNo',
                                                                 r.round_no, 'roundName', r.round_name, 'status',
                                                                 r.status, 'submissionDueAt', r.submission_due_at,
                                                                 'isFinal', r.is_final, 'assessmentOpenAt',
                                                                 r.assessment_open_at, 'assessmentDueAt',
                                                                 r.assessment_due_at) ORDER BY r.round_no)
                                    FILTER (WHERE r.deleted_at IS NULL)                   AS items,
                                    (array_agg(r.assessment_round_id ORDER BY (
                                        CASE
                                            WHEN r.status::text = ANY
                                                 (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                THEN 0
                                            ELSE 1
                                            END), r.round_no))[1]                         AS display_round_id,
                                    (array_agg(r.submission_due_at ORDER BY (
                                        CASE
                                            WHEN r.status::text = ANY
                                                 (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                THEN 0
                                            ELSE 1
                                            END), r.round_no))[1]                         AS submission_due_at,
                                    (array_agg(r.report_publish_mode ORDER BY (
                                        CASE
                                            WHEN r.status::text = ANY
                                                 (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                THEN 0
                                            ELSE 1
                                            END), r.round_no))[1]                         AS report_publish_mode,
                                    (array_agg(r.report_publish_not_before_at ORDER BY (
                                        CASE
                                            WHEN r.status::text = ANY
                                                 (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                THEN 0
                                            ELSE 1
                                            END), r.round_no))[1]                         AS report_publish_not_before_at,
                                    (array_agg(r.assessment_open_at ORDER BY (
                                        CASE
                                            WHEN r.status::text = ANY
                                                 (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                THEN 0
                                            ELSE 1
                                            END), r.round_no))[1]                         AS assessment_open_at,
                                    (array_agg(r.assessment_due_at ORDER BY (
                                        CASE
                                            WHEN r.status::text = ANY
                                                 (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                THEN 0
                                            ELSE 1
                                            END), r.round_no))[1]                         AS assessment_due_at
                             FROM project_assessment_round r
                             WHERE r.project_id = p.project_id
                               AND r.deleted_at IS NULL) rounds ON true
WHERE p.deleted_at IS NULL;

alter table operator_project_detail_view
    owner to postgres;

grant delete, insert, select, update on operator_project_detail_view to teamiz_app;

create view operator_project_list_view
            (org_id, cohort_id, project_id, project_sequence_no, project_name, project_category, start_date, end_date,
             lifecycle_status, concept_source_mode, curriculum_not_applicable, project_management_status,
             readiness_reason_codes, readiness_reason_count, curriculum_versions, active_concept_set_id,
             verification_concept_count, verification_concepts, active_round_count, total_round_count,
             round_cardinality_status, display_round_id, display_round_no, display_submission_due_at,
             display_assessment_window_mode, display_assessment_window_hours, deadline_status, remaining_deadline_days,
             deadline_warning, total_project_count, preparing_project_count, ready_project_count, running_project_count,
             closed_project_count, class_count, trainee_membership_count, aggregation_status, as_of_at)
as
SELECT p.org_id,
       p.cohort_id,
       p.project_id,
       p.sequence_no                                                                                                                                                                                                      AS project_sequence_no,
       p.name                                                                                                                                                                                                             AS project_name,
       p.project_category,
       p.start_date,
       p.end_date,
       p.lifecycle_status,
       p.concept_source_mode,
       p.curriculum_not_applicable,
       CASE
           WHEN p.lifecycle_status::text = 'CLOSED'::text THEN 'CLOSED'::text
           WHEN p.lifecycle_status::text = ANY
                (ARRAY ['RUNNING'::character varying::text, 'ACTIVE'::character varying::text]) THEN 'RUNNING'::text
           WHEN (p.curriculum_not_applicable OR COALESCE(cur.cnt, 0) > 0) AND COALESCE(con.concept_count, 0) = 3 AND
                COALESCE(rd.total_round_count, 0) > 0 THEN 'READY'::text
           ELSE 'PREPARING'::text
           END::character varying(30)                                                                                                                                                                                     AS project_management_status,
       array_remove(ARRAY [
                        CASE
                            WHEN NOT p.curriculum_not_applicable AND COALESCE(cur.cnt, 0) = 0 THEN 'CURRICULUM_REQUIRED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN COALESCE(con.concept_count, 0) <> 3 THEN 'THREE_CONCEPTS_REQUIRED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN COALESCE(rd.total_round_count, 0) = 0 THEN 'ROUND_REQUIRED'::text
                            ELSE NULL::text
                            END],
                    NULL::text)                                                                                                                                                                                           AS readiness_reason_codes,
       cardinality(array_remove(ARRAY [
                                    CASE
                                        WHEN NOT p.curriculum_not_applicable AND COALESCE(cur.cnt, 0) = 0
                                            THEN 'CURRICULUM_REQUIRED'::text
                                        ELSE NULL::text
                                        END,
                                    CASE
                                        WHEN COALESCE(con.concept_count, 0) <> 3 THEN 'THREE_CONCEPTS_REQUIRED'::text
                                        ELSE NULL::text
                                        END,
                                    CASE
                                        WHEN COALESCE(rd.total_round_count, 0) = 0 THEN 'ROUND_REQUIRED'::text
                                        ELSE NULL::text
                                        END],
                                NULL::text))                                                                                                                                                                              AS readiness_reason_count,
       COALESCE(cur.items, '[]'::jsonb)                                                                                                                                                                                   AS curriculum_versions,
       cs.concept_set_id                                                                                                                                                                                                  AS active_concept_set_id,
       COALESCE(con.concept_count, 0)                                                                                                                                                                                     AS verification_concept_count,
       COALESCE(con.items, '[]'::jsonb)                                                                                                                                                                                   AS verification_concepts,
       COALESCE(rd.active_round_count, 0)                                                                                                                                                                                 AS active_round_count,
       COALESCE(rd.total_round_count, 0)                                                                                                                                                                                  AS total_round_count,
       CASE
           WHEN p.project_category::text = 'MINI'::text AND COALESCE(rd.total_round_count, 0) = 1 THEN 'VALID'::text
           WHEN p.project_category::text = 'MINI'::text THEN 'INVALID_MINI_ROUND_COUNT'::text
           WHEN COALESCE(rd.total_round_count, 0) > 0 THEN 'VALID'::text
           ELSE 'EMPTY'::text
           END::character varying(100)                                                                                                                                                                                    AS round_cardinality_status,
       rd.display_round_id,
       rd.display_round_no,
       rd.display_submission_due_at,
       'ANALYSIS_COMPLETED_PLUS_24H'::character varying(100)                                                                                                                                                              AS display_assessment_window_mode,
       '24'::text                                                                                                                                                                                                         AS display_assessment_window_hours,
       CASE
           WHEN rd.display_submission_due_at IS NULL THEN 'NOT_CONFIGURED'::text
           WHEN rd.display_submission_due_at < CURRENT_TIMESTAMP THEN 'PASSED'::text
           WHEN rd.display_submission_due_at < (CURRENT_TIMESTAMP + '3 days'::interval) THEN 'IMMINENT'::text
           ELSE 'NORMAL'::text
           END::character varying(30)                                                                                                                                                                                     AS deadline_status,
       CASE
           WHEN rd.display_submission_due_at IS NULL THEN NULL::integer
           ELSE ceil(EXTRACT(epoch FROM rd.display_submission_due_at - CURRENT_TIMESTAMP) / 86400.0)::integer
           END                                                                                                                                                                                                            AS remaining_deadline_days,
       rd.display_submission_due_at IS NOT NULL AND rd.display_submission_due_at <
                                                    (CURRENT_TIMESTAMP + '3 days'::interval)                                                                                                                              AS deadline_warning,
       count(*) OVER (PARTITION BY p.cohort_id)::integer                                                                                                                                                                  AS total_project_count,
       count(*) FILTER (WHERE
           NOT ((p.curriculum_not_applicable OR COALESCE(cur.cnt, 0) > 0) AND COALESCE(con.concept_count, 0) = 3 AND
                COALESCE(rd.total_round_count, 0) > 0) AND (p.lifecycle_status::text <> ALL
                                                            (ARRAY ['RUNNING'::character varying::text, 'ACTIVE'::character varying::text, 'CLOSED'::character varying::text]))) OVER (PARTITION BY p.cohort_id)::integer AS preparing_project_count,
       count(*) FILTER (WHERE (p.curriculum_not_applicable OR COALESCE(cur.cnt, 0) > 0) AND
                              COALESCE(con.concept_count, 0) = 3 AND COALESCE(rd.total_round_count, 0) > 0 AND
                              (p.lifecycle_status::text <> ALL
                               (ARRAY ['RUNNING'::character varying::text, 'ACTIVE'::character varying::text, 'CLOSED'::character varying::text]))) OVER (PARTITION BY p.cohort_id)::integer                              AS ready_project_count,
       count(*) FILTER (WHERE p.lifecycle_status::text = ANY
                              (ARRAY ['RUNNING'::character varying::text, 'ACTIVE'::character varying::text])) OVER (PARTITION BY p.cohort_id)::integer                                                                   AS running_project_count,
       count(*)
       FILTER (WHERE p.lifecycle_status::text = 'CLOSED'::text) OVER (PARTITION BY p.cohort_id)::integer                                                                                                                  AS closed_project_count,
       COALESCE(coh.class_count, 0)                                                                                                                                                                                       AS class_count,
       COALESCE(coh.trainee_count, 0)                                                                                                                                                                                     AS trainee_membership_count,
       'COMPLETE'::character varying(20)                                                                                                                                                                                  AS aggregation_status,
       CURRENT_TIMESTAMP                                                                                                                                                                                                  AS as_of_at
FROM project p
         LEFT JOIN LATERAL ( SELECT count(*)::integer                                                       AS cnt,
                                    jsonb_agg(jsonb_build_object('versionId', v.version_id, 'versionNo', v.version_no,
                                                                 'title', m.title) ORDER BY pc.sequence_no) AS items
                             FROM project_curriculum pc
                                      JOIN curriculum_version v ON v.version_id = pc.curriculum_version_id
                                      JOIN curriculum_material m ON m.material_id = v.material_id
                             WHERE pc.project_id = p.project_id) cur ON true
         LEFT JOIN LATERAL ( SELECT x.concept_set_id,
                                    x.project_id,
                                    x.org_id,
                                    x.version_no,
                                    x.status,
                                    x.effective_from,
                                    x.effective_to,
                                    x.created_by,
                                    x.change_reason,
                                    x.created_at
                             FROM project_verification_concept_set x
                             WHERE x.project_id = p.project_id
                               AND x.status::text = 'ACTIVE'::text
                             ORDER BY x.version_no DESC
                             LIMIT 1) cs ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer                                                      AS concept_count,
                                    jsonb_agg(jsonb_build_object('projectConceptId', x.project_concept_id, 'sequenceNo',
                                                                 x.sequence_no, 'teachesId', x.teaches_id, 'name',
                                                                 t.canonical_name) ORDER BY x.sequence_no) AS items
                             FROM project_verification_concept x
                                      LEFT JOIN teaches t ON t.teaches_id = x.teaches_id
                             WHERE x.concept_set_id = cs.concept_set_id) con ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer                                                                                              AS total_round_count,
                                    count(*) FILTER (WHERE x.status::text = ANY
                                                           (ARRAY ['PLANNED'::character varying::text, 'OPEN'::character varying::text]))::integer AS active_round_count,
                                    (array_agg(x.assessment_round_id ORDER BY (
                                        CASE
                                            WHEN x.status::text = ANY
                                                 (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                THEN 0
                                            ELSE 1
                                            END), x.round_no))[1]                                                                                  AS display_round_id,
                                    (array_agg(x.round_no ORDER BY (
                                        CASE
                                            WHEN x.status::text = ANY
                                                 (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                THEN 0
                                            ELSE 1
                                            END), x.round_no))[1]                                                                                  AS display_round_no,
                                    (array_agg(x.submission_due_at ORDER BY (
                                        CASE
                                            WHEN x.status::text = ANY
                                                 (ARRAY ['OPEN'::character varying::text, 'PLANNED'::character varying::text])
                                                THEN 0
                                            ELSE 1
                                            END), x.round_no))[1]                                                                                  AS display_submission_due_at
                             FROM project_assessment_round x
                             WHERE x.project_id = p.project_id
                               AND x.deleted_at IS NULL) rd ON true
         LEFT JOIN LATERAL ( SELECT ((SELECT count(*) AS count
                                      FROM class c
                                      WHERE c.cohort_id = p.cohort_id
                                        AND c.deleted_at IS NULL))::integer AS class_count,
                                    ((SELECT count(*) AS count
                                      FROM cohort_member cm
                                      WHERE cm.cohort_id = p.cohort_id
                                        AND cm.status::text = 'ACTIVE'::text
                                        AND cm.left_at IS NULL))::integer   AS trainee_count) coh ON true
WHERE p.deleted_at IS NULL;

alter table operator_project_list_view
    owner to postgres;

grant delete, insert, select, update on operator_project_list_view to teamiz_app;

create view operator_project_status_view
            (org_id, cohort_id, project_id, assessment_round_id, class_id, manager_names, manager_count,
             manager_missing_warning, eligible_trainee_count, submission_target_team_count,
             submission_covered_trainee_count, analysis_succeeded_team_count, analysis_failed_team_count,
             analysis_succeeded_trainee_count, analysis_failed_trainee_count, prepared_problem_count,
             prepared_question_count, problem_set_completion_status, assessment_eligible_trainee_count,
             assessment_completed_trainee_count, assessment_not_ready_count, own_commit_ready_trainee_count,
             insufficient_own_commit_evidence_count, analysis_failure_codes, aggregation_status, as_of_at)
as
SELECT p.org_id,
       p.cohort_id,
       p.project_id,
       r.assessment_round_id,
       c.class_id,
       COALESCE(ms.names, ARRAY []::text[]::character varying[])::text[]                                             AS manager_names,
       COALESCE(ms.manager_count, 0)                                                                                 AS manager_count,
       CASE
           WHEN COALESCE(ms.manager_count, 0) = 0 THEN 'MANAGER_NOT_ASSIGNED'::text
           ELSE NULL::text
           END                                                                                                       AS manager_missing_warning,
       COALESCE(stats.eligible_trainee_count, 0)                                                                     AS eligible_trainee_count,
       COALESCE(stats.submission_target_team_count, 0)                                                               AS submission_target_team_count,
       COALESCE(stats.submission_covered_trainee_count, 0)                                                           AS submission_covered_trainee_count,
       COALESCE(stats.analysis_succeeded_team_count, 0)                                                              AS analysis_succeeded_team_count,
       COALESCE(stats.analysis_failed_team_count, 0)                                                                 AS analysis_failed_team_count,
       COALESCE(stats.analysis_succeeded_trainee_count, 0)                                                           AS analysis_succeeded_trainee_count,
       COALESCE(stats.analysis_failed_trainee_count, 0)                                                              AS analysis_failed_trainee_count,
       COALESCE(stats.prepared_problem_count, 0)                                                                     AS prepared_problem_count,
       COALESCE(stats.prepared_question_count, 0)                                                                    AS prepared_question_count,
       CASE
           WHEN COALESCE(stats.prepared_problem_count, 0) = 0 THEN 'NOT_READY'::text
           WHEN COALESCE(stats.prepared_problem_count, 0) >= 3 THEN 'READY'::text
           ELSE 'PARTIAL'::text
           END::character varying(100)                                                                               AS problem_set_completion_status,
       COALESCE(stats.assessment_eligible_trainee_count, 0)                                                          AS assessment_eligible_trainee_count,
       COALESCE(stats.assessment_completed_trainee_count, 0)                                                         AS assessment_completed_trainee_count,
       GREATEST(COALESCE(stats.eligible_trainee_count, 0) - COALESCE(stats.assessment_eligible_trainee_count, 0),
                0)                                                                                                   AS assessment_not_ready_count,
       COALESCE(stats.own_commit_ready_trainee_count, 0)                                                             AS own_commit_ready_trainee_count,
       COALESCE(stats.insufficient_own_commit_evidence_count, 0)                                                     AS insufficient_own_commit_evidence_count,
       COALESCE(stats.analysis_failure_codes, ARRAY []::text[])                                                      AS analysis_failure_codes,
       'COMPLETE'::character varying(20)                                                                             AS aggregation_status,
       CURRENT_TIMESTAMP                                                                                             AS as_of_at
FROM project p
         JOIN project_assessment_round r ON r.project_id = p.project_id AND r.deleted_at IS NULL
         JOIN class c ON c.cohort_id = p.cohort_id AND c.deleted_at IS NULL
         LEFT JOIN LATERAL ( SELECT count(*)::integer                 AS manager_count,
                                    array_agg(u.name ORDER BY u.name) AS names
                             FROM manager_assignment ma
                                      JOIN app_user u ON u.user_id = ma.manager_user_id
                             WHERE ma.class_id = c.class_id
                               AND ma.status::text = 'ACTIVE'::text
                               AND ma.unassigned_at IS NULL) ms ON true
         LEFT JOIN LATERAL ( SELECT ((SELECT count(*) AS count
                                      FROM project_membership pm
                                      WHERE pm.project_id = p.project_id
                                        AND pm.class_id = c.class_id
                                        AND pm.status::text = 'ACTIVE'::text))::integer               AS eligible_trainee_count,
                                    ((SELECT count(*) AS count
                                      FROM team t
                                      WHERE t.project_id = p.project_id
                                        AND t.class_id = c.class_id
                                        AND t.deleted_at IS NULL))::integer                           AS submission_target_team_count,
                                    ((SELECT count(DISTINCT pm.user_id) AS count
                                      FROM submission s
                                               JOIN team t ON t.team_id = s.team_id
                                               JOIN team_membership tm ON tm.team_id = t.team_id AND tm.to_at IS NULL
                                               JOIN project_membership pm
                                                    ON pm.project_membership_id = tm.project_membership_id
                                      WHERE t.project_id = p.project_id
                                        AND t.class_id = c.class_id
                                        AND s.assessment_round_id = r.assessment_round_id
                                        AND s.is_current))::integer                                   AS submission_covered_trainee_count,
                                    ((SELECT count(DISTINCT aj.team_id) AS count
                                      FROM analysis_job aj
                                               JOIN team t ON t.team_id = aj.team_id
                                      WHERE t.project_id = p.project_id
                                        AND t.class_id = c.class_id
                                        AND aj.assessment_round_id = r.assessment_round_id
                                        AND aj.status::text = 'SUCCEEDED'::text))::integer            AS analysis_succeeded_team_count,
                                    ((SELECT count(DISTINCT aj.team_id) AS count
                                      FROM analysis_job aj
                                               JOIN team t ON t.team_id = aj.team_id
                                      WHERE t.project_id = p.project_id
                                        AND t.class_id = c.class_id
                                        AND aj.assessment_round_id = r.assessment_round_id
                                        AND aj.status::text = 'FAILED'::text))::integer               AS analysis_failed_team_count,
                                    ((SELECT count(DISTINCT pm.user_id) AS count
                                      FROM analysis_job aj
                                               JOIN team t ON t.team_id = aj.team_id
                                               JOIN team_membership tm ON tm.team_id = t.team_id AND tm.to_at IS NULL
                                               JOIN project_membership pm
                                                    ON pm.project_membership_id = tm.project_membership_id
                                      WHERE t.class_id = c.class_id
                                        AND aj.assessment_round_id = r.assessment_round_id
                                        AND aj.status::text = 'SUCCEEDED'::text))::integer            AS analysis_succeeded_trainee_count,
                                    ((SELECT count(DISTINCT pm.user_id) AS count
                                      FROM analysis_job aj
                                               JOIN team t ON t.team_id = aj.team_id
                                               JOIN team_membership tm ON tm.team_id = t.team_id AND tm.to_at IS NULL
                                               JOIN project_membership pm
                                                    ON pm.project_membership_id = tm.project_membership_id
                                      WHERE t.class_id = c.class_id
                                        AND aj.assessment_round_id = r.assessment_round_id
                                        AND aj.status::text = 'FAILED'::text))::integer               AS analysis_failed_trainee_count,
                                    ((SELECT count(*) AS count
                                      FROM assessment_problem ap
                                               JOIN code_analysis ca ON ca.analysis_id = ap.code_analysis_id
                                               JOIN team t ON t.team_id = ca.team_id
                                      WHERE ca.assessment_round_id = r.assessment_round_id
                                        AND t.class_id = c.class_id
                                        AND ap.generation_status::text = 'GENERATED'::text))::integer AS prepared_problem_count,
                                    ((SELECT count(*) AS count
                                      FROM problem_stage ps
                                               JOIN assessment_problem ap ON ap.problem_id = ps.problem_id
                                               JOIN code_analysis ca ON ca.analysis_id = ap.code_analysis_id
                                               JOIN team t ON t.team_id = ca.team_id
                                      WHERE ca.assessment_round_id = r.assessment_round_id
                                        AND t.class_id = c.class_id))::integer                        AS prepared_question_count,
                                    ((SELECT count(DISTINCT ma.user_id) AS count
                                      FROM measurement_attempt ma
                                               JOIN project_membership pm
                                                    ON pm.user_id = ma.user_id AND pm.project_id = p.project_id
                                      WHERE ma.assessment_round_id = r.assessment_round_id
                                        AND pm.class_id = c.class_id
                                        AND ma.assessment_open_at IS NOT NULL))::integer              AS assessment_eligible_trainee_count,
                                    ((SELECT count(DISTINCT ma.user_id) AS count
                                      FROM measurement_attempt ma
                                               JOIN project_membership pm
                                                    ON pm.user_id = ma.user_id AND pm.project_id = p.project_id
                                      WHERE ma.assessment_round_id = r.assessment_round_id
                                        AND pm.class_id = c.class_id
                                        AND ma.attempt_type::text = 'INITIAL'::text
                                        AND ma.status::text = 'COMPLETED'::text))::integer            AS assessment_completed_trainee_count,
                                    ((SELECT count(DISTINCT ap.target_user_id) AS count
                                      FROM assessment_problem ap
                                               JOIN measurement_attempt ma ON ma.attempt_id = ap.measurement_attempt_id
                                               JOIN project_membership pm
                                                    ON pm.user_id = ma.user_id AND pm.project_id = p.project_id
                                      WHERE ma.assessment_round_id = r.assessment_round_id
                                        AND pm.class_id = c.class_id
                                        AND ap.problem_scope::text = 'INDIVIDUAL_OWN_COMMIT'::text
                                        AND ap.generation_status::text = 'GENERATED'::text))::integer AS own_commit_ready_trainee_count,
                                    ((SELECT count(DISTINCT ma.user_id) AS count
                                      FROM measurement_attempt ma
                                               JOIN project_membership pm
                                                    ON pm.user_id = ma.user_id AND pm.project_id = p.project_id
                                      WHERE ma.assessment_round_id = r.assessment_round_id
                                        AND pm.class_id = c.class_id
                                        AND ma.terminal_reason_code::text =
                                            'INSUFFICIENT_OWN_COMMIT_EVIDENCE'::text))::integer       AS insufficient_own_commit_evidence_count,
                                    (SELECT array_agg(DISTINCT aj.failure_reason)
                                            FILTER (WHERE aj.failure_reason IS NOT NULL) AS array_agg
                                     FROM analysis_job aj
                                              JOIN team t ON t.team_id = aj.team_id
                                     WHERE aj.assessment_round_id = r.assessment_round_id
                                       AND t.class_id = c.class_id
                                       AND aj.status::text = 'FAILED'::text)                          AS analysis_failure_codes) stats
                   ON true
WHERE p.deleted_at IS NULL;

alter table operator_project_status_view
    owner to postgres;

grant delete, insert, select, update on operator_project_status_view to teamiz_app;

create view super_admin_account_view
            (user_id, name, email, role_code, org_id, status, is_email_verified, email_verified_at, last_login_at,
             failed_login_count, login_blocked_until, inactivated_at, inactivated_by, inactivated_reason_code,
             inactivated_reason, active_refresh_token_count, latest_token_issued_at, latest_token_used_at,
             latest_security_event_code, latest_security_event_at, is_current_user, can_suspend, can_reactivate,
             action_block_code)
as
SELECT u.user_id,
       u.name::character varying(100)                                                         AS name,
       u.email::character varying(320)                                                        AS email,
       u.role_code,
       u.org_id,
       u.status,
       u.is_email_verified,
       u.email_verified_at,
       u.last_login_at,
       u.failed_login_count,
       u.login_blocked_until,
       u.inactivated_at,
       u.inactivated_by,
       u.inactivated_reason_code,
       u.inactivated_reason,
       COALESCE(ts.active_count, 0::bigint)::integer                                          AS active_refresh_token_count,
       ts.latest_issued_at                                                                    AS latest_token_issued_at,
       ts.latest_used_at                                                                      AS latest_token_used_at,
       se.event_code                                                                          AS latest_security_event_code,
       se.occurred_at                                                                         AS latest_security_event_at,
       u.user_id = NULLIF(current_setting('app.current_user_id'::text, true), ''::text)::uuid AS is_current_user,
       u.status::text = 'ACTIVE'::text AND sa.active_count > 1                                AS can_suspend,
       u.status::text = 'INACTIVE'::text                                                      AS can_reactivate,
       CASE
           WHEN u.status::text = 'ACTIVE'::text AND sa.active_count <= 1 THEN 'LAST_SUPERADMIN'::text
           WHEN u.status::text <> ALL (ARRAY ['ACTIVE'::character varying::text, 'INACTIVE'::character varying::text])
               THEN 'ACCOUNT_STATUS_NOT_ACTIONABLE'::text
           ELSE NULL::text
           END::character varying(100)                                                        AS action_block_code
FROM app_user u
         LEFT JOIN LATERAL ( SELECT count(*)
                                    FILTER (WHERE rt.revoked_at IS NULL AND rt.expires_at > CURRENT_TIMESTAMP) AS active_count,
                                    max(rt.issued_at)                                                          AS latest_issued_at,
                                    max(rt.last_used_at)                                                       AS latest_used_at
                             FROM refresh_token rt
                             WHERE rt.user_id = u.user_id) ts ON true
         LEFT JOIN LATERAL ( SELECT al.event_code,
                                    al.occurred_at
                             FROM audit_log al
                             WHERE al.actor_user_id = u.user_id
                                OR al.target_type::text = 'APP_USER'::text AND al.target_id = u.user_id::text
                             ORDER BY al.occurred_at DESC, al.recorded_at DESC
                             LIMIT 1) se ON true
         CROSS JOIN LATERAL ( SELECT count(*) AS active_count
                              FROM app_user x
                              WHERE x.role_code::text = 'SUPER_ADMIN'::text
                                AND x.org_id IS NULL
                                AND x.status::text = 'ACTIVE'::text
                                AND x.deleted_at IS NULL) sa
WHERE u.org_id IS NULL
  AND u.role_code::text = 'SUPER_ADMIN'::text
  AND u.deleted_at IS NULL;

alter table super_admin_account_view
    owner to postgres;

grant delete, insert, select, update on super_admin_account_view to teamiz_app;

create view super_admin_organization_detail_view
            (org_id, display_code, name, lifecycle_status, warning_codes, display_status, created_at,
             total_cohort_count, planned_cohort_count, running_cohort_count, closed_cohort_count, cohort_id,
             cohort_name, cohort_status, cohort_class_count, cohort_trainee_count, cohort_start_date, cohort_end_date,
             trainee_membership_count, unique_trainee_user_count, active_session_count, active_session_as_of_at,
             cost_period_start_at, cost_period_end_at, cost_as_of_at, current_month_effective_cost, monthly_ai_budget,
             budget_utilization_rate, budget_status, currency_code, cost_aggregation_status, cost_stale,
             storage_total_bytes, previous_storage_total_bytes, storage_change_rate, storage_comparison_status,
             storage_as_of_at, storage_aggregation_status, storage_stale, active_operator_count, active_operator_names,
             pending_operator_invite_count, operator_bootstrap_status, operator_primary_action, can_invite_operator,
             current_policy_id, policy_version, policy_effective_from, retention_days, default_disclosure_scope)
as
SELECT o.org_id,
       o.display_code::character varying(30)                                                         AS display_code,
       o.name,
       o.status::character varying(30)                                                               AS lifecycle_status,
       array_remove(ARRAY [
                        CASE
                            WHEN COALESCE(opx.active_count, 0) = 0 THEN 'ORGANIZATION_OPERATOR_EMPTY'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN pol.monthly_ai_budget IS NOT NULL AND
                                 COALESCE(us.effective_cost, 0::numeric) > pol.monthly_ai_budget THEN 'BUDGET_EXCEEDED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN us.aggregation_status IS NOT NULL AND (us.aggregation_status::text <> ALL
                                                                        (ARRAY ['COMPLETE'::character varying::text, 'SUCCESS'::character varying::text]))
                                THEN 'USAGE_AGGREGATION_INCOMPLETE'::text
                            ELSE NULL::text
                            END], NULL::text)                                                        AS warning_codes,
       CASE
           WHEN o.status::text <> 'ACTIVE'::text THEN o.status
           WHEN COALESCE(opx.active_count, 0) = 0 THEN 'OPERATOR_REQUIRED'::character varying
           WHEN pol.monthly_ai_budget IS NOT NULL AND COALESCE(us.effective_cost, 0::numeric) > pol.monthly_ai_budget
               THEN 'BUDGET_EXCEEDED'::character varying
           ELSE 'ACTIVE'::character varying
           END::character varying(30)                                                                AS display_status,
       o.created_at,
       COALESCE(cx.total_count, 0)                                                                   AS total_cohort_count,
       COALESCE(cx.planned_count, 0)                                                                 AS planned_cohort_count,
       COALESCE(cx.running_count, 0)                                                                 AS running_cohort_count,
       COALESCE(cx.closed_count, 0)                                                                  AS closed_cohort_count,
       c.cohort_id,
       c.name                                                                                        AS cohort_name,
       c.status                                                                                      AS cohort_status,
       COALESCE(cc.class_count, 0)                                                                   AS cohort_class_count,
       COALESCE(ct.trainee_count, 0)                                                                 AS cohort_trainee_count,
       c.start_date                                                                                  AS cohort_start_date,
       c.end_date                                                                                    AS cohort_end_date,
       COALESCE(ot.trainee_membership_count, 0)                                                      AS trainee_membership_count,
       COALESCE(ot.unique_trainee_user_count, 0)                                                     AS unique_trainee_user_count,
       COALESCE(sess.active_session_count, 0)                                                        AS active_session_count,
       CURRENT_TIMESTAMP                                                                             AS active_session_as_of_at,
       us.period_start_at                                                                            AS cost_period_start_at,
       us.period_end_at                                                                              AS cost_period_end_at,
       us.as_of_at                                                                                   AS cost_as_of_at,
       us.effective_cost                                                                             AS current_month_effective_cost,
       pol.monthly_ai_budget,
       CASE
           WHEN pol.monthly_ai_budget IS NULL OR pol.monthly_ai_budget = 0::numeric THEN NULL::numeric
           ELSE COALESCE(us.effective_cost, 0::numeric) / pol.monthly_ai_budget
           END::numeric(9, 4)                                                                        AS budget_utilization_rate,
       CASE
           WHEN pol.monthly_ai_budget IS NULL THEN 'NOT_CONFIGURED'::text
           WHEN COALESCE(us.effective_cost, 0::numeric) > pol.monthly_ai_budget THEN 'EXCEEDED'::text
           WHEN COALESCE(us.effective_cost, 0::numeric) >= (pol.monthly_ai_budget * 0.8) THEN 'WARNING'::text
           ELSE 'NORMAL'::text
           END::character varying(30)                                                                AS budget_status,
       COALESCE(us.currency_code, pol.currency_code, 'USD'::character varying)::character varying(3) AS currency_code,
       COALESCE(us.aggregation_status, 'NO_DATA'::character varying)::character varying(20)          AS cost_aggregation_status,
       COALESCE(us.as_of_at < (CURRENT_TIMESTAMP - '24:00:00'::interval), false)                     AS cost_stale,
       st.current_bytes                                                                              AS storage_total_bytes,
       st.previous_bytes                                                                             AS previous_storage_total_bytes,
       CASE
           WHEN st.previous_bytes IS NULL OR st.previous_bytes = 0 THEN NULL::numeric
           ELSE (st.current_bytes - st.previous_bytes)::numeric / st.previous_bytes::numeric
           END::numeric(9, 4)                                                                        AS storage_change_rate,
       CASE
           WHEN st.previous_bytes IS NULL THEN 'NO_BASELINE'::text
           WHEN st.previous_bytes = 0 THEN 'ZERO_BASELINE'::text
           ELSE 'COMPARABLE'::text
           END::character varying(30)                                                                AS storage_comparison_status,
       st.current_at                                                                                 AS storage_as_of_at,
       CASE
           WHEN st.current_at IS NULL THEN 'NO_DATA'::text
           ELSE 'COMPLETE'::text
           END::character varying(20)                                                                AS storage_aggregation_status,
       COALESCE(st.current_at < (CURRENT_TIMESTAMP - '24:00:00'::interval), false)                   AS storage_stale,
       COALESCE(opx.active_count, 0)                                                                 AS active_operator_count,
       COALESCE(opx.active_names, ARRAY []::text[]::character varying[])::text[]                     AS active_operator_names,
       COALESCE(inv.pending_count, 0)                                                                AS pending_operator_invite_count,
       CASE
           WHEN COALESCE(opx.active_count, 0) > 0 THEN 'COMPLETED'::text
           WHEN COALESCE(inv.pending_count, 0) > 0 THEN 'INVITATION_PENDING'::text
           ELSE 'INVITE_REQUIRED'::text
           END::character varying(30)                                                                AS operator_bootstrap_status,
       CASE
           WHEN COALESCE(opx.active_count, 0) > 0 THEN NULL::text
           WHEN COALESCE(inv.pending_count, 0) > 0 THEN 'VIEW_INVITATION'::text
           ELSE 'INVITE_OPERATOR'::text
           END::character varying(40)                                                                AS operator_primary_action,
       o.status::text = 'ACTIVE'::text                                                               AS can_invite_operator,
       pol.policy_id                                                                                 AS current_policy_id,
       pol.policy_version,
       pol.effective_from                                                                            AS policy_effective_from,
       pol.retention_days,
       pol.default_disclosure_scope::character varying(30)                                           AS default_disclosure_scope
FROM organization o
         LEFT JOIN cohort c ON c.org_id = o.org_id AND c.deleted_at IS NULL
         LEFT JOIN LATERAL ( SELECT count(*)::integer                                                                                                AS total_count,
                                    count(*) FILTER (WHERE x.status::text = 'PLANNED'::text)::integer                                                AS planned_count,
                                    count(*) FILTER (WHERE x.status::text = ANY
                                                           (ARRAY ['ACTIVE'::character varying::text, 'RUNNING'::character varying::text]))::integer AS running_count,
                                    count(*) FILTER (WHERE x.status::text = 'CLOSED'::text)::integer                                                 AS closed_count
                             FROM cohort x
                             WHERE x.org_id = o.org_id
                               AND x.deleted_at IS NULL) cx ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer AS class_count
                             FROM class x
                             WHERE x.cohort_id = c.cohort_id
                               AND x.deleted_at IS NULL) cc ON true
         LEFT JOIN LATERAL ( SELECT count(DISTINCT x.user_id)::integer AS trainee_count
                             FROM cohort_member x
                             WHERE x.cohort_id = c.cohort_id
                               AND x.status::text = 'ACTIVE'::text
                               AND x.left_at IS NULL) ct ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer                  AS trainee_membership_count,
                                    count(DISTINCT x.user_id)::integer AS unique_trainee_user_count
                             FROM cohort_member x
                             WHERE x.org_id = o.org_id
                               AND x.status::text = 'ACTIVE'::text
                               AND x.left_at IS NULL) ot ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer AS active_session_count
                             FROM assessment_session s
                                      JOIN measurement_attempt ma ON ma.attempt_id = s.attempt_id
                             WHERE ma.org_id = o.org_id
                               AND (s.status::text = ANY
                                    (ARRAY ['IN_PROGRESS'::character varying::text, 'PAUSED'::character varying::text]))) sess
                   ON true
         LEFT JOIN LATERAL ( SELECT p.policy_id,
                                    p.org_id,
                                    p.policy_version,
                                    p.monthly_ai_budget,
                                    p.currency_code,
                                    p.monthly_token_limit,
                                    p.storage_limit_bytes,
                                    p.retention_days,
                                    p.default_disclosure_scope,
                                    p.code_session_tier_code,
                                    p.allow_manager_invite,
                                    p.allow_data_export,
                                    p.allow_zip_submission,
                                    p.allow_github_integration,
                                    p.enable_big_project_contribution_analysis,
                                    p.effective_from,
                                    p.effective_to,
                                    p.status,
                                    p.created_by,
                                    p.created_at,
                                    p.updated_by,
                                    p.updated_at
                             FROM organization_policy p
                             WHERE p.org_id = o.org_id
                               AND p.status::text = 'ACTIVE'::text
                               AND p.effective_from <= CURRENT_TIMESTAMP
                               AND (p.effective_to IS NULL OR p.effective_to > CURRENT_TIMESTAMP)
                             ORDER BY p.policy_version DESC, p.effective_from DESC
                             LIMIT 1) pol ON true
         LEFT JOIN LATERAL ( SELECT x.usage_snapshot_id,
                                    x.org_id,
                                    x.period_type,
                                    x.period_start_at,
                                    x.period_end_at,
                                    x.as_of_at,
                                    x.active_trainee_count,
                                    x.completed_session_count,
                                    x.grading_execution_count,
                                    x.published_report_count,
                                    x.ai_call_count,
                                    x.input_token_count,
                                    x.output_token_count,
                                    x.cached_token_count,
                                    x.unpriced_call_count,
                                    x.unpriced_input_token_count,
                                    x.unpriced_output_token_count,
                                    x.cost_completeness_status,
                                    x.estimated_cost,
                                    x.actual_cost,
                                    x.effective_cost,
                                    x.currency_code,
                                    x.aggregation_status,
                                    x.failure_stage,
                                    x.failure_code,
                                    x.failure_reason,
                                    x.failed_at,
                                    x.is_retryable,
                                    x.source_watermark,
                                    x.calculation_version,
                                    x.created_at
                             FROM organization_usage_snapshot x
                             WHERE x.org_id = o.org_id
                             ORDER BY x.period_end_at DESC, x.as_of_at DESC, x.created_at DESC
                             LIMIT 1) us ON true
         LEFT JOIN LATERAL ( WITH batches AS (SELECT x.measurement_batch_id,
                                                     max(x.captured_at)                                     AS captured_at,
                                                     sum(x.used_bytes)::bigint                              AS total_bytes,
                                                     dense_rank() OVER (ORDER BY (max(x.captured_at)) DESC) AS rn
                                              FROM storage_usage_snapshot x
                                              WHERE x.org_id = o.org_id
                                              GROUP BY x.measurement_batch_id)
                             SELECT max(batches.total_bytes) FILTER (WHERE batches.rn = 1) AS current_bytes,
                                    max(batches.captured_at) FILTER (WHERE batches.rn = 1) AS current_at,
                                    max(batches.total_bytes) FILTER (WHERE batches.rn = 2) AS previous_bytes
                             FROM batches) st ON true
         LEFT JOIN LATERAL ( SELECT count(*) FILTER (WHERE u.status::text = 'ACTIVE'::text)::integer                 AS active_count,
                                    array_agg(u.name ORDER BY u.name)
                                    FILTER (WHERE u.status::text = 'ACTIVE'::text)                                   AS active_names
                             FROM app_user u
                             WHERE u.org_id = o.org_id
                               AND u.role_code::text = 'OPERATOR'::text
                               AND u.deleted_at IS NULL) opx ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer AS pending_count
                             FROM user_invitation ui
                             WHERE ui.org_id = o.org_id
                               AND ui.target_role_code::text = 'OPERATOR'::text
                               AND (ui.status::text = ANY
                                    (ARRAY ['PENDING'::character varying::text, 'SENT'::character varying::text, 'FAILED'::character varying::text]))
                               AND ui.accepted_at IS NULL
                               AND ui.cancelled_at IS NULL) inv ON true
WHERE o.deleted_at IS NULL;

alter table super_admin_organization_detail_view
    owner to postgres;

grant delete, insert, select, update on super_admin_organization_detail_view to teamiz_app;

create view super_admin_organization_list_view
            (platform_total_organization_count, platform_active_organization_count,
             platform_suspended_organization_count, platform_deletion_pending_organization_count,
             platform_unique_trainee_user_count, platform_active_session_count, platform_current_month_effective_cost,
             platform_active_policy_budget_sum, platform_cost_change_rate, platform_cost_failure_organization_count,
             platform_total_storage_bytes, platform_storage_change_rate, platform_average_storage_bytes, org_id, name,
             normalized_name, currency_code, cohort_count, unique_trainee_user_count, manager_count,
             current_month_effective_cost, active_operator_count, pending_operator_invite_count, operator_display,
             lifecycle_status, warning_codes, display_status, is_new, created_at, aggregation_as_of_at,
             aggregation_status, calculation_version, stale)
as
SELECT count(*) OVER ()::integer                                                                                                  AS platform_total_organization_count,
       count(*) FILTER (WHERE o.status::text = 'ACTIVE'::text) OVER ()::integer                                                   AS platform_active_organization_count,
       count(*) FILTER (WHERE o.status::text = 'SUSPENDED'::text) OVER ()::integer                                                AS platform_suspended_organization_count,
       count(*)
       FILTER (WHERE o.status::text = 'DELETION_PENDING'::text) OVER ()::integer                                                  AS platform_deletion_pending_organization_count,
       sum(COALESCE(trainee.unique_count, 0)) OVER ()::integer                                                                    AS platform_unique_trainee_user_count,
       sum(COALESCE(sess.active_count, 0)) OVER ()::integer                                                                       AS platform_active_session_count,
       sum(COALESCE(us.effective_cost, 0::numeric)) OVER ()::numeric(18, 6)                                                       AS platform_current_month_effective_cost,
       sum(COALESCE(pol.monthly_ai_budget, 0::numeric)) OVER ()::numeric(18, 6)                                                   AS platform_active_policy_budget_sum,
       CASE
           WHEN sum(COALESCE(prev.effective_cost, 0::numeric)) OVER () = 0::numeric THEN NULL::numeric
           ELSE (sum(COALESCE(us.effective_cost, 0::numeric)) OVER () -
                 sum(COALESCE(prev.effective_cost, 0::numeric)) OVER ()) /
                sum(COALESCE(prev.effective_cost, 0::numeric)) OVER ()
           END::numeric(9, 4)                                                                                                     AS platform_cost_change_rate,
       count(*) FILTER (WHERE us.aggregation_status::text <> ALL
                              (ARRAY ['COMPLETE'::character varying::text, 'SUCCESS'::character varying::text])) OVER ()::integer AS platform_cost_failure_organization_count,
       sum(COALESCE(st.total_bytes, 0::bigint)) OVER ()::bigint                                                                   AS platform_total_storage_bytes,
       NULL::numeric(9, 4)                                                                                                        AS platform_storage_change_rate,
       avg(COALESCE(st.total_bytes, 0::bigint)) OVER ()::numeric(18, 2)                                                           AS platform_average_storage_bytes,
       o.org_id,
       o.name,
       o.normalized_name,
       COALESCE(us.currency_code, pol.currency_code,
                'USD'::character varying)::character varying(3)                                                                   AS currency_code,
       COALESCE(coh.cohort_count, 0)                                                                                              AS cohort_count,
       COALESCE(trainee.unique_count, 0)                                                                                          AS unique_trainee_user_count,
       COALESCE(mgr.manager_count, 0)                                                                                             AS manager_count,
       COALESCE(us.effective_cost, 0::numeric)::numeric(18, 6)                                                                    AS current_month_effective_cost,
       COALESCE(op.active_count, 0)                                                                                               AS active_operator_count,
       COALESCE(inv.pending_count, 0)                                                                                             AS pending_operator_invite_count,
       CASE
           WHEN COALESCE(op.active_count, 0) > 0 THEN array_to_string(op.names, ', '::text)
           WHEN COALESCE(inv.pending_count, 0) > 0 THEN '초대 대기'::text
           ELSE '미배정'::text
           END                                                                                                                    AS operator_display,
       o.status::character varying(30)                                                                                            AS lifecycle_status,
       array_remove(ARRAY [
                        CASE
                            WHEN COALESCE(op.active_count, 0) = 0 THEN 'ORGANIZATION_OPERATOR_EMPTY'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN pol.monthly_ai_budget IS NOT NULL AND
                                 COALESCE(us.effective_cost, 0::numeric) > pol.monthly_ai_budget THEN 'BUDGET_EXCEEDED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN us.aggregation_status IS NOT NULL AND (us.aggregation_status::text <> ALL
                                                                        (ARRAY ['COMPLETE'::character varying::text, 'SUCCESS'::character varying::text]))
                                THEN 'USAGE_AGGREGATION_INCOMPLETE'::text
                            ELSE NULL::text
                            END],
                    NULL::text)                                                                                                   AS warning_codes,
       CASE
           WHEN o.status::text = 'DELETION_PENDING'::text THEN 'DELETION_PENDING'::text
           WHEN o.status::text = 'SUSPENDED'::text THEN 'SUSPENDED'::text
           WHEN COALESCE(op.active_count, 0) = 0 THEN 'ORGANIZATION_OPERATOR_EMPTY'::text
           WHEN pol.monthly_ai_budget IS NOT NULL AND COALESCE(us.effective_cost, 0::numeric) > pol.monthly_ai_budget
               THEN 'BUDGET_EXCEEDED'::text
           ELSE 'ACTIVE'::text
           END::character varying(30)                                                                                             AS display_status,
       o.created_at >= (CURRENT_TIMESTAMP - '30 days'::interval)                                                                  AS is_new,
       o.created_at,
       COALESCE(us.as_of_at, CURRENT_TIMESTAMP)                                                                                   AS aggregation_as_of_at,
       CASE
           WHEN us.usage_snapshot_id IS NULL THEN 'PARTIAL'::character varying
           ELSE COALESCE(us.aggregation_status, 'PARTIAL'::character varying)
           END::character varying(20)                                                                                             AS aggregation_status,
       COALESCE(us.calculation_version, 1)                                                                                        AS calculation_version,
       COALESCE(us.as_of_at < (CURRENT_TIMESTAMP - '24:00:00'::interval),
                false)                                                                                                            AS stale
FROM organization o
         LEFT JOIN LATERAL ( SELECT count(*)::integer AS cohort_count
                             FROM cohort x
                             WHERE x.org_id = o.org_id
                               AND x.deleted_at IS NULL) coh ON true
         LEFT JOIN LATERAL ( SELECT count(DISTINCT x.user_id)::integer AS unique_count
                             FROM cohort_member x
                             WHERE x.org_id = o.org_id
                               AND x.status::text = 'ACTIVE'::text
                               AND x.left_at IS NULL) trainee ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer AS manager_count
                             FROM app_user u
                             WHERE u.org_id = o.org_id
                               AND u.role_code::text = 'MANAGER'::text
                               AND u.status::text = 'ACTIVE'::text
                               AND u.deleted_at IS NULL) mgr ON true
         LEFT JOIN LATERAL ( SELECT count(*) FILTER (WHERE u.status::text = 'ACTIVE'::text)::integer                 AS active_count,
                                    array_agg(u.name ORDER BY u.name)
                                    FILTER (WHERE u.status::text = 'ACTIVE'::text)                                   AS names
                             FROM app_user u
                             WHERE u.org_id = o.org_id
                               AND u.role_code::text = 'OPERATOR'::text
                               AND u.deleted_at IS NULL) op ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer AS pending_count
                             FROM user_invitation ui
                             WHERE ui.org_id = o.org_id
                               AND ui.target_role_code::text = 'OPERATOR'::text
                               AND (ui.status::text = ANY
                                    (ARRAY ['PENDING'::character varying::text, 'SENT'::character varying::text, 'FAILED'::character varying::text]))
                               AND ui.accepted_at IS NULL
                               AND ui.cancelled_at IS NULL) inv ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer AS active_count
                             FROM assessment_session s
                                      JOIN measurement_attempt ma ON ma.attempt_id = s.attempt_id
                             WHERE ma.org_id = o.org_id
                               AND (s.status::text = ANY
                                    (ARRAY ['IN_PROGRESS'::character varying::text, 'PAUSED'::character varying::text]))) sess
                   ON true
         LEFT JOIN LATERAL ( SELECT x.policy_id,
                                    x.org_id,
                                    x.policy_version,
                                    x.monthly_ai_budget,
                                    x.currency_code,
                                    x.monthly_token_limit,
                                    x.storage_limit_bytes,
                                    x.retention_days,
                                    x.default_disclosure_scope,
                                    x.code_session_tier_code,
                                    x.allow_manager_invite,
                                    x.allow_data_export,
                                    x.allow_zip_submission,
                                    x.allow_github_integration,
                                    x.enable_big_project_contribution_analysis,
                                    x.effective_from,
                                    x.effective_to,
                                    x.status,
                                    x.created_by,
                                    x.created_at,
                                    x.updated_by,
                                    x.updated_at
                             FROM organization_policy x
                             WHERE x.org_id = o.org_id
                               AND x.status::text = 'ACTIVE'::text
                             ORDER BY x.policy_version DESC, x.effective_from DESC
                             LIMIT 1) pol ON true
         LEFT JOIN LATERAL ( SELECT x.usage_snapshot_id,
                                    x.org_id,
                                    x.period_type,
                                    x.period_start_at,
                                    x.period_end_at,
                                    x.as_of_at,
                                    x.active_trainee_count,
                                    x.completed_session_count,
                                    x.grading_execution_count,
                                    x.published_report_count,
                                    x.ai_call_count,
                                    x.input_token_count,
                                    x.output_token_count,
                                    x.cached_token_count,
                                    x.unpriced_call_count,
                                    x.unpriced_input_token_count,
                                    x.unpriced_output_token_count,
                                    x.cost_completeness_status,
                                    x.estimated_cost,
                                    x.actual_cost,
                                    x.effective_cost,
                                    x.currency_code,
                                    x.aggregation_status,
                                    x.failure_stage,
                                    x.failure_code,
                                    x.failure_reason,
                                    x.failed_at,
                                    x.is_retryable,
                                    x.source_watermark,
                                    x.calculation_version,
                                    x.created_at
                             FROM organization_usage_snapshot x
                             WHERE x.org_id = o.org_id
                             ORDER BY x.period_end_at DESC, x.as_of_at DESC
                             LIMIT 1) us ON true
         LEFT JOIN LATERAL ( SELECT x.effective_cost
                             FROM organization_usage_snapshot x
                             WHERE x.org_id = o.org_id
                               AND us.period_start_at IS NOT NULL
                               AND x.period_end_at <= us.period_start_at
                             ORDER BY x.period_end_at DESC, x.as_of_at DESC
                             LIMIT 1) prev ON true
         LEFT JOIN LATERAL ( SELECT sum(x.used_bytes)::bigint AS total_bytes
                             FROM storage_usage_snapshot x
                             WHERE x.org_id = o.org_id
                               AND x.measurement_batch_id = ((SELECT y.measurement_batch_id
                                                              FROM storage_usage_snapshot y
                                                              WHERE y.org_id = o.org_id
                                                              ORDER BY y.captured_at DESC, y.created_at DESC
                                                              LIMIT 1))) st ON true
WHERE o.deleted_at IS NULL;

alter table super_admin_organization_list_view
    owner to postgres;

grant delete, insert, select, update on super_admin_organization_list_view to teamiz_app;

create view super_admin_organization_operator_view
            (org_id, row_type, email, display_status, sort_at, user_id, name, account_status, is_email_verified,
             last_login_at, login_blocked_until, inactivated_at, inactivated_reason_code, inactivated_reason,
             invitation_id, target_role_code, invitation_status, invited_at, sent_at, expired_at, failure_stage,
             failure_code, current_token_id, current_token_purpose, current_token_expires_at, current_token_used_at,
             current_token_invalidated_at, current_token_invalidated_reason, can_suspend, can_reactivate, can_resend,
             can_cancel, action_block_code, active_operator_count, pending_operator_invitation_count,
             operator_bootstrap_status)
as
WITH stats AS (SELECT o.org_id,
                      count(u.user_id) FILTER (WHERE u.status::text = 'ACTIVE'::text)::integer AS active_count,
                      (SELECT count(*)::integer AS count
                       FROM user_invitation ui
                       WHERE ui.org_id = o.org_id
                         AND ui.target_role_code::text = 'OPERATOR'::text
                         AND (ui.status::text = ANY
                              (ARRAY ['PENDING'::character varying::text, 'SENT'::character varying::text, 'FAILED'::character varying::text]))
                         AND ui.accepted_at IS NULL
                         AND ui.cancelled_at IS NULL)                                          AS pending_count
               FROM organization o
                        LEFT JOIN app_user u ON u.org_id = o.org_id AND u.role_code::text = 'OPERATOR'::text AND
                                                u.deleted_at IS NULL
               WHERE o.deleted_at IS NULL
               GROUP BY o.org_id),
     accounts AS (SELECT u.org_id,
                         'ACCOUNT'::text                                        AS row_type,
                         u.email::text                                          AS email,
                         CASE
                             WHEN u.status::text = 'ACTIVE'::text THEN 'ACTIVE'::character varying
                             ELSE u.status
                             END::text                                          AS display_status,
                         COALESCE(u.last_login_at, u.created_at)                AS sort_at,
                         u.user_id,
                         u.name,
                         u.status                                               AS account_status,
                         u.is_email_verified,
                         u.last_login_at,
                         u.login_blocked_until,
                         u.inactivated_at,
                         u.inactivated_reason_code,
                         u.inactivated_reason,
                         NULL::uuid                                             AS invitation_id,
                         NULL::text                                             AS target_role_code,
                         NULL::text                                             AS invitation_status,
                         NULL::timestamp with time zone                         AS invited_at,
                         NULL::timestamp with time zone                         AS sent_at,
                         NULL::timestamp with time zone                         AS expired_at,
                         NULL::text                                             AS failure_stage,
                         NULL::text                                             AS failure_code,
                         NULL::uuid                                             AS current_token_id,
                         NULL::text                                             AS current_token_purpose,
                         NULL::timestamp with time zone                         AS current_token_expires_at,
                         NULL::timestamp with time zone                         AS current_token_used_at,
                         NULL::timestamp with time zone                         AS current_token_invalidated_at,
                         NULL::text                                             AS current_token_invalidated_reason,
                         u.status::text = 'ACTIVE'::text AND s.active_count > 1 AS can_suspend,
                         u.status::text = 'INACTIVE'::text                      AS can_reactivate,
                         false                                                  AS can_resend,
                         false                                                  AS can_cancel,
                         CASE
                             WHEN u.status::text = 'ACTIVE'::text AND s.active_count <= 1 THEN 'LAST_OPERATOR'::text
                             ELSE NULL::text
                             END                                                AS action_block_code,
                         s.active_count                                         AS active_operator_count,
                         s.pending_count                                        AS pending_operator_invitation_count,
                         CASE
                             WHEN s.active_count > 0 THEN 'COMPLETED'::text
                             WHEN s.pending_count > 0 THEN 'INVITATION_PENDING'::text
                             ELSE 'INVITE_REQUIRED'::text
                             END                                                AS operator_bootstrap_status
                  FROM app_user u
                           JOIN stats s ON s.org_id = u.org_id
                  WHERE u.role_code::text = 'OPERATOR'::text
                    AND u.deleted_at IS NULL),
     invites AS (SELECT ui.org_id,
                        'INVITATION'::text                                 AS row_type,
                        ui.target_email::text                              AS email,
                        ui.status::text                                    AS display_status,
                        COALESCE(ui.sent_at, ui.invited_at)                AS sort_at,
                        NULL::uuid                                         AS user_id,
                        NULL::text                                         AS name,
                        NULL::text                                         AS account_status,
                        NULL::boolean                                      AS is_email_verified,
                        NULL::timestamp with time zone                     AS last_login_at,
                        NULL::timestamp with time zone                     AS login_blocked_until,
                        NULL::timestamp with time zone                     AS inactivated_at,
                        NULL::text                                         AS inactivated_reason_code,
                        NULL::text                                         AS inactivated_reason,
                        ui.invitation_id,
                        ui.target_role_code::text                          AS target_role_code,
                        ui.status::text                                    AS invitation_status,
                        ui.invited_at,
                        ui.sent_at,
                        ui.expired_at,
                        ui.failure_stage::text                             AS failure_stage,
                        ui.failure_code::text                              AS failure_code,
                        t.token_id                                         AS current_token_id,
                        t.purpose::text                                    AS current_token_purpose,
                        t.expires_at                                       AS current_token_expires_at,
                        t.used_at                                          AS current_token_used_at,
                        t.invalidated_at                                   AS current_token_invalidated_at,
                        t.invalidated_reason                               AS current_token_invalidated_reason,
                        false                                              AS can_suspend,
                        false                                              AS can_reactivate,
                        (ui.status::text = ANY
                         (ARRAY ['PENDING'::character varying::text, 'SENT'::character varying::text, 'FAILED'::character varying::text])) AND
                        ui.accepted_at IS NULL AND ui.cancelled_at IS NULL AS can_resend,
                        ui.accepted_at IS NULL AND ui.cancelled_at IS NULL AS can_cancel,
                        CASE
                            WHEN ui.accepted_at IS NOT NULL THEN 'ALREADY_ACCEPTED'::text
                            WHEN ui.cancelled_at IS NOT NULL THEN 'CANCELLED'::text
                            ELSE NULL::text
                            END                                            AS action_block_code,
                        s.active_count,
                        s.pending_count,
                        CASE
                            WHEN s.active_count > 0 THEN 'COMPLETED'::text
                            WHEN s.pending_count > 0 THEN 'INVITATION_PENDING'::text
                            ELSE 'INVITE_REQUIRED'::text
                            END                                            AS text
                 FROM user_invitation ui
                          JOIN stats s ON s.org_id = ui.org_id
                          LEFT JOIN LATERAL ( SELECT x.token_id,
                                                     x.org_id,
                                                     x.user_id,
                                                     x.invitation_id,
                                                     x.target_email,
                                                     x.target_email_normalized,
                                                     x.purpose,
                                                     x.token_hash,
                                                     x.payload,
                                                     x.issued_at,
                                                     x.expires_at,
                                                     x.used_at,
                                                     x.invalidated_at,
                                                     x.invalidated_reason,
                                                     x.replaced_by_token_id,
                                                     x.issued_by,
                                                     x.issued_request_id,
                                                     x.used_request_id,
                                                     x.created_at
                                              FROM one_time_token x
                                              WHERE x.token_id = ui.current_token_id
                                                 OR x.invitation_id = ui.invitation_id AND x.replaced_by_token_id IS NULL
                                              ORDER BY (x.token_id = ui.current_token_id) DESC, x.issued_at DESC
                                              LIMIT 1) t ON true
                 WHERE ui.target_role_code::text = 'OPERATOR'::text
                   AND ui.accepted_at IS NULL),
     z AS (SELECT accounts.org_id,
                  accounts.row_type,
                  accounts.email,
                  accounts.display_status,
                  accounts.sort_at,
                  accounts.user_id,
                  accounts.name,
                  accounts.account_status,
                  accounts.is_email_verified,
                  accounts.last_login_at,
                  accounts.login_blocked_until,
                  accounts.inactivated_at,
                  accounts.inactivated_reason_code,
                  accounts.inactivated_reason,
                  accounts.invitation_id,
                  accounts.target_role_code,
                  accounts.invitation_status,
                  accounts.invited_at,
                  accounts.sent_at,
                  accounts.expired_at,
                  accounts.failure_stage,
                  accounts.failure_code,
                  accounts.current_token_id,
                  accounts.current_token_purpose,
                  accounts.current_token_expires_at,
                  accounts.current_token_used_at,
                  accounts.current_token_invalidated_at,
                  accounts.current_token_invalidated_reason,
                  accounts.can_suspend,
                  accounts.can_reactivate,
                  accounts.can_resend,
                  accounts.can_cancel,
                  accounts.action_block_code,
                  accounts.active_operator_count,
                  accounts.pending_operator_invitation_count,
                  accounts.operator_bootstrap_status
           FROM accounts
           UNION ALL
           SELECT invites.org_id,
                  invites.row_type,
                  invites.email,
                  invites.display_status,
                  invites.sort_at,
                  invites.user_id,
                  invites.name,
                  invites.account_status,
                  invites.is_email_verified,
                  invites.last_login_at,
                  invites.login_blocked_until,
                  invites.inactivated_at,
                  invites.inactivated_reason_code,
                  invites.inactivated_reason,
                  invites.invitation_id,
                  invites.target_role_code,
                  invites.invitation_status,
                  invites.invited_at,
                  invites.sent_at,
                  invites.expired_at,
                  invites.failure_stage,
                  invites.failure_code,
                  invites.current_token_id,
                  invites.current_token_purpose,
                  invites.current_token_expires_at,
                  invites.current_token_used_at,
                  invites.current_token_invalidated_at,
                  invites.current_token_invalidated_reason,
                  invites.can_suspend,
                  invites.can_reactivate,
                  invites.can_resend,
                  invites.can_cancel,
                  invites.action_block_code,
                  invites.active_count,
                  invites.pending_count,
                  invites.text
           FROM invites)
SELECT org_id,
       row_type::character varying(20)                  AS row_type,
       email::character varying(320)                    AS email,
       display_status::character varying(30)            AS display_status,
       sort_at,
       user_id,
       name::character varying(100)                     AS name,
       account_status::character varying(30)            AS account_status,
       is_email_verified,
       last_login_at,
       login_blocked_until,
       inactivated_at,
       inactivated_reason_code::character varying(30)   AS inactivated_reason_code,
       inactivated_reason,
       invitation_id,
       target_role_code::character varying(30)          AS target_role_code,
       invitation_status::character varying(30)         AS invitation_status,
       invited_at,
       sent_at,
       expired_at,
       failure_stage::character varying(100)            AS failure_stage,
       failure_code::character varying(100)             AS failure_code,
       current_token_id,
       current_token_purpose::character varying(50)     AS current_token_purpose,
       current_token_expires_at,
       current_token_used_at,
       current_token_invalidated_at,
       current_token_invalidated_reason,
       can_suspend,
       can_reactivate,
       can_resend,
       can_cancel,
       action_block_code::character varying(100)        AS action_block_code,
       active_operator_count,
       pending_operator_invitation_count,
       operator_bootstrap_status::character varying(30) AS operator_bootstrap_status
FROM z;

alter table super_admin_organization_operator_view
    owner to postgres;

grant delete, insert, select, update on super_admin_organization_operator_view to teamiz_app;

create view super_admin_organization_settings_view
            (org_id, name, lifecycle_status, current_policy_id, policy_version, policy_effective_from,
             monthly_ai_budget, monthly_token_limit, retention_days, default_disclosure_scope,
             question_generation_tier_code, summary_tier_code, allow_github_integration, allow_zip_submission,
             enable_big_project_contribution_analysis, currency_code, storage_limit_bytes, allow_manager_invite,
             allow_data_export, can_suspend, can_activate, can_request_deletion, policy_update_allowed)
as
SELECT o.org_id,
       o.name,
       o.status::character varying(30)                                                                        AS lifecycle_status,
       p.policy_id                                                                                            AS current_policy_id,
       p.policy_version,
       p.effective_from                                                                                       AS policy_effective_from,
       p.monthly_ai_budget,
       p.monthly_token_limit,
       p.retention_days,
       p.default_disclosure_scope::character varying(30)                                                      AS default_disclosure_scope,
       NULL::character varying(30)                                                                            AS question_generation_tier_code,
       NULL::character varying(30)                                                                            AS summary_tier_code,
       p.allow_github_integration,
       p.allow_zip_submission,
       p.enable_big_project_contribution_analysis,
       COALESCE(p.currency_code, 'USD'::character varying)::character varying(3)                              AS currency_code,
       p.storage_limit_bytes,
       p.allow_manager_invite,
       p.allow_data_export,
       o.status::text = 'ACTIVE'::text                                                                        AS can_suspend,
       o.status::text = 'SUSPENDED'::text                                                                     AS can_activate,
       o.status::text = ANY
       (ARRAY ['ACTIVE'::character varying::text, 'SUSPENDED'::character varying::text])                      AS can_request_deletion,
       o.status::text <> 'DELETED'::text                                                                      AS policy_update_allowed
FROM organization o
         LEFT JOIN LATERAL ( SELECT x.policy_id,
                                    x.org_id,
                                    x.policy_version,
                                    x.monthly_ai_budget,
                                    x.currency_code,
                                    x.monthly_token_limit,
                                    x.storage_limit_bytes,
                                    x.retention_days,
                                    x.default_disclosure_scope,
                                    x.code_session_tier_code,
                                    x.allow_manager_invite,
                                    x.allow_data_export,
                                    x.allow_zip_submission,
                                    x.allow_github_integration,
                                    x.enable_big_project_contribution_analysis,
                                    x.effective_from,
                                    x.effective_to,
                                    x.status,
                                    x.created_by,
                                    x.created_at,
                                    x.updated_by,
                                    x.updated_at
                             FROM organization_policy x
                             WHERE x.org_id = o.org_id
                               AND x.status::text = 'ACTIVE'::text
                               AND x.effective_from <= CURRENT_TIMESTAMP
                               AND (x.effective_to IS NULL OR x.effective_to > CURRENT_TIMESTAMP)
                             ORDER BY x.policy_version DESC, x.effective_from DESC
                             LIMIT 1) p ON true
WHERE o.deleted_at IS NULL;

alter table super_admin_organization_settings_view
    owner to postgres;

grant delete, insert, select, update on super_admin_organization_settings_view to teamiz_app;

create view super_admin_organization_usage_view
            (org_id, period_start_at, period_end_at, as_of_at, currency_code, code_artifact_storage_bytes,
             session_transcript_storage_bytes, score_evidence_storage_bytes, report_export_storage_bytes,
             display_storage_total_bytes, policy_storage_total_bytes, active_trainee_count, completed_session_count,
             grading_execution_count, published_report_count, feature_code, model_id, ai_call_count, input_token_count,
             output_token_count, cached_token_count, effective_cost, unpriced_call_count, unpriced_input_token_count,
             unpriced_output_token_count, cost_completeness_status, monthly_ai_budget, budget_utilization_rate,
             budget_remaining, budget_status, previous_period_effective_cost, cost_change_rate, comparison_status,
             aggregation_status, failure_stage, failure_code, last_successful_as_of_at, stale)
as
WITH latest AS (SELECT DISTINCT ON (organization_usage_snapshot.org_id) organization_usage_snapshot.usage_snapshot_id,
                                                                        organization_usage_snapshot.org_id,
                                                                        organization_usage_snapshot.period_type,
                                                                        organization_usage_snapshot.period_start_at,
                                                                        organization_usage_snapshot.period_end_at,
                                                                        organization_usage_snapshot.as_of_at,
                                                                        organization_usage_snapshot.active_trainee_count,
                                                                        organization_usage_snapshot.completed_session_count,
                                                                        organization_usage_snapshot.grading_execution_count,
                                                                        organization_usage_snapshot.published_report_count,
                                                                        organization_usage_snapshot.ai_call_count,
                                                                        organization_usage_snapshot.input_token_count,
                                                                        organization_usage_snapshot.output_token_count,
                                                                        organization_usage_snapshot.cached_token_count,
                                                                        organization_usage_snapshot.unpriced_call_count,
                                                                        organization_usage_snapshot.unpriced_input_token_count,
                                                                        organization_usage_snapshot.unpriced_output_token_count,
                                                                        organization_usage_snapshot.cost_completeness_status,
                                                                        organization_usage_snapshot.estimated_cost,
                                                                        organization_usage_snapshot.actual_cost,
                                                                        organization_usage_snapshot.effective_cost,
                                                                        organization_usage_snapshot.currency_code,
                                                                        organization_usage_snapshot.aggregation_status,
                                                                        organization_usage_snapshot.failure_stage,
                                                                        organization_usage_snapshot.failure_code,
                                                                        organization_usage_snapshot.failure_reason,
                                                                        organization_usage_snapshot.failed_at,
                                                                        organization_usage_snapshot.is_retryable,
                                                                        organization_usage_snapshot.source_watermark,
                                                                        organization_usage_snapshot.calculation_version,
                                                                        organization_usage_snapshot.created_at
                FROM organization_usage_snapshot
                ORDER BY organization_usage_snapshot.org_id, organization_usage_snapshot.period_end_at DESC,
                         organization_usage_snapshot.as_of_at DESC, organization_usage_snapshot.created_at DESC),
     prev AS (SELECT l.org_id,
                     (SELECT x.effective_cost
                      FROM organization_usage_snapshot x
                      WHERE x.org_id = l.org_id
                        AND x.period_end_at <= l.period_start_at
                      ORDER BY x.period_end_at DESC, x.as_of_at DESC
                      LIMIT 1) AS effective_cost
              FROM latest l),
     succ AS (SELECT organization_usage_snapshot.org_id,
                     max(organization_usage_snapshot.as_of_at)
                     FILTER (WHERE organization_usage_snapshot.aggregation_status::text = ANY
                                   (ARRAY ['COMPLETE'::character varying::text, 'SUCCESS'::character varying::text])) AS last_successful_as_of_at
              FROM organization_usage_snapshot
              GROUP BY organization_usage_snapshot.org_id),
     policy AS (SELECT DISTINCT ON (organization_policy.org_id) organization_policy.policy_id,
                                                                organization_policy.org_id,
                                                                organization_policy.policy_version,
                                                                organization_policy.monthly_ai_budget,
                                                                organization_policy.currency_code,
                                                                organization_policy.monthly_token_limit,
                                                                organization_policy.storage_limit_bytes,
                                                                organization_policy.retention_days,
                                                                organization_policy.default_disclosure_scope,
                                                                organization_policy.code_session_tier_code,
                                                                organization_policy.allow_manager_invite,
                                                                organization_policy.allow_data_export,
                                                                organization_policy.allow_zip_submission,
                                                                organization_policy.allow_github_integration,
                                                                organization_policy.enable_big_project_contribution_analysis,
                                                                organization_policy.effective_from,
                                                                organization_policy.effective_to,
                                                                organization_policy.status,
                                                                organization_policy.created_by,
                                                                organization_policy.created_at,
                                                                organization_policy.updated_by,
                                                                organization_policy.updated_at
                FROM organization_policy
                WHERE organization_policy.status::text = 'ACTIVE'::text
                  AND organization_policy.effective_from <= CURRENT_TIMESTAMP
                  AND (organization_policy.effective_to IS NULL OR organization_policy.effective_to > CURRENT_TIMESTAMP)
                ORDER BY organization_policy.org_id, organization_policy.policy_version DESC,
                         organization_policy.effective_from DESC),
     storage_latest_batch AS (SELECT DISTINCT ON (storage_usage_snapshot.org_id) storage_usage_snapshot.org_id,
                                                                                 storage_usage_snapshot.measurement_batch_id,
                                                                                 storage_usage_snapshot.captured_at
                              FROM storage_usage_snapshot
                              ORDER BY storage_usage_snapshot.org_id, storage_usage_snapshot.captured_at DESC,
                                       storage_usage_snapshot.created_at DESC),
     st AS (SELECT s.org_id,
                   sum(s.used_bytes)
                   FILTER (WHERE s.storage_category::text = 'CODE_ARTIFACT'::text)::bigint                        AS code_artifact_storage_bytes,
                   sum(s.used_bytes)
                   FILTER (WHERE s.storage_category::text = 'SESSION_TRANSCRIPT'::text)::bigint                   AS session_transcript_storage_bytes,
                   sum(s.used_bytes)
                   FILTER (WHERE s.storage_category::text = 'SCORE_EVIDENCE'::text)::bigint                       AS score_evidence_storage_bytes,
                   sum(s.used_bytes)
                   FILTER (WHERE s.storage_category::text = 'REPORT_EXPORT'::text)::bigint                        AS report_export_storage_bytes,
                   sum(s.used_bytes)::bigint                                                                      AS display_storage_total_bytes
            FROM storage_usage_snapshot s
                     JOIN storage_latest_batch b
                          ON b.org_id = s.org_id AND b.measurement_batch_id = s.measurement_batch_id
            GROUP BY s.org_id),
     ab AS (SELECT a.org_id,
                   a.feature_code,
                   m.model_id,
                   count(*)::integer                                                                          AS ai_call_count,
                   sum(a.input_token_count)::bigint                                                           AS input_token_count,
                   sum(a.output_token_count)::bigint                                                          AS output_token_count,
                   sum(a.cached_token_count)::bigint                                                          AS cached_token_count,
                   sum(COALESCE(a.actual_cost, a.estimated_cost, 0::numeric))                                 AS effective_cost,
                   count(*) FILTER (WHERE a.pricing_status::text = 'UNPRICED'::text)::integer                 AS unpriced_call_count,
                   sum(a.input_token_count)
                   FILTER (WHERE a.pricing_status::text = 'UNPRICED'::text)::bigint                           AS unpriced_input_token_count,
                   sum(a.output_token_count)
                   FILTER (WHERE a.pricing_status::text = 'UNPRICED'::text)::bigint                           AS unpriced_output_token_count
            FROM ai_usage a
                     JOIN latest l ON l.org_id = a.org_id
                     LEFT JOIN ai_model m ON m.model_code::text = a.model_code::text
            WHERE a.occurred_at >= l.period_start_at
              AND a.occurred_at < l.period_end_at
            GROUP BY a.org_id, a.feature_code, m.model_id)
SELECT u.org_id,
       u.period_start_at,
       u.period_end_at,
       u.as_of_at,
       COALESCE(u.currency_code, p.currency_code, 'USD'::character varying)::character varying(3) AS currency_code,
       COALESCE(st.code_artifact_storage_bytes, 0::bigint)                                        AS code_artifact_storage_bytes,
       COALESCE(st.session_transcript_storage_bytes, 0::bigint)                                   AS session_transcript_storage_bytes,
       COALESCE(st.score_evidence_storage_bytes, 0::bigint)                                       AS score_evidence_storage_bytes,
       COALESCE(st.report_export_storage_bytes, 0::bigint)                                        AS report_export_storage_bytes,
       COALESCE(st.display_storage_total_bytes, 0::bigint)                                        AS display_storage_total_bytes,
       p.storage_limit_bytes                                                                      AS policy_storage_total_bytes,
       u.active_trainee_count,
       u.completed_session_count,
       u.grading_execution_count,
       u.published_report_count,
       ab.feature_code,
       ab.model_id,
       COALESCE(ab.ai_call_count::bigint, u.ai_call_count)                                        AS ai_call_count,
       COALESCE(ab.input_token_count, u.input_token_count)                                        AS input_token_count,
       COALESCE(ab.output_token_count, u.output_token_count)                                      AS output_token_count,
       COALESCE(ab.cached_token_count, u.cached_token_count)                                      AS cached_token_count,
       COALESCE(ab.effective_cost, u.effective_cost)::numeric(18, 6)                              AS effective_cost,
       COALESCE(ab.unpriced_call_count::bigint, u.unpriced_call_count)                            AS unpriced_call_count,
       COALESCE(ab.unpriced_input_token_count,
                u.unpriced_input_token_count)                                                     AS unpriced_input_token_count,
       COALESCE(ab.unpriced_output_token_count,
                u.unpriced_output_token_count)                                                    AS unpriced_output_token_count,
       u.cost_completeness_status,
       p.monthly_ai_budget,
       CASE
           WHEN p.monthly_ai_budget IS NULL OR p.monthly_ai_budget = 0::numeric THEN NULL::numeric
           ELSE u.effective_cost / p.monthly_ai_budget
           END::numeric(9, 4)                                                                     AS budget_utilization_rate,
       CASE
           WHEN p.monthly_ai_budget IS NULL THEN NULL::numeric
           ELSE p.monthly_ai_budget - u.effective_cost
           END::numeric(18, 6)                                                                    AS budget_remaining,
       CASE
           WHEN p.monthly_ai_budget IS NULL THEN 'NOT_CONFIGURED'::text
           WHEN u.effective_cost > p.monthly_ai_budget THEN 'EXCEEDED'::text
           WHEN u.effective_cost >= (p.monthly_ai_budget * 0.8) THEN 'WARNING'::text
           ELSE 'NORMAL'::text
           END::character varying(30)                                                             AS budget_status,
       prev.effective_cost                                                                        AS previous_period_effective_cost,
       CASE
           WHEN prev.effective_cost IS NULL OR prev.effective_cost = 0::numeric THEN NULL::numeric
           ELSE (u.effective_cost - prev.effective_cost) / prev.effective_cost
           END::numeric(9, 4)                                                                     AS cost_change_rate,
       CASE
           WHEN prev.effective_cost IS NULL THEN 'NO_BASELINE'::text
           WHEN prev.effective_cost = 0::numeric THEN 'ZERO_BASELINE'::text
           ELSE 'COMPARABLE'::text
           END::character varying(30)                                                             AS comparison_status,
       u.aggregation_status,
       u.failure_stage,
       u.failure_code,
       succ.last_successful_as_of_at,
       u.as_of_at < (CURRENT_TIMESTAMP - '24:00:00'::interval)                                    AS stale
FROM latest u
         LEFT JOIN policy p ON p.org_id = u.org_id
         LEFT JOIN prev ON prev.org_id = u.org_id
         LEFT JOIN succ ON succ.org_id = u.org_id
         LEFT JOIN st ON st.org_id = u.org_id
         LEFT JOIN ab ON ab.org_id = u.org_id;

alter table super_admin_organization_usage_view
    owner to postgres;

grant delete, insert, select, update on super_admin_organization_usage_view to teamiz_app;

create view super_admin_platform_model_settings_view
            (model_id, model_code, provider, provider_model_code, display_name, status, input_unit_price,
             output_unit_price, cached_input_unit_price, currency_code, price_unit_token_count, price_effective_from,
             price_completeness_status, pricing_complete, model_unavailable_reason_code, active_grading_policy_id,
             grading_policy_version, active_grading_model_id, active_calibration_version_id, calibration_status,
             calibration_target_org_count, calibration_pending_org_count, calibration_running_org_count,
             calibration_succeeded_org_count, calibration_failed_org_count, feature_code, tier_code, tier_policy_id,
             tier_model_id, unpriced_model_count)
as
SELECT m.model_id,
       m.model_code,
       m.provider,
       m.provider_model_code,
       m.display_name,
       m.status::character varying(30)                                                                    AS status,
       m.input_unit_price,
       m.output_unit_price,
       m.cached_input_unit_price,
       m.currency_code,
       m.price_unit_token_count,
       m.price_effective_from,
       CASE
           WHEN m.input_unit_price IS NULL OR m.output_unit_price IS NULL OR m.currency_code IS NULL THEN 'INCOMPLETE'::text
           ELSE 'COMPLETE'::text
           END::character varying(30)                                                                     AS price_completeness_status,
       m.input_unit_price IS NOT NULL AND m.output_unit_price IS NOT NULL AND
       m.currency_code IS NOT NULL                                                                        AS pricing_complete,
       CASE
           WHEN m.status::text <> 'ACTIVE'::text THEN 'MODEL_INACTIVE'::text
           WHEN m.input_unit_price IS NULL OR m.output_unit_price IS NULL THEN 'PRICE_INCOMPLETE'::text
           ELSE NULL::text
           END::character varying(100)                                                                    AS model_unavailable_reason_code,
       gp.grading_policy_id                                                                               AS active_grading_policy_id,
       gp.policy_version                                                                                  AS grading_policy_version,
       gp.model_id                                                                                        AS active_grading_model_id,
       cv.calibration_version_id                                                                          AS active_calibration_version_id,
       cv.status                                                                                          AS calibration_status,
       COALESCE(cs.target_count, 0)                                                                       AS calibration_target_org_count,
       COALESCE(cs.pending_count, 0)                                                                      AS calibration_pending_org_count,
       COALESCE(cs.running_count, 0)                                                                      AS calibration_running_org_count,
       COALESCE(cs.succeeded_count, 0)                                                                    AS calibration_succeeded_org_count,
       COALESCE(cs.failed_count, 0)                                                                       AS calibration_failed_org_count,
       tp.feature_code,
       tp.tier_code,
       tp.tier_policy_id,
       tp.model_id                                                                                        AS tier_model_id,
       count(*)
       FILTER (WHERE m.input_unit_price IS NULL OR m.output_unit_price IS NULL) OVER ()::integer          AS unpriced_model_count
FROM ai_model m
         LEFT JOIN LATERAL ( SELECT x.grading_policy_id,
                                    x.policy_version,
                                    x.model_id,
                                    x.status,
                                    x.effective_from,
                                    x.effective_to,
                                    x.changed_by,
                                    x.change_reason,
                                    x.created_at,
                                    x.updated_at
                             FROM platform_grading_model_policy x
                             WHERE x.status::text = 'ACTIVE'::text
                               AND x.effective_from <= CURRENT_TIMESTAMP
                               AND (x.effective_to IS NULL OR x.effective_to > CURRENT_TIMESTAMP)
                             ORDER BY x.policy_version DESC, x.effective_from DESC
                             LIMIT 1) gp ON true
         LEFT JOIN LATERAL ( SELECT x.calibration_version_id,
                                    x.grading_policy_id,
                                    x.version_code,
                                    x.status,
                                    x.started_at,
                                    x.completed_at,
                                    x.created_by,
                                    x.failure_code,
                                    x.failure_reason,
                                    x.failed_at,
                                    x.created_at,
                                    x.updated_at
                             FROM platform_grading_calibration_version x
                             WHERE x.grading_policy_id = gp.grading_policy_id
                             ORDER BY x.created_at DESC, x.version_code DESC
                             LIMIT 1) cv ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer                                                   AS target_count,
                                    count(*) FILTER (WHERE x.status::text = 'PENDING'::text)::integer   AS pending_count,
                                    count(*) FILTER (WHERE x.status::text = 'RUNNING'::text)::integer   AS running_count,
                                    count(*) FILTER (WHERE x.status::text = 'SUCCEEDED'::text)::integer AS succeeded_count,
                                    count(*) FILTER (WHERE x.status::text = 'FAILED'::text)::integer    AS failed_count
                             FROM organization_grading_calibration x
                             WHERE x.calibration_version_id = cv.calibration_version_id) cs ON true
         LEFT JOIN platform_ai_tier_model_policy tp ON tp.model_id = m.model_id AND tp.status::text = 'ACTIVE'::text AND
                                                       tp.effective_from <= CURRENT_TIMESTAMP AND
                                                       (tp.effective_to IS NULL OR tp.effective_to > CURRENT_TIMESTAMP);

alter table super_admin_platform_model_settings_view
    owner to postgres;

grant delete, insert, select, update on super_admin_platform_model_settings_view to teamiz_app;

create view trainee_home_round_view
            (trainee_user_id, org_id, cohort_id, class_id_at_round, class_name, team_id_at_round, team_number,
             team_name, project_id, project_name, project_category, assessment_round_id, round_no, round_name,
             round_status, curriculum_version_ids, curriculum_display_names, current_submission_id, submission_method,
             submission_status, submitted_at, submission_due_at, available_submission_methods, can_submit, can_resubmit,
             analysis_phase, latest_analysis_job_id, analysis_job_status, code_analysis_id, analysis_failure_code,
             prepared_problem_count, prepared_question_count, problem_scope, initial_attempt_id, initial_attempt_status,
             initial_terminal_reason_code, assessment_open_at, assessment_close_at, round_assessment_open_at,
             round_assessment_due_at, initial_session_id, initial_session_status, initial_terminal_at,
             latest_retry_attempt_id, latest_retry_status, latest_review_attempt_id, review_status, review_due_at,
             completed_review_count, review_source_report_id, review_source_report_snapshot_id, report_id,
             report_generation_status, report_publish_status, trainee_release_status, report_publish_mode,
             report_publish_not_before_at, explanation_status, latest_notification_reason_code,
             latest_notification_status, warning_codes, representative_status, default_action_code,
             action_unavailable_reason_code, manager_user_id, manager_name, aggregation_status, as_of_at,
             commit_email_status)
as
SELECT a.user_id                                                                    AS trainee_user_id,
       a.org_id,
       a.cohort_id,
       a.class_id                                                                   AS class_id_at_round,
       cl.name                                                                      AS class_name,
       a.team_id                                                                    AS team_id_at_round,
       tm.team_number,
       tm.name                                                                      AS team_name,
       a.project_id,
       p.name                                                                       AS project_name,
       p.project_category,
       a.assessment_round_id,
       a.round_no,
       a.round_name,
       a.round_status,
       COALESCE(cur.version_ids, ARRAY []::uuid[])                                  AS curriculum_version_ids,
       COALESCE(cur.names, ARRAY []::text[]::character varying[])::text[]           AS curriculum_display_names,
       a.source_submission_id                                                       AS current_submission_id,
       a.submission_method,
       a.submission_status,
       a.submitted_at,
       a.submission_due_at,
       array_remove(ARRAY ['GITHUB_URL'::text,
                        CASE
                            WHEN COALESCE(pol.allow_zip_submission, false) THEN 'ZIP_WITH_GITLOG'::text
                            ELSE NULL::text
                            END], NULL::text)::text                                 AS available_submission_methods,
       a.submission_due_at > CURRENT_TIMESTAMP AND a.primary_session_status IS NULL AS can_submit,
       a.source_submission_id IS NOT NULL AND a.submission_due_at > CURRENT_TIMESTAMP AND
       a.primary_session_status IS NULL                                             AS can_resubmit,
       CASE
           WHEN a.source_submission_id IS NULL THEN 'NOT_SUBMITTED'::text
           WHEN a.analysis_status::text = ANY
                (ARRAY ['QUEUED'::character varying, 'RUNNING'::character varying]::text[]) THEN 'ANALYZING'::text
           -- [2026-08-25] 결과 없는 성공(analysis_job SUCCEEDED · analysis_id NULL)에서 응시가
           -- terminal_reason_code='ANALYSIS_FAILED' 로 닫힌다. 그때 job 상태는 SUCCEEDED 로 남으므로
           -- 아래 두 줄만으로는 'COMPLETED' 가 나가 "분석은 됐다" 는 거짓을 말한다.
           -- 위 QUEUED/RUNNING 분기 '아래' 에 두는 것이 이 줄의 가드다 — 재제출로 새 분석이 도는
           -- 동안에는 직전 실패가 아니라 진행 중이 맞다.
           WHEN a.primary_terminal_reason_code::text = 'ANALYSIS_FAILED'::text THEN 'FAILED'::text
           WHEN a.analysis_status::text = 'FAILED'::text THEN 'FAILED'::text
           WHEN a.analysis_status::text = 'SUCCEEDED'::text THEN 'COMPLETED'::text
           ELSE 'WAITING'::text
           END                                                                      AS analysis_phase,
       a.analysis_job_id                                                            AS latest_analysis_job_id,
       a.analysis_status                                                            AS analysis_job_status,
       ma.code_analysis_id,
       CASE
           WHEN a.analysis_status::text = 'FAILED'::text
                OR (a.primary_terminal_reason_code::text = 'ANALYSIS_FAILED'::text
                    AND a.analysis_status::text IS DISTINCT FROM 'QUEUED'::text
                    AND a.analysis_status::text IS DISTINCT FROM 'RUNNING'::text)
               THEN 'ANALYSIS_FAILED'::text
           ELSE NULL::text
           END::character varying(100)                                              AS analysis_failure_code,
       COALESCE(probs.problem_count, 0)                                             AS prepared_problem_count,
       COALESCE(probs.question_count, 0)                                            AS prepared_question_count,
       probs.problem_scope::character varying(100)                                  AS problem_scope,
       a.primary_attempt_id                                                         AS initial_attempt_id,
       a.primary_attempt_status                                                     AS initial_attempt_status,
       a.primary_terminal_reason_code                                               AS initial_terminal_reason_code,
       a.primary_assessment_open_at                                                 AS assessment_open_at,
       a.primary_assessment_close_at                                                AS assessment_close_at,
       r.assessment_open_at                                                         AS round_assessment_open_at,
       r.assessment_due_at                                                          AS round_assessment_due_at,
       a.primary_session_id                                                         AS initial_session_id,
       a.primary_session_status                                                     AS initial_session_status,
       ma.terminal_at                                                               AS initial_terminal_at,
       a.latest_retry_attempt_id,
       a.latest_retry_status,
       a.latest_review_attempt_id,
       a.latest_review_status                                                       AS review_status,
       rev.review_due_at,
       COALESCE(rvc.completed_count, 0)                                             AS completed_review_count,
       rev.review_source_report_id,
       rev.review_source_report_snapshot_id,
       rpt.report_id,
       gr.status                                                                    AS report_generation_status,
       CASE
           WHEN rpt.published_at IS NOT NULL THEN 'PUBLISHED'::text
           WHEN gr.status::text = ANY
                (ARRAY ['QUEUED'::character varying, 'RUNNING'::character varying, 'RETRYING'::character varying]::text[])
               THEN 'GENERATING'::text
           ELSE 'NOT_PUBLISHED'::text
           END::character varying(100)                                              AS report_publish_status,
       rpt.trainee_release_status,
       r.report_publish_mode,
       r.report_publish_not_before_at,
       CASE
           WHEN rs.snapshot_id IS NULL THEN 'UNAVAILABLE'::text
           WHEN rs.completion_status::text = 'FULL'::text THEN 'AVAILABLE'::text
           ELSE 'PARTIAL'::text
           END::character varying(100)                                              AS explanation_status,
       notify.reason_code                                                           AS latest_notification_reason_code,
       notify.status::character varying(100)                                        AS latest_notification_status,
       array_remove(ARRAY [
                        CASE
                            WHEN a.submission_deadline_status::text = 'MISSED'::text THEN 'SUBMISSION_DEADLINE_PASSED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN a.analysis_status::text = 'FAILED'::text
                                 OR (a.primary_terminal_reason_code::text = 'ANALYSIS_FAILED'::text
                                     AND a.analysis_status::text IS DISTINCT FROM 'QUEUED'::text
                                     AND a.analysis_status::text IS DISTINCT FROM 'RUNNING'::text)
                                THEN 'ANALYSIS_FAILED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN a.primary_assessment_close_at < CURRENT_TIMESTAMP AND
                                 a.primary_attempt_status::text <> 'COMPLETED'::text THEN 'ASSESSMENT_WINDOW_CLOSED'::text
                            ELSE NULL::text
                            END,
                        CASE
                            WHEN a.primary_terminal_reason_code::text = ANY
                                 (ARRAY ['INSUFFICIENT_PROBLEM_EVIDENCE'::character varying, 'INSUFFICIENT_OWN_COMMIT_EVIDENCE'::character varying]::text[])
                                THEN 'PROBLEM_NOT_GENERATED'::text
                            ELSE NULL::text
                            END], NULL::text)                                       AS warning_codes,
       CASE
           WHEN a.latest_review_attempt_id IS NOT NULL AND (a.latest_review_status::text <> ALL
                                                            (ARRAY ['COMPLETED'::character varying, 'FAILED'::character varying, 'EXPIRED'::character varying]::text[]))
               THEN 'REVIEW_REQUIRED'::text
           WHEN a.primary_attempt_status::text = 'COMPLETED'::text THEN 'ASSESSMENT_COMPLETED'::text
           WHEN (a.primary_terminal_reason_code::text = ANY
                 (ARRAY ['NOT_ATTENDED'::character varying, 'SESSION_INCOMPLETE'::character varying]::text[])) OR
                a.primary_assessment_close_at IS NOT NULL AND a.primary_assessment_close_at < CURRENT_TIMESTAMP
               THEN 'ASSESSMENT_WINDOW_CLOSED'::text
           WHEN (a.primary_session_status::text = ANY
                 (ARRAY ['IN_PROGRESS'::character varying, 'PAUSED'::character varying]::text[])) OR
                a.primary_attempt_status::text = 'SESSION_IN_PROGRESS'::text THEN 'ASSESSMENT_IN_PROGRESS'::text
           WHEN (a.primary_attempt_status::text = ANY
                 (ARRAY ['SESSION_READY'::character varying, 'NOT_STARTED'::character varying]::text[])) AND
                a.primary_assessment_open_at IS NOT NULL THEN 'ASSESSMENT_AVAILABLE'::text
           -- [2026-08-25] analysis_job.status 만 보던 절을 응시 원장까지 보도록 넓혔다.
           -- 위 ASSESSMENT_WINDOW_CLOSED 절보다 아래여도 안전하다 — markAttemptsAnalysisFailed 는
           -- 세션이 열린 적 없는 응시(NOT_STARTED·SUBMITTED·ANALYZING)만 닫으므로
           -- primary_assessment_close_at 이 NULL 이고, 그 절이 이 행을 먼저 채 가지 못한다.
           WHEN a.analysis_status::text = 'FAILED'::text
                OR (a.primary_terminal_reason_code::text = 'ANALYSIS_FAILED'::text
                    AND a.analysis_status::text IS DISTINCT FROM 'QUEUED'::text
                    AND a.analysis_status::text IS DISTINCT FROM 'RUNNING'::text)
               THEN 'ANALYSIS_FAILED'::text
           WHEN a.source_submission_id IS NULL AND CURRENT_TIMESTAMP > a.submission_due_at THEN 'SUBMISSION_MISSED'::text
           WHEN a.source_submission_id IS NULL THEN 'SUBMISSION_REQUIRED'::text
           ELSE 'ANALYZING'::text
           END::character varying(100)                                              AS representative_status,
       CASE
           WHEN a.latest_review_attempt_id IS NOT NULL AND (a.latest_review_status::text <> ALL
                                                            (ARRAY ['COMPLETED'::character varying, 'FAILED'::character varying, 'EXPIRED'::character varying]::text[]))
               THEN 'START_REVIEW'::text
           WHEN a.primary_attempt_status::text = 'COMPLETED'::text AND rpt.published_at IS NOT NULL THEN 'VIEW_REPORT'::text
           WHEN a.primary_attempt_status::text = 'COMPLETED'::text THEN 'WAIT_FOR_REPORT'::text
           WHEN a.primary_session_status::text = ANY
                (ARRAY ['IN_PROGRESS'::character varying, 'PAUSED'::character varying]::text[]) THEN 'RESUME_ASSESSMENT'::text
           WHEN (a.primary_attempt_status::text = ANY
                 (ARRAY ['SESSION_READY'::character varying, 'NOT_STARTED'::character varying]::text[])) AND
                a.primary_assessment_open_at IS NOT NULL AND
                (a.primary_assessment_close_at IS NULL OR a.primary_assessment_close_at > CURRENT_TIMESTAMP)
               THEN 'START_ASSESSMENT'::text
           -- [2026-08-25] 이 절이 이번 수정의 실질적 성과다. 제출 마감 전이면 재제출 버튼이 살아나고,
           -- 재제출 → 분석 성공 → 적재 성공이면 markAttemptsReady 가 응시를 SESSION_READY 로 되살린다.
           WHEN (a.analysis_status::text = 'FAILED'::text
                 OR (a.primary_terminal_reason_code::text = 'ANALYSIS_FAILED'::text
                     AND a.analysis_status::text IS DISTINCT FROM 'QUEUED'::text
                     AND a.analysis_status::text IS DISTINCT FROM 'RUNNING'::text))
                AND a.submission_due_at > CURRENT_TIMESTAMP THEN
               CASE
                   WHEN a.submission_method::text = 'ZIP_WITH_GITLOG'::text THEN 'RESUBMIT_ZIP'::text
                   ELSE 'RESUBMIT_REPOSITORY'::text
                   END
           WHEN a.primary_terminal_reason_code::text = ANY
                (ARRAY ['NOT_ATTENDED'::character varying, 'SESSION_INCOMPLETE'::character varying, 'NOT_SUBMITTED'::character varying]::text[])
               THEN 'CONTACT_MANAGER'::text
           -- [2026-08-25] 마감이 지나 재제출이 불가능한 ANALYSIS_FAILED. 별도 WHEN 으로 둔 이유는
           -- 위 배열에 값 하나를 더하는 것과 달리 "재분석 중이 아닐 때만" 이라는 가드가 필요해서다.
           WHEN a.primary_terminal_reason_code::text = 'ANALYSIS_FAILED'::text
                AND a.analysis_status::text IS DISTINCT FROM 'QUEUED'::text
                AND a.analysis_status::text IS DISTINCT FROM 'RUNNING'::text
               THEN 'CONTACT_MANAGER'::text
           WHEN a.source_submission_id IS NULL AND CURRENT_TIMESTAMP > a.submission_due_at THEN 'CONTACT_MANAGER'::text
           WHEN a.source_submission_id IS NULL THEN 'SUBMIT_CODE'::text
           WHEN a.analysis_status::text = ANY
                (ARRAY ['QUEUED'::character varying, 'RUNNING'::character varying]::text[]) THEN 'WAIT_FOR_ANALYSIS'::text
           ELSE 'NONE'::text
           END::character varying(100)                                              AS default_action_code,
       CASE
           WHEN a.submission_due_at < CURRENT_TIMESTAMP AND a.source_submission_id IS NULL
               THEN 'SUBMISSION_DEADLINE_PASSED'::text
           WHEN a.primary_assessment_close_at < CURRENT_TIMESTAMP AND a.primary_attempt_status::text <> 'COMPLETED'::text
               THEN 'ASSESSMENT_WINDOW_CLOSED'::text
           ELSE NULL::text
           END::character varying(100)                                              AS action_unavailable_reason_code,
       mgr.manager_user_id,
       mgr.manager_name,
       a.aggregation_status,
       CURRENT_TIMESTAMP                                                            AS as_of_at,
       au.commit_email_status
FROM assessment_round_attendance a
         JOIN project p ON p.project_id = a.project_id
         JOIN project_assessment_round r ON r.assessment_round_id = a.assessment_round_id
         LEFT JOIN class cl ON cl.class_id = a.class_id
         LEFT JOIN team tm ON tm.team_id = a.team_id
         LEFT JOIN app_user au ON au.user_id = a.user_id
         LEFT JOIN measurement_attempt ma ON ma.attempt_id = a.primary_attempt_id
         LEFT JOIN LATERAL ( SELECT array_agg(pc.curriculum_version_id ORDER BY pc.sequence_no) AS version_ids,
                                    array_agg(m.title ORDER BY pc.sequence_no)                  AS names
                             FROM project_curriculum pc
                                      JOIN curriculum_version v ON v.version_id = pc.curriculum_version_id
                                      JOIN curriculum_material m ON m.material_id = v.material_id
                             WHERE pc.project_id = a.project_id) cur ON true
         LEFT JOIN LATERAL ( SELECT count(DISTINCT ps.problem_id)::integer       AS problem_count,
                                    count(DISTINCT ps.problem_stage_id)::integer AS question_count,
                                    max(ap.problem_scope::text)                  AS problem_scope
                             FROM assessment_problem ap
                                      LEFT JOIN problem_stage ps ON ps.problem_id = ap.problem_id AND
                                                                    ps.session_id = a.primary_session_id AND
                                                                    ap.generation_status::text = 'GENERATED'::text
                             WHERE ap.measurement_attempt_id = a.primary_attempt_id
                                OR ap.code_analysis_id = ma.code_analysis_id AND
                                   ap.problem_scope::text = 'TEAM_SHARED_PROBLEM'::text) probs ON true
         LEFT JOIN measurement_attempt rev ON rev.attempt_id = a.latest_review_attempt_id
         LEFT JOIN LATERAL ( SELECT x.report_id,
                                    x.org_id,
                                    x.cohort_id,
                                    x.class_id,
                                    x.user_id,
                                    x.assessment_round_id,
                                    x.report_type,
                                    x.lifecycle_status,
                                    x.scheduled_publish_at,
                                    x.published_at,
                                    x.trainee_release_status,
                                    x.trainee_disclosure_scope,
                                    x.trainee_released_at,
                                    x.trainee_released_by
                             FROM report x
                             WHERE x.user_id = a.user_id
                               AND x.assessment_round_id = a.assessment_round_id
                             ORDER BY (
                                          CASE
                                              WHEN x.lifecycle_status::text = 'ACTIVE'::text THEN 0
                                              ELSE 1
                                              END), x.published_at DESC NULLS LAST
                             LIMIT 1) rpt ON true
         LEFT JOIN LATERAL ( SELECT x.generation_run_id,
                                    x.report_id,
                                    x.trigger_type,
                                    x.idempotency_key,
                                    x.calculation_version,
                                    x.status,
                                    x.failure_reason,
                                    x.execution_no,
                                    x.started_at,
                                    x.completed_at,
                                    x.request_fingerprint
                             FROM report_generation_run x
                             WHERE x.report_id = rpt.report_id
                             ORDER BY x.execution_no DESC
                             LIMIT 1) gr ON true
         LEFT JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
         LEFT JOIN LATERAL ( SELECT x.reason_code,
                                    x.status
                             FROM reminder_dispatch x
                             WHERE x.assessment_round_id = a.assessment_round_id
                               AND x.user_id = a.user_id
                             ORDER BY x.sent_at DESC NULLS LAST, x.created_at DESC
                             LIMIT 1) notify ON true
         LEFT JOIN LATERAL ( SELECT x.policy_id,
                                    x.org_id,
                                    x.policy_version,
                                    x.monthly_ai_budget,
                                    x.currency_code,
                                    x.monthly_token_limit,
                                    x.storage_limit_bytes,
                                    x.retention_days,
                                    x.default_disclosure_scope,
                                    x.code_session_tier_code,
                                    x.allow_manager_invite,
                                    x.allow_data_export,
                                    x.allow_zip_submission,
                                    x.allow_github_integration,
                                    x.enable_big_project_contribution_analysis,
                                    x.effective_from,
                                    x.effective_to,
                                    x.status,
                                    x.created_by,
                                    x.created_at,
                                    x.updated_by,
                                    x.updated_at
                             FROM organization_policy x
                             WHERE x.org_id = a.org_id
                               AND x.status::text = 'ACTIVE'::text
                             ORDER BY x.policy_version DESC
                             LIMIT 1) pol ON true
         LEFT JOIN LATERAL ( SELECT count(*)::integer AS completed_count
                             FROM measurement_attempt x
                             WHERE x.assessment_round_id = a.assessment_round_id
                               AND x.user_id = a.user_id
                               AND x.attempt_type::text = 'REVIEW'::text
                               AND x.status::text = 'COMPLETED'::text) rvc ON true
         LEFT JOIN LATERAL ( SELECT x.manager_user_id,
                                    u.name AS manager_name
                             FROM manager_assignment x
                                      JOIN app_user u ON u.user_id = x.manager_user_id
                             WHERE x.class_id = a.class_id
                               AND x.status::text = 'ACTIVE'::text
                             ORDER BY x.assigned_at DESC
                             LIMIT 1) mgr ON true;

alter table trainee_home_round_view
    owner to postgres;

grant delete, insert, select, update on trainee_home_round_view to teamiz_app;

create view trainee_report_archive_view
            (org_id, cohort_id, user_id, project_id, assessment_round_id, report_id, snapshot_id, report_type,
             published_at, trainee_released_at, as_of_at, curriculum_versions, concept_display_snapshots,
             class_snapshot, team_snapshot, review_summary, completion_status, disclosure_scope)
as
SELECT rpt.org_id,
       rpt.cohort_id,
       rpt.user_id,
       ar.project_id,
       rpt.assessment_round_id,
       rpt.report_id,
       rs.snapshot_id,
       rpt.report_type,
       rpt.published_at,
       rpt.trainee_released_at,
       rs.as_of_at,
       COALESCE(rs.summary_payload -> 'curriculumVersions'::text, '[]'::jsonb) AS curriculum_versions,
       COALESCE(rs.summary_payload -> 'conceptItems'::text, '[]'::jsonb)       AS concept_display_snapshots,
       COALESCE(rs.summary_payload -> 'classSnapshot'::text, '{}'::jsonb)      AS class_snapshot,
       COALESCE(rs.summary_payload -> 'teamSnapshot'::text, '{}'::jsonb)       AS team_snapshot,
       rs.summary_payload ->> 'reviewSummary'::text                            AS review_summary,
       rs.completion_status,
       rpt.trainee_disclosure_scope                                            AS disclosure_scope
FROM report rpt
         JOIN report_snapshot rs ON rs.report_id = rpt.report_id
         LEFT JOIN project_assessment_round ar ON ar.assessment_round_id = rpt.assessment_round_id
WHERE rpt.user_id IS NOT NULL
  AND rpt.published_at IS NOT NULL;

alter table trainee_report_archive_view
    owner to postgres;

grant delete, insert, select, update on trainee_report_archive_view to teamiz_app;

create view trainee_report_problem_view
            (user_id, report_id, snapshot_id, problem_id, problem_stage_id, project_verification_concept_id,
             concept_display_name, concept_display_order, problem_scope, axis_code, confirmed_through_axis_code,
             next_unconfirmed_axis_code, reach_display_code, answer_count, own_answer_items, curriculum_location,
             result_explanation, answer_excerpt, review_required, review_status, review_attempt_id,
             review_before_after_items, has_explanation_content, explanation_status, can_view_explanation)
as
WITH review_map AS (SELECT measurement_attempt.source_attempt_id,
                           (array_agg(measurement_attempt.attempt_id
                            ORDER BY measurement_attempt.attempt_sequence_no DESC, measurement_attempt.updated_at DESC)
                            FILTER (WHERE measurement_attempt.attempt_type::text = 'REVIEW'::text))[1] AS review_attempt_id
                    FROM measurement_attempt
                    GROUP BY measurement_attempt.source_attempt_id)
SELECT rpt.user_id,
       rpt.report_id,
       rs.snapshot_id,
       re.problem_id,
       re.problem_stage_id,
       ap.project_verification_concept_id,
       COALESCE(re.subject_display_snapshot ->> 'conceptName'::text,
                t.canonical_name::text)::character varying(200)                              AS concept_display_name,
       COALESCE(re.display_order, pvc.sequence_no)                                           AS concept_display_order,
       ap.problem_scope,
       COALESCE(re.axis_code, ps.axis_code)                                                  AS axis_code,
       ap.best_success_stage::character varying(100)                                         AS confirmed_through_axis_code,
       CASE ap.best_success_stage
           WHEN 'L1'::text THEN 'L2'::text
           WHEN 'L2'::text THEN 'L3'::text
           WHEN 'L3'::text THEN 'L4'::text
           WHEN 'L4'::text THEN NULL::text
           ELSE 'L1'::text
           END::character varying(100)                                                       AS next_unconfirmed_axis_code,
       COALESCE(ap.best_success_stage, 'L0'::character varying)::character varying(100)      AS reach_display_code,
       (ps.question_answer_text IS NOT NULL)::integer + (ps.first_hint_answer_text IS NOT NULL)::integer +
       (ps.second_hint_answer_text IS NOT NULL)::integer                                     AS answer_count,
       jsonb_build_array(
               jsonb_build_object('type', 'QUESTION', 'answer', ps.question_answer_text, 'score', ps.question_score,
                                  'passed', ps.question_passed),
               jsonb_build_object('type', 'FIRST_HINT', 'answer', ps.first_hint_answer_text, 'score',
                                  ps.first_hint_score, 'passed', ps.first_hint_passed),
               jsonb_build_object('type', 'SECOND_HINT', 'answer', ps.second_hint_answer_text, 'score',
                                  ps.second_hint_score, 'passed', ps.second_hint_passed))    AS own_answer_items,
       COALESCE(re.trace_payload -> 'curriculumLocation'::text, '{}'::jsonb)::text           AS curriculum_location,
       re.evidence_summary                                                                   AS result_explanation,
       re.quote_excerpt                                                                      AS answer_excerpt,
       re.decision_code::text = 'REVIEW_REQUIRED'::text                                      AS review_required,
       COALESCE(re.decision_code, 'NOT_REQUIRED'::character varying)::character varying(100) AS review_status,
       rm.review_attempt_id,
       COALESCE(re.trace_payload -> 'reviewBeforeAfterItems'::text,
                '[]'::jsonb)                                                                 AS review_before_after_items,
       re.evidence_summary IS NOT NULL OR re.quote_excerpt IS NOT NULL                       AS has_explanation_content,
       CASE
           WHEN re.evidence_id IS NULL THEN 'UNAVAILABLE'::text
           WHEN re.evidence_summary IS NULL AND re.quote_excerpt IS NULL THEN 'EMPTY'::text
           ELSE 'AVAILABLE'::text
           END::character varying(100)                                                       AS explanation_status,
       rpt.published_at IS NOT NULL                                                          AS can_view_explanation
FROM report rpt
         JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
         JOIN report_evidence re ON re.snapshot_id = rs.snapshot_id
         LEFT JOIN assessment_problem ap ON ap.problem_id = re.problem_id
         LEFT JOIN problem_stage ps ON ps.problem_stage_id = re.problem_stage_id
         LEFT JOIN project_verification_concept pvc ON pvc.project_concept_id = ap.project_verification_concept_id
         LEFT JOIN teaches t ON t.teaches_id = pvc.teaches_id
         LEFT JOIN measurement_attempt initial_ma ON initial_ma.attempt_id = ap.measurement_attempt_id
         LEFT JOIN review_map rm ON rm.source_attempt_id = initial_ma.attempt_id
WHERE rpt.user_id IS NOT NULL
  AND (re.evidence_category::text = ANY
       (ARRAY ['ANSWER_EXCERPT'::character varying, 'RESULT_EXPLANATION'::character varying, 'CURRICULUM_LOCATION'::character varying]::text[]));

alter table trainee_report_problem_view
    owner to postgres;

grant delete, insert, select, update on trainee_report_problem_view to teamiz_app;

create view trainee_report_round_view
            (org_id, cohort_id, user_id, project_id, project_name, assessment_round_id, round_name, round_no,
             round_ended_at, attempt_id, attempt_status, terminal_reason_code, validity_review_status, report_id,
             snapshot_id, report_generation_status, trainee_release_status, trainee_disclosure_scope,
             round_report_view_status, report_publish_not_before_at, published_at, trainee_released_at, review_status,
             review_due_at, can_view_report, can_contact_manager, as_of_at)
as
SELECT rpt.org_id,
       rpt.cohort_id,
       rpt.user_id,
       p.project_id,
       p.name                                                      AS project_name,
       r.assessment_round_id,
       r.round_name,
       r.round_no,
       COALESCE(ma.terminal_at, r.report_publish_not_before_at)    AS round_ended_at,
       ma.attempt_id,
       ma.status                                                   AS attempt_status,
       ma.terminal_reason_code,
       ma.validity_review_status,
       rpt.report_id,
       rs.snapshot_id,
       gr.status                                                   AS report_generation_status,
       rpt.trainee_release_status,
       rpt.trainee_disclosure_scope,
       CASE
           WHEN rpt.published_at IS NOT NULL THEN 'VIEWABLE'::text
           WHEN gr.status::text = ANY
                (ARRAY ['QUEUED'::character varying, 'RUNNING'::character varying, 'RETRYING'::character varying]::text[])
               THEN 'GENERATING'::text
           WHEN rpt.report_id IS NULL THEN 'NOT_CREATED'::text
           ELSE 'WITHHELD'::text
           END::character varying(100)                             AS round_report_view_status,
       r.report_publish_not_before_at,
       rpt.published_at,
       rpt.trainee_released_at,
       rev.status                                                  AS review_status,
       rev.review_due_at,
       rpt.published_at IS NOT NULL AND rs.snapshot_id IS NOT NULL AS can_view_report,
       true                                                        AS can_contact_manager,
       COALESCE(rs.as_of_at, CURRENT_TIMESTAMP)                    AS as_of_at
FROM report rpt
         JOIN project_assessment_round r ON r.assessment_round_id = rpt.assessment_round_id
         JOIN project p ON p.project_id = r.project_id
         LEFT JOIN measurement_attempt ma
                   ON ma.assessment_round_id = r.assessment_round_id AND ma.user_id = rpt.user_id AND
                      ma.attempt_type::text = 'INITIAL'::text
         LEFT JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
         LEFT JOIN LATERAL ( SELECT x.generation_run_id,
                                    x.report_id,
                                    x.trigger_type,
                                    x.idempotency_key,
                                    x.calculation_version,
                                    x.status,
                                    x.failure_reason,
                                    x.execution_no,
                                    x.started_at,
                                    x.completed_at,
                                    x.request_fingerprint
                             FROM report_generation_run x
                             WHERE x.report_id = rpt.report_id
                             ORDER BY x.execution_no DESC
                             LIMIT 1) gr ON true
         LEFT JOIN LATERAL ( SELECT x.attempt_id,
                                    x.org_id,
                                    x.cohort_id,
                                    x.assessment_round_id,
                                    x.project_id,
                                    x.user_id,
                                    x.assessment_contract_version,
                                    x.source_submission_id,
                                    x.code_analysis_id,
                                    x.attempt_type,
                                    x.source_attempt_id,
                                    x.attempt_sequence_no,
                                    x.assigned_at,
                                    x.assigned_by,
                                    x.review_source_report_id,
                                    x.review_source_report_snapshot_id,
                                    x.review_due_at,
                                    x.status,
                                    x.terminal_reason_code,
                                    x.terminal_at,
                                    x.analysis_completed_at,
                                    x.assessment_open_at,
                                    x.assessment_close_at,
                                    x.validity_review_status,
                                    x.validity_trigger_reason_code,
                                    x.validity_decision_reason_code,
                                    x.validity_decision_note,
                                    x.validity_review_started_at,
                                    x.validity_reviewed_by,
                                    x.validity_reviewed_at,
                                    x.row_version,
                                    x.updated_at,
                                    x.outcome_type_code,
                                    x.outcome_verdict,
                                    x.outcome_policy_version,
                                    x.outcome_judged_at
                             FROM measurement_attempt x
                             WHERE x.source_attempt_id = ma.attempt_id
                               AND x.attempt_type::text = 'REVIEW'::text
                             ORDER BY x.attempt_sequence_no DESC
                             LIMIT 1) rev ON true
WHERE rpt.user_id IS NOT NULL;

alter table trainee_report_round_view
    owner to postgres;

grant delete, insert, select, update on trainee_report_round_view to teamiz_app;

create view trainee_report_view
            (org_id, cohort_id, user_id, project_id, assessment_round_id, project_name, round_name, report_id,
             report_type, lifecycle_status, report_generation_status, snapshot_id, completion_status, published_at,
             trainee_release_status, trainee_disclosure_scope, trainee_released_at, round_report_view_status,
             can_view_report, can_view_own_answers, can_view_curriculum_location, concept_items, review_summary,
             as_of_at, calculation_version)
as
SELECT rpt.org_id,
       rpt.cohort_id,
       rpt.user_id,
       p.project_id,
       rpt.assessment_round_id,
       p.name                                                            AS project_name,
       r.round_name,
       rpt.report_id,
       rpt.report_type,
       rpt.lifecycle_status,
       gr.status                                                         AS report_generation_status,
       rs.snapshot_id,
       rs.completion_status,
       rpt.published_at,
       rpt.trainee_release_status,
       rpt.trainee_disclosure_scope,
       rpt.trainee_released_at,
       CASE
           WHEN rpt.published_at IS NOT NULL AND rs.snapshot_id IS NOT NULL THEN 'VIEWABLE'::text
           WHEN gr.status::text = ANY
                (ARRAY ['QUEUED'::character varying, 'RUNNING'::character varying, 'RETRYING'::character varying]::text[])
               THEN 'GENERATING'::text
           WHEN rpt.published_at IS NULL THEN 'NOT_PUBLISHED'::text
           ELSE 'WITHHELD'::text
           END::character varying(100)                                   AS round_report_view_status,
       rpt.published_at IS NOT NULL AND rs.snapshot_id IS NOT NULL       AS can_view_report,
       rpt.published_at IS NOT NULL                                      AS can_view_own_answers,
       rpt.published_at IS NOT NULL                                      AS can_view_curriculum_location,
       COALESCE(rs.summary_payload -> 'conceptItems'::text, '[]'::jsonb) AS concept_items,
       rs.summary_payload ->> 'reviewSummary'::text                      AS review_summary,
       rs.as_of_at,
       rs.calculation_version
FROM report rpt
         LEFT JOIN LATERAL ( SELECT x.generation_run_id,
                                    x.report_id,
                                    x.trigger_type,
                                    x.idempotency_key,
                                    x.calculation_version,
                                    x.status,
                                    x.failure_reason,
                                    x.execution_no,
                                    x.started_at,
                                    x.completed_at,
                                    x.request_fingerprint
                             FROM report_generation_run x
                             WHERE x.report_id = rpt.report_id
                             ORDER BY x.execution_no DESC
                             LIMIT 1) gr ON true
         LEFT JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
         LEFT JOIN project_assessment_round r ON r.assessment_round_id = rpt.assessment_round_id
         LEFT JOIN project p ON p.project_id = r.project_id
WHERE rpt.user_id IS NOT NULL;

alter table trainee_report_view
    owner to postgres;

grant delete, insert, select, update on trainee_report_view to teamiz_app;


