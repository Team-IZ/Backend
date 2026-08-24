-- 2026-08-24 · 팀 미제출·코드 분석 실패 교육생이 명단에서 배지 없이 「정상」으로 보인다
--
-- manager_trainee_roster_view 재생성 (정본: docs/table-definition/PostgreSQL_View_v08.sql)
--
-- 배경
--   current_round_primary_status_code 의 1층 응시상태 판정이
--   `ma.terminal_reason_code = 'NOT_ATTENDED'` 하나만 봤다. 그런데 검증 세션을 하지 못하는
--   경로는 셋이다.
--
--     terminal_reason_code = 'NOT_ATTENDED'     응시할 수 있었는데 창이 닫히도록 안 봤다
--     terminal_reason_code = 'NOT_SUBMITTED'    팀이 제출하지 않아 문항이 만들어지지 않았다
--     terminal_reason_code = 'ANALYSIS_FAILED'  제출은 했으나 코드 분석이 실패했다
--
--   뒤의 둘은 CASE 의 어느 WHEN 에도 안 걸려 ELSE NULL 로 떨어졌고, NULL 은 화면에서 「정상」이다.
--   즉 팀이 제출을 안 해 응시조차 못 한 학생이 매니저 명단에서 아무 배지 없이 정상으로 보였다.
--   그린컴퍼니 5·6·7기 미프 3차 기준 각 29명(미제출 20 · 분석 실패 9)이 이 상태다.
--
--   같은 결함을 결과 탭(EvaluationService.resultStatus)에서 먼저 고쳤다. 거기서는 이 셋을
--   resultStatus='NOT_ATTENDED' 한 값으로 묶고 원인을 notAttendedReason 으로 냈다.
--   이 뷰도 같은 모양으로 맞춘다 — 두 화면이 같은 학생을 두고 다르게 말하면 안 된다.
--
-- 변경 세 곳
--   1) current_round_primary_status_code — 1층 미응시 판정을 3종으로 넓힌다.
--   2) round_terminal_at — 같은 3종 + SESSION_INCOMPLETE 에서 값이 나오게 한다.
--      배지가 「미제출 · 07-14」처럼 사유와 일자를 함께 그리기 때문이다.
--   3) current_round_not_attended_reason_code — 신규 컬럼(맨 끝).
--      NO_SHOW / NOT_SUBMITTED / ANALYSIS_FAILED. 그 밖에는 NULL.
--      NO_SHOW 만 학생 책임이며 나머지 둘은 볼 수 없었던 경우라 독촉 대상이 아니다.
--
--   신규 컬럼을 맨 끝에 두므로 CREATE OR REPLACE VIEW 로 교체할 수 있다
--   (Postgres 는 기존 컬럼의 이름·타입·순서가 그대로일 때만 REPLACE 를 허용한다).
--
-- 적용 후 확인 — 미제출·분석 실패가 더 이상 NULL 이 아니어야 한다
--   SELECT current_round_primary_status_code,
--          current_round_not_attended_reason_code,
--          count(*)
--     FROM public.manager_trainee_roster_view
--    GROUP BY 1, 2
--    ORDER BY 3 DESC;
--
--   대조군 — 사유가 붙는 행은 반드시 NOT_ATTENDED 여야 하고, 그 역도 성립해야 한다
--   SELECT count(*) AS should_be_zero
--     FROM public.manager_trainee_roster_view
--    WHERE (current_round_not_attended_reason_code IS NOT NULL)
--       <> (current_round_primary_status_code = 'NOT_ATTENDED');
--
--   대조군 — 중단·무효·위험 유형 판정은 이 마이그레이션으로 달라지지 않아야 한다
--   SELECT current_round_primary_status_code, count(*)
--     FROM public.manager_trainee_roster_view
--    WHERE current_round_primary_status_code <> 'NOT_ATTENDED'
--    GROUP BY 1 ORDER BY 2 DESC;

BEGIN;

CREATE OR REPLACE VIEW public.manager_trainee_roster_view
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

GRANT DELETE, INSERT, SELECT, UPDATE ON public.manager_trainee_roster_view TO teamiz_app;

COMMIT;
