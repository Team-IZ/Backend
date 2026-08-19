-- =============================================================================
-- 리포트 공개/비공개 개념 폐지 — DDL 변경 사항
--
-- 작성일: 2026-08-19 (기획논의 확정)
-- 관련: Report · TraineeReportServiceImpl · domain/disclosure 삭제
--
-- 상태: ⬜ 백엔드 구현 예정 · ⬜ 운영 DB 미적용
--
--   ★ 이 DDL이 배포보다 먼저다. 적용하지 않은 DB에 코드를 올리면 리포트 INSERT가
--     "null value in column trainee_release_status" 로 실패한다 — 엔티티에서 그 필드를
--     떼어냈기 때문에 DEFAULT 가 없으면 아무 값도 들어가지 않는다. 즉 리포트 생성이 통째로 막힌다.
--
-- 무엇이 바뀌나
--
--   종전에는 리포트가 발행되어도 매니저가 공개 범위를 정해 줄 때까지 학생이 본문을 볼 수 없었다
--   (trainee_release_status = NOT_CONFIGURED → 화면 상태 PENDING_VISIBILITY).
--   이제 발행 = 공개다. 검증 세션이 끝나 리포트가 생성·발행되면 학생이 곧바로 본다.
--
--   그래서 공개 상태 3값(NOT_CONFIGURED · WITHHELD · RELEASED)과 공개 범위 2값(PRIVATE · SUMMARY)이
--   갈 곳을 잃는다. 판정의 자리는 전부 published_at 이 물려받는다 — "공개됐나" 가 "발행됐나" 가 된다.
--
-- 🔴 컬럼을 이번에 지우지 않는 이유
--
--   trainee_release_status 는 뷰 5개가 컬럼으로 그대로 내보내고 있다(아래 4절 목록).
--   CREATE OR REPLACE VIEW 는 컬럼을 뺄 수 없어서 지우려면 DROP VIEW 후 재생성해야 하고,
--   그러면 정의서 View.sql 과 백엔드 조회부까지 같은 배포에 묶인다.
--   이번에는 판정식만 published_at 기준으로 바꾸고 컬럼은 남긴다 — 값은 항상 RELEASED · FULL 이라
--   패스스루 컬럼을 읽는 쪽이 있어도 종전과 같은 값을 본다. 컬럼·뷰 컬럼 정리는 2단계다(6절).
--
-- 검증: ⬜ 임시 Postgres 16에 v07 스키마를 올린 뒤 확인 예정
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. 조합 제약을 없앤다
-- -----------------------------------------------------------------------------

/*
 * ck_report_trainee_release_status_2 는 상태값과 나머지 3컬럼의 조합을 강제한다.
 *
 *   NOT_CONFIGURED → scope NULL        · released_at NULL     · released_by NULL
 *   WITHHELD       → scope PRIVATE     · released_at NULL     · released_by NULL
 *   RELEASED       → scope SUMMARY|FULL · released_at NOT NULL · released_by NOT NULL
 *
 * 🔴 마지막 줄이 이번 변경의 실제 걸림돌이다. trainee_released_by 는 app_user FK 라
 *    "누가 공개했는가" 에 넣을 사람이 있어야 하는데, 매니저가 판단에서 빠지면서 그 사람이 없어졌다.
 *    자동화 전용 시스템 계정을 만들어 채우는 방법도 있지만, 그건 없어진 개념을 붙잡기 위해
 *    없던 계정을 만드는 것이라 순서가 거꾸로다. 조합 자체를 없앤다.
 *
 * 값 집합 제약 두 개(ck_report_trainee_release_status · ck_report_trainee_disclosure_scope)는
 * 그대로 둔다 — 남길 값 RELEASED · FULL 이 둘 다 통과하고, 2단계에서 컬럼과 함께 정리한다.
 */
ALTER TABLE report DROP CONSTRAINT ck_report_trainee_release_status_2;


-- -----------------------------------------------------------------------------
-- 2. 기본값을 준다
-- -----------------------------------------------------------------------------

/*
 * 엔티티(Report)에서 공개 상태 4필드를 떼어내므로 INSERT 문에 이 컬럼들이 실리지 않는다.
 * trainee_release_status 는 NOT NULL 이라 DEFAULT 가 없으면 그 INSERT 가 실패한다.
 *
 * trainee_disclosure_scope 는 nullable 이지만 함께 채운다 — 뷰 5개가 이 컬럼을 패스스루로
 * 내보내고 있어서, NULL 로 두면 종전에 FULL·SUMMARY 를 보던 쪽이 갑자기 NULL 을 받는다.
 */
ALTER TABLE report ALTER COLUMN trainee_release_status   SET DEFAULT 'RELEASED';
ALTER TABLE report ALTER COLUMN trainee_disclosure_scope SET DEFAULT 'FULL';


-- -----------------------------------------------------------------------------
-- 3. 기존 행을 맞춘다
-- -----------------------------------------------------------------------------

/*
 * 🔴 이 UPDATE 를 건너뛰면 안 된다. 백엔드가 PENDING_VISIBILITY 판정을 지우기 때문에,
 *    종전에 WITHHELD·NOT_CONFIGURED 로 잠겨 있던 리포트가 "볼 수 있는 상태" 로 분류되면서도
 *    스냅샷 조회는 RELEASED 조건에 걸려 빈 화면이 된다.
 *
 * trainee_released_at 은 published_at 으로 채운다. 이제 발행이 곧 공개라 두 시각이 같다 —
 * NULL 로 두면 trainee_report_archive_view 가 "공개됐는데 공개 시각이 없다" 를 내보낸다.
 *
 * trainee_released_by 는 NULL 로 둔다. 공개를 결정한 사람이 실제로 없어졌고, 없는 사실을
 * 시스템 계정으로 지어내면 감사 기록이 사람을 잘못 지목한다. 컬럼은 2단계에서 지운다.
 */
UPDATE report
   SET trainee_release_status   = 'RELEASED',
       trainee_disclosure_scope = 'FULL',
       trainee_released_at      = COALESCE(trainee_released_at, published_at)
 WHERE trainee_release_status <> 'RELEASED'
    OR trainee_disclosure_scope IS DISTINCT FROM 'FULL';


-- -----------------------------------------------------------------------------
-- 4. 뷰의 판정식을 published_at 기준으로 바꾼다
-- -----------------------------------------------------------------------------

/*
 * 판정식이 걸린 뷰 5개를 전부 다시 만든다. 컬럼 목록·순서·타입은 하나도 바뀌지 않으므로
 * CREATE OR REPLACE 로 끝난다(DROP 불필요).
 *
 *   trainee_home_round_view      기본 액션 VIEW_REPORT
 *   manager_project_result_view  trainee_evidence_available
 *   trainee_report_problem_view  can_view_explanation
 *   trainee_report_round_view    report_view_status · can_view_report
 *   trainee_report_view          report_view_status · can_view_report
 *                                · can_view_own_answers · can_view_curriculum_location
 *
 * 치환 규칙은 하나다 — trainee_release_status='RELEASED' [AND scope 조건] → published_at IS NOT NULL.
 *
 * 🔴 조건을 그냥 지우고 TRUE 로 두면 안 된다. DEFAULT 'RELEASED' 때문에 아직 발행되지 않은
 *    DRAFT 리포트도 RELEASED 로 들어가기 때문이다. 종전에는 NOT_CONFIGURED 가 그 자리를 막아
 *    줬는데 그 값이 없어졌으니, 발행 여부를 published_at 이 직접 말해야 한다.
 *    특히 trainee_home_round_view 의 VIEW_REPORT 는 스냅샷 조인조차 없어서 이 조건이 유일한 방어다.
 *
 * ReportRunFinalizer 는 스냅샷·근거를 만든 뒤에도 발행을 보류할 수 있다(세션 무효,
 * 발행 예정 시각 전). 그래서 "스냅샷이 있다" 는 "발행됐다" 와 같은 말이 아니고,
 * snapshot_id 조건이 이미 붙어 있는 뷰에도 published_at 을 함께 둔다.
 */

CREATE OR REPLACE VIEW public.trainee_home_round_view (
    trainee_user_id,
    org_id,
    cohort_id,
    class_id_at_round,
    class_name,
    team_id_at_round,
    team_number,
    team_name,
    project_id,
    project_name,
    project_category,
    assessment_round_id,
    round_no,
    round_name,
    round_status,
    curriculum_version_ids,
    curriculum_display_names,
    current_submission_id,
    submission_method,
    submission_status,
    submitted_at,
    submission_due_at,
    available_submission_methods,
    can_submit,
    can_resubmit,
    analysis_phase,
    latest_analysis_job_id,
    analysis_job_status,
    code_analysis_id,
    analysis_failure_code,
    prepared_problem_count,
    prepared_question_count,
    problem_scope,
    initial_attempt_id,
    initial_attempt_status,
    initial_terminal_reason_code,
    assessment_open_at,
    assessment_close_at,
    round_assessment_open_at,
    round_assessment_due_at,
    initial_session_id,
    initial_session_status,
    initial_terminal_at,
    latest_retry_attempt_id,
    latest_retry_status,
    latest_review_attempt_id,
    review_status,
    review_due_at,
    completed_review_count,
    review_source_report_id,
    review_source_report_snapshot_id,
    report_id,
    report_generation_status,
    report_publish_status,
    trainee_release_status,
    report_publish_mode,
    report_publish_not_before_at,
    explanation_status,
    latest_notification_reason_code,
    latest_notification_status,
    warning_codes,
    representative_status,
    default_action_code,
    action_unavailable_reason_code,
    manager_user_id,
    manager_name,
    aggregation_status,
    as_of_at,
    commit_email_status
) AS
SELECT
    (a.user_id)::UUID AS trainee_user_id,
    (a.org_id)::UUID AS org_id,
    (a.cohort_id)::UUID AS cohort_id,
    (a.class_id)::UUID AS class_id_at_round,
    (cl.name)::VARCHAR(200) AS class_name,
    (a.team_id)::UUID AS team_id_at_round,
    (tm.team_number)::TEXT AS team_number,
    (tm.name)::VARCHAR(200) AS team_name,
    (a.project_id)::UUID AS project_id,
    (p.name)::VARCHAR(200) AS project_name,
    (p.project_category)::VARCHAR(30) AS project_category,
    (a.assessment_round_id)::UUID AS assessment_round_id,
    (a.round_no)::INTEGER AS round_no,
    (a.round_name)::VARCHAR(200) AS round_name,
    (a.round_status)::VARCHAR(100) AS round_status,
    (COALESCE(cur.version_ids,ARRAY[]::uuid[]))::UUID[] AS curriculum_version_ids,
    (COALESCE(cur.names,ARRAY[]::text[]))::TEXT[] AS curriculum_display_names,
    (a.source_submission_id)::UUID AS current_submission_id,
    (a.submission_method)::VARCHAR(100) AS submission_method,
    (a.submission_status)::VARCHAR(100) AS submission_status,
    (a.submitted_at)::TIMESTAMPTZ AS submitted_at,
    (a.submission_due_at)::TIMESTAMPTZ AS submission_due_at,
    (ARRAY_REMOVE(ARRAY[
 'GITHUB_URL',
 CASE WHEN COALESCE(pol.allow_zip_submission,FALSE) THEN 'ZIP_WITH_GITLOG' END],NULL))::TEXT AS available_submission_methods,
    (a.submission_due_at>CURRENT_TIMESTAMP AND a.primary_session_status IS NULL)::BOOLEAN AS can_submit,
    (a.source_submission_id IS NOT NULL AND a.submission_due_at>CURRENT_TIMESTAMP AND a.primary_session_status IS NULL)::BOOLEAN AS can_resubmit,
    (CASE WHEN a.source_submission_id IS NULL THEN 'NOT_SUBMITTED'
 WHEN a.analysis_status IN ('QUEUED','RUNNING') THEN 'ANALYZING'
 WHEN a.analysis_status='FAILED' THEN 'FAILED'
 WHEN a.analysis_status='SUCCEEDED' THEN 'COMPLETED' ELSE 'WAITING' END)::TEXT AS analysis_phase,
    (a.analysis_job_id)::UUID AS latest_analysis_job_id,
    (a.analysis_status)::VARCHAR(100) AS analysis_job_status,
    (ma.code_analysis_id)::UUID AS code_analysis_id,
    (CASE WHEN a.analysis_status='FAILED' THEN 'ANALYSIS_FAILED' ELSE NULL END)::VARCHAR(100) AS analysis_failure_code,
    (COALESCE(probs.problem_count,0))::INTEGER AS prepared_problem_count,
    (COALESCE(probs.question_count,0))::INTEGER AS prepared_question_count,
    (probs.problem_scope)::VARCHAR(100) AS problem_scope,
    (a.primary_attempt_id)::UUID AS initial_attempt_id,
    (a.primary_attempt_status)::VARCHAR(100) AS initial_attempt_status,
    (a.primary_terminal_reason_code)::VARCHAR(100) AS initial_terminal_reason_code,
    (a.primary_assessment_open_at)::TIMESTAMPTZ AS assessment_open_at,
    (a.primary_assessment_close_at)::TIMESTAMPTZ AS assessment_close_at,
    (r.assessment_open_at)::TIMESTAMPTZ AS round_assessment_open_at,
    (r.assessment_due_at)::TIMESTAMPTZ AS round_assessment_due_at,
    (a.primary_session_id)::UUID AS initial_session_id,
    (a.primary_session_status)::VARCHAR(100) AS initial_session_status,
    (ma.terminal_at)::TIMESTAMPTZ AS initial_terminal_at,
    (a.latest_retry_attempt_id)::UUID AS latest_retry_attempt_id,
    (a.latest_retry_status)::VARCHAR(100) AS latest_retry_status,
    (a.latest_review_attempt_id)::UUID AS latest_review_attempt_id,
    (a.latest_review_status)::VARCHAR(100) AS review_status,
    (rev.review_due_at)::TIMESTAMPTZ AS review_due_at,
    (COALESCE(rvc.completed_count,0))::INTEGER AS completed_review_count,
    (rev.review_source_report_id)::UUID AS review_source_report_id,
    (rev.review_source_report_snapshot_id)::UUID AS review_source_report_snapshot_id,
    (rpt.report_id)::UUID AS report_id,
    (gr.status)::VARCHAR(100) AS report_generation_status,
    (CASE WHEN rpt.published_at IS NOT NULL THEN 'PUBLISHED' WHEN gr.status IN ('QUEUED','RUNNING','RETRYING') THEN 'GENERATING' ELSE 'NOT_PUBLISHED' END)::VARCHAR(100) AS report_publish_status,
    (rpt.trainee_release_status)::VARCHAR(100) AS trainee_release_status,
    (r.report_publish_mode)::VARCHAR(100) AS report_publish_mode,
    (r.report_publish_not_before_at)::TIMESTAMPTZ AS report_publish_not_before_at,
    (CASE WHEN rs.snapshot_id IS NULL THEN 'UNAVAILABLE' WHEN rs.completion_status='FULL' THEN 'AVAILABLE' ELSE 'PARTIAL' END)::VARCHAR(100) AS explanation_status,
    (notify.reason_code)::VARCHAR(100) AS latest_notification_reason_code,
    (notify.status)::VARCHAR(100) AS latest_notification_status,
    (ARRAY_REMOVE(ARRAY[
 CASE WHEN a.submission_deadline_status='MISSED' THEN 'SUBMISSION_DEADLINE_PASSED' END,
 CASE WHEN a.analysis_status='FAILED' THEN 'ANALYSIS_FAILED' END,
 CASE WHEN a.primary_assessment_close_at<CURRENT_TIMESTAMP AND a.primary_attempt_status<>'COMPLETED' THEN 'ASSESSMENT_WINDOW_CLOSED' END,
 CASE WHEN a.primary_terminal_reason_code IN ('INSUFFICIENT_PROBLEM_EVIDENCE','INSUFFICIENT_OWN_COMMIT_EVIDENCE') THEN 'PROBLEM_NOT_GENERATED' END],NULL))::TEXT[] AS warning_codes,
    (CASE
 WHEN a.latest_review_attempt_id IS NOT NULL AND a.latest_review_status NOT IN ('COMPLETED','FAILED','EXPIRED') THEN 'REVIEW_REQUIRED'
 WHEN a.primary_attempt_status='COMPLETED' THEN 'ASSESSMENT_COMPLETED'
 WHEN a.primary_terminal_reason_code IN ('NOT_ATTENDED','SESSION_INCOMPLETE')
      OR (a.primary_assessment_close_at IS NOT NULL AND a.primary_assessment_close_at<CURRENT_TIMESTAMP) THEN 'ASSESSMENT_WINDOW_CLOSED'
 WHEN a.primary_session_status IN ('IN_PROGRESS','PAUSED') OR a.primary_attempt_status='SESSION_IN_PROGRESS' THEN 'ASSESSMENT_IN_PROGRESS'
 WHEN a.primary_attempt_status IN ('SESSION_READY','NOT_STARTED') AND a.primary_assessment_open_at IS NOT NULL THEN 'ASSESSMENT_AVAILABLE'
 WHEN a.analysis_status='FAILED' THEN 'ANALYSIS_FAILED'
 WHEN a.source_submission_id IS NULL AND CURRENT_TIMESTAMP>a.submission_due_at THEN 'SUBMISSION_MISSED'
 WHEN a.source_submission_id IS NULL THEN 'SUBMISSION_REQUIRED'
 ELSE 'ANALYZING' END)::VARCHAR(100) AS representative_status,
    (CASE
 -- 🔴 START_REVIEW 는 COMPLETED 두 분기보다 **위**에 있어야 한다(2026-08-16).
 --    REVIEW 응시가 있으면 INITIAL 은 반드시 COMPLETED 라, 아래에 두면 영영 도달하지 못하고
 --    배지는 REVIEW_REQUIRED 인데 버튼은 VIEW_REPORT 가 나간다.
 --    다시 보기를 마치면 latest_review_status='COMPLETED' 가 되어 자연히 VIEW_REPORT 로 돌아간다.
 WHEN a.latest_review_attempt_id IS NOT NULL AND a.latest_review_status NOT IN ('COMPLETED','FAILED','EXPIRED') THEN 'START_REVIEW'
 WHEN a.primary_attempt_status='COMPLETED' AND rpt.published_at IS NOT NULL THEN 'VIEW_REPORT'
 WHEN a.primary_attempt_status='COMPLETED' THEN 'WAIT_FOR_REPORT'
 WHEN a.primary_session_status IN ('IN_PROGRESS','PAUSED') THEN 'RESUME_ASSESSMENT'
 WHEN a.primary_attempt_status IN ('SESSION_READY','NOT_STARTED') AND a.primary_assessment_open_at IS NOT NULL
      AND (a.primary_assessment_close_at IS NULL OR a.primary_assessment_close_at>CURRENT_TIMESTAMP) THEN 'START_ASSESSMENT'
 WHEN a.analysis_status='FAILED' AND a.submission_due_at>CURRENT_TIMESTAMP
      THEN CASE WHEN a.submission_method='ZIP_WITH_GITLOG' THEN 'RESUBMIT_ZIP' ELSE 'RESUBMIT_REPOSITORY' END
 WHEN a.primary_terminal_reason_code IN ('NOT_ATTENDED','SESSION_INCOMPLETE','NOT_SUBMITTED') THEN 'CONTACT_MANAGER'
 WHEN a.source_submission_id IS NULL AND CURRENT_TIMESTAMP>a.submission_due_at THEN 'CONTACT_MANAGER'
 -- [V-14 / 2026-08-06] VERIFY_COMMIT_EMAIL 분기를 삭제했다.
 --   커밋 이메일은 제출을 막는 게이트가 아니라 커밋 귀속(commit_attribution.attributed_user_id
 --   -> participant_contribution_snapshot -> INDIVIDUAL_OWN_COMMIT 문제) 조건이다.
 --   미확인 안내는 새로 노출한 commit_email_status 컬럼이 단독으로 담당한다.
 WHEN a.source_submission_id IS NULL THEN 'SUBMIT_CODE'
 WHEN a.analysis_status IN ('QUEUED','RUNNING') THEN 'WAIT_FOR_ANALYSIS'
 ELSE 'NONE' END)::VARCHAR(100) AS default_action_code,
    (CASE WHEN a.submission_due_at<CURRENT_TIMESTAMP AND a.source_submission_id IS NULL THEN 'SUBMISSION_DEADLINE_PASSED' WHEN a.primary_assessment_close_at<CURRENT_TIMESTAMP AND a.primary_attempt_status<>'COMPLETED' THEN 'ASSESSMENT_WINDOW_CLOSED' ELSE NULL END)::VARCHAR(100) AS action_unavailable_reason_code,
    (mgr.manager_user_id)::UUID AS manager_user_id,
    (mgr.manager_name)::VARCHAR(200) AS manager_name,
    (a.aggregation_status)::VARCHAR(20) AS aggregation_status,
    (CURRENT_TIMESTAMP)::TIMESTAMPTZ AS as_of_at,
    -- [V-14 / 2026-08-06] NULL은 커밋 이메일 미등록을 뜻한다.
    (au.commit_email_status)::VARCHAR(30) AS commit_email_status
FROM public.assessment_round_attendance a
JOIN public.project p ON p.project_id=a.project_id
JOIN public.project_assessment_round r ON r.assessment_round_id=a.assessment_round_id
LEFT JOIN public.class cl ON cl.class_id=a.class_id
LEFT JOIN public.team tm ON tm.team_id=a.team_id
LEFT JOIN public.app_user au ON au.user_id=a.user_id
LEFT JOIN public.measurement_attempt ma ON ma.attempt_id=a.primary_attempt_id
LEFT JOIN LATERAL (
 SELECT ARRAY_AGG(pc.curriculum_version_id ORDER BY pc.sequence_no) version_ids,
   ARRAY_AGG(m.title ORDER BY pc.sequence_no) names
 FROM public.project_curriculum pc JOIN public.curriculum_version v ON v.version_id=pc.curriculum_version_id
 JOIN public.curriculum_material m ON m.material_id=v.material_id WHERE pc.project_id=a.project_id
) cur ON TRUE
-- [V-15 / 2026-08-15] 두 COUNT를 ap가 아니라 ps(이 교육생 세션의 단계) 기준으로 바꿨다.
--   · ap 기준이면 NOT_GENERATED 슬롯까지 세어 항상 3이었다.
--   · ps 조인에 session_id 조건이 없으면 TEAM_SHARED_PROBLEM에서 팀원 전원의 단계를 세어
--     question_count가 팀 인원수만큼 부풀었다.
--   generation_status 조건은 단계가 GENERATED 문제에만 깔린다는 점에서 이미 함축돼 있으나,
--   그 불변식이 깨졌을 때 카운트가 조용히 틀리지 않도록 명시한다.
--   WHERE가 아니라 ON 절에 두는 이유는 problem_scope(MAX)의 기존 값을 보존하기 위해서다.
LEFT JOIN LATERAL (
 SELECT COUNT(DISTINCT ps.problem_id)::integer problem_count,
   COUNT(DISTINCT ps.problem_stage_id)::integer question_count,
   MAX(ap.problem_scope) problem_scope
 FROM public.assessment_problem ap
 LEFT JOIN public.problem_stage ps ON ps.problem_id=ap.problem_id
   AND ps.session_id=a.primary_session_id
   AND ap.generation_status='GENERATED'
 WHERE ap.measurement_attempt_id=a.primary_attempt_id
    OR (ap.code_analysis_id=ma.code_analysis_id AND ap.problem_scope='TEAM_SHARED_PROBLEM')
) probs ON TRUE
LEFT JOIN public.measurement_attempt rev ON rev.attempt_id=a.latest_review_attempt_id
LEFT JOIN LATERAL (
 SELECT x.* FROM public.report x WHERE x.user_id=a.user_id AND x.assessment_round_id=a.assessment_round_id
 ORDER BY CASE WHEN x.lifecycle_status='ACTIVE' THEN 0 ELSE 1 END,x.published_at DESC NULLS LAST LIMIT 1
) rpt ON TRUE
LEFT JOIN LATERAL (
 SELECT x.* FROM public.report_generation_run x WHERE x.report_id=rpt.report_id
 ORDER BY x.execution_no DESC LIMIT 1
) gr ON TRUE
LEFT JOIN public.report_snapshot rs ON rs.report_id=rpt.report_id AND rs.is_active
LEFT JOIN LATERAL (
 SELECT x.reason_code,x.status FROM public.reminder_dispatch x
 WHERE x.assessment_round_id=a.assessment_round_id AND x.user_id=a.user_id
 ORDER BY x.sent_at DESC NULLS LAST,x.created_at DESC LIMIT 1
) notify ON TRUE
LEFT JOIN LATERAL (
 SELECT x.* FROM public.organization_policy x WHERE x.org_id=a.org_id AND x.status='ACTIVE'
 ORDER BY x.policy_version DESC LIMIT 1
) pol ON TRUE
LEFT JOIN LATERAL (
 SELECT COUNT(*)::integer completed_count FROM public.measurement_attempt x
 WHERE x.assessment_round_id=a.assessment_round_id AND x.user_id=a.user_id
   AND x.attempt_type='REVIEW' AND x.status='COMPLETED'
) rvc ON TRUE
LEFT JOIN LATERAL (
 SELECT x.manager_user_id,u.name manager_name FROM public.manager_assignment x
 JOIN public.app_user u ON u.user_id=x.manager_user_id
 WHERE x.class_id=a.class_id AND x.status='ACTIVE'
 ORDER BY x.assigned_at DESC LIMIT 1
) mgr ON TRUE;


CREATE OR REPLACE VIEW public.manager_project_result_view (
    org_id,
    cohort_id,
    project_id,
    assessment_round_id,
    class_id,
    team_id,
    user_id,
    report_id,
    report_type,
    lifecycle_status,
    latest_generation_status,
    report_snapshot_id,
    completion_status,
    published_at,
    trainee_release_status,
    trainee_disclosure_scope,
    eligible_trainee_count,
    assessed_trainee_count,
    sample_count,
    missing_count,
    is_provisional,
    attempt_id,
    validity_review_status,
    terminal_reason_code,
    problem_id,
    problem_scope,
    project_verification_concept_id,
    concept_display_name_snapshot,
    concept_display_order,
    highest_reached_level,
    result_display_status,
    axis_code,
    stage_status,
    answer_attempt_count,
    score,
    assistance_display_code,
    review_target_status,
    internal_evidence_available,
    trainee_evidence_available,
    aggregation_status,
    as_of_at,
    calculation_version
) AS
SELECT
    (rpt.org_id)::UUID AS org_id,
    (rpt.cohort_id)::UUID AS cohort_id,
    (ar.project_id)::UUID AS project_id,
    (rpt.assessment_round_id)::UUID AS assessment_round_id,
    (pm.class_id)::UUID AS class_id,
    (tm.team_id)::UUID AS team_id,
    (rpt.user_id)::UUID AS user_id,
    (rpt.report_id)::UUID AS report_id,
    (rpt.report_type)::VARCHAR(100) AS report_type,
    (rpt.lifecycle_status)::VARCHAR(30) AS lifecycle_status,
    (gr.status)::VARCHAR(100) AS latest_generation_status,
    (rs.snapshot_id)::UUID AS report_snapshot_id,
    (rs.completion_status)::VARCHAR(100) AS completion_status,
    (rpt.published_at)::TIMESTAMPTZ AS published_at,
    (rpt.trainee_release_status)::VARCHAR(100) AS trainee_release_status,
    (rpt.trainee_disclosure_scope)::VARCHAR(100) AS trainee_disclosure_scope,
    (COALESCE(rs.sample_count,0)+COALESCE(rs.missing_count,0))::INTEGER AS eligible_trainee_count,
    (COALESCE(rs.sample_count,0))::INTEGER AS assessed_trainee_count,
    (rs.sample_count)::INTEGER AS sample_count,
    (rs.missing_count)::INTEGER AS missing_count,
    (rs.completion_status='PARTIAL' OR rpt.published_at IS NULL)::BOOLEAN AS is_provisional,
    (ma.attempt_id)::UUID AS attempt_id,
    (ma.validity_review_status)::VARCHAR(100) AS validity_review_status,
    (ma.terminal_reason_code)::VARCHAR(100) AS terminal_reason_code,
    (ap.problem_id)::UUID AS problem_id,
    (ap.problem_scope)::VARCHAR(100) AS problem_scope,
    (ap.project_verification_concept_id)::UUID AS project_verification_concept_id,
    (COALESCE(re.subject_display_snapshot->>'conceptName',tch.canonical_name))::VARCHAR(200) AS concept_display_name_snapshot,
    (COALESCE(rm.display_order,pvc.sequence_no))::INTEGER AS concept_display_order,
    (CASE ap.best_success_stage WHEN 'L4' THEN 4 WHEN 'L3' THEN 3 WHEN 'L2' THEN 2 WHEN 'L1' THEN 1 ELSE 0 END)::INTEGER AS highest_reached_level,
    (CASE WHEN ap.generation_status='NOT_GENERATED' THEN 'NO_PROBLEM' WHEN ma.validity_review_status='CONFIRMED_INVALID' THEN 'INVALID' WHEN ma.status='COMPLETED' THEN 'AVAILABLE' ELSE 'PENDING' END)::VARCHAR(100) AS result_display_status,
    (ps.axis_code)::VARCHAR(10) AS axis_code,
    (ps.status)::VARCHAR(100) AS stage_status,
    ((ps.question_answer_text IS NOT NULL)::integer+(ps.first_hint_answer_text IS NOT NULL)::integer+(ps.second_hint_answer_text IS NOT NULL)::integer)::INTEGER AS answer_attempt_count,
    (COALESCE(ps.second_hint_score,ps.first_hint_score,ps.question_score))::NUMERIC(18,6) AS score,
    (CASE WHEN ps.second_hint_answer_text IS NOT NULL THEN 'SECOND_HINT' WHEN ps.first_hint_answer_text IS NOT NULL THEN 'FIRST_HINT' ELSE 'NONE' END)::VARCHAR(100) AS assistance_display_code,
    (CASE WHEN re.evidence_id IS NOT NULL AND re.evidence_category='ANSWER_EXCERPT' THEN 'TARGET' ELSE 'NOT_TARGET' END)::VARCHAR(100) AS review_target_status,
    (re.evidence_id IS NOT NULL)::BOOLEAN AS internal_evidence_available,
    (re.evidence_id IS NOT NULL AND rpt.published_at IS NOT NULL)::BOOLEAN AS trainee_evidence_available,
    (COALESCE(rm.aggregation_status,'SINGLE_SOURCE'))::VARCHAR(20) AS aggregation_status,
    (rs.as_of_at)::TIMESTAMPTZ AS as_of_at,
    (rs.calculation_version)::INTEGER AS calculation_version
FROM public.report rpt
JOIN public.report_snapshot rs ON rs.report_id=rpt.report_id AND rs.is_active
LEFT JOIN LATERAL (
 SELECT x.* FROM public.report_generation_run x WHERE x.report_id=rpt.report_id ORDER BY x.execution_no DESC LIMIT 1
) gr ON TRUE
LEFT JOIN public.project_assessment_round ar ON ar.assessment_round_id=rpt.assessment_round_id
LEFT JOIN public.measurement_attempt ma ON ma.assessment_round_id=rpt.assessment_round_id AND ma.user_id=rpt.user_id AND ma.attempt_type='INITIAL'
LEFT JOIN public.project_membership pm ON pm.project_id=ar.project_id AND pm.user_id=rpt.user_id
LEFT JOIN LATERAL (
 SELECT x.team_id FROM public.team_membership x WHERE x.project_membership_id=pm.project_membership_id
 AND x.from_at<=COALESCE(ma.assessment_open_at,rs.as_of_at) AND (x.to_at IS NULL OR x.to_at>COALESCE(ma.assessment_open_at,rs.as_of_at))
 ORDER BY x.from_at DESC LIMIT 1
) tm ON TRUE
LEFT JOIN public.assessment_problem ap ON ap.measurement_attempt_id=ma.attempt_id OR ap.code_analysis_id=ma.code_analysis_id
LEFT JOIN public.problem_stage ps ON ps.problem_id=ap.problem_id
LEFT JOIN public.project_verification_concept pvc ON pvc.project_concept_id=ap.project_verification_concept_id
LEFT JOIN public.teaches tch ON tch.teaches_id=pvc.teaches_id
LEFT JOIN public.report_metric rm ON rm.snapshot_id=rs.snapshot_id AND (rm.project_verification_concept_id=ap.project_verification_concept_id OR rm.metric_grain_code='REPORT')
LEFT JOIN public.report_evidence re ON re.snapshot_id=rs.snapshot_id AND (re.problem_id=ap.problem_id OR re.problem_stage_id=ps.problem_stage_id)
WHERE rpt.user_id IS NOT NULL;


CREATE OR REPLACE VIEW public.trainee_report_problem_view (
    user_id,
    report_id,
    snapshot_id,
    problem_id,
    problem_stage_id,
    project_verification_concept_id,
    concept_display_name,
    concept_display_order,
    problem_scope,
    axis_code,
    confirmed_through_axis_code,
    next_unconfirmed_axis_code,
    reach_display_code,
    answer_count,
    own_answer_items,
    curriculum_location,
    result_explanation,
    answer_excerpt,
    review_required,
    review_status,
    review_attempt_id,
    review_before_after_items,
    has_explanation_content,
    explanation_status,
    can_view_explanation
) AS
WITH review_map AS (
 SELECT source_attempt_id,
   (ARRAY_AGG(attempt_id ORDER BY attempt_sequence_no DESC,updated_at DESC) FILTER (WHERE attempt_type='REVIEW'))[1] review_attempt_id
 FROM public.measurement_attempt GROUP BY source_attempt_id
)
SELECT
    (rpt.user_id)::UUID AS user_id,
    (rpt.report_id)::UUID AS report_id,
    (rs.snapshot_id)::UUID AS snapshot_id,
    (re.problem_id)::UUID AS problem_id,
    (re.problem_stage_id)::UUID AS problem_stage_id,
    (ap.project_verification_concept_id)::UUID AS project_verification_concept_id,
    (COALESCE(re.subject_display_snapshot->>'conceptName',t.canonical_name))::VARCHAR(200) AS concept_display_name,
    (COALESCE(re.display_order,pvc.sequence_no))::INTEGER AS concept_display_order,
    (ap.problem_scope)::VARCHAR(100) AS problem_scope,
    (COALESCE(re.axis_code,ps.axis_code))::VARCHAR(10) AS axis_code,
    (ap.best_success_stage)::VARCHAR(100) AS confirmed_through_axis_code,
    (CASE ap.best_success_stage WHEN 'L1' THEN 'L2' WHEN 'L2' THEN 'L3' WHEN 'L3' THEN 'L4' WHEN 'L4' THEN NULL ELSE 'L1' END)::VARCHAR(100) AS next_unconfirmed_axis_code,
    (COALESCE(ap.best_success_stage,'L0'))::VARCHAR(100) AS reach_display_code,
    ((ps.question_answer_text IS NOT NULL)::integer+(ps.first_hint_answer_text IS NOT NULL)::integer+(ps.second_hint_answer_text IS NOT NULL)::integer)::INTEGER AS answer_count,
    (JSONB_BUILD_ARRAY(
 JSONB_BUILD_OBJECT('type','QUESTION','answer',ps.question_answer_text,'score',ps.question_score,'passed',ps.question_passed),
 JSONB_BUILD_OBJECT('type','FIRST_HINT','answer',ps.first_hint_answer_text,'score',ps.first_hint_score,'passed',ps.first_hint_passed),
 JSONB_BUILD_OBJECT('type','SECOND_HINT','answer',ps.second_hint_answer_text,'score',ps.second_hint_score,'passed',ps.second_hint_passed)))::JSONB AS own_answer_items,
    (COALESCE(re.trace_payload->'curriculumLocation','{}'::jsonb))::TEXT AS curriculum_location,
    (re.evidence_summary)::TEXT AS result_explanation,
    (re.quote_excerpt)::TEXT AS answer_excerpt,
    (re.decision_code='REVIEW_REQUIRED')::BOOLEAN AS review_required,
    (COALESCE(re.decision_code,'NOT_REQUIRED'))::VARCHAR(100) AS review_status,
    (rm.review_attempt_id)::UUID AS review_attempt_id,
    (COALESCE(re.trace_payload->'reviewBeforeAfterItems','[]'::jsonb))::JSONB AS review_before_after_items,
    (re.evidence_summary IS NOT NULL OR re.quote_excerpt IS NOT NULL)::BOOLEAN AS has_explanation_content,
    (CASE WHEN re.evidence_id IS NULL THEN 'UNAVAILABLE' WHEN re.evidence_summary IS NULL AND re.quote_excerpt IS NULL THEN 'EMPTY' ELSE 'AVAILABLE' END)::VARCHAR(100) AS explanation_status,
    (rpt.published_at IS NOT NULL)::BOOLEAN AS can_view_explanation
FROM public.report rpt
JOIN public.report_snapshot rs ON rs.report_id=rpt.report_id AND rs.is_active
JOIN public.report_evidence re ON re.snapshot_id=rs.snapshot_id
LEFT JOIN public.assessment_problem ap ON ap.problem_id=re.problem_id
LEFT JOIN public.problem_stage ps ON ps.problem_stage_id=re.problem_stage_id
LEFT JOIN public.project_verification_concept pvc ON pvc.project_concept_id=ap.project_verification_concept_id
LEFT JOIN public.teaches t ON t.teaches_id=pvc.teaches_id
LEFT JOIN public.measurement_attempt initial_ma ON initial_ma.attempt_id=ap.measurement_attempt_id
LEFT JOIN review_map rm ON rm.source_attempt_id=initial_ma.attempt_id
WHERE rpt.user_id IS NOT NULL AND re.evidence_category IN ('ANSWER_EXCERPT','RESULT_EXPLANATION','CURRICULUM_LOCATION');


CREATE OR REPLACE VIEW public.trainee_report_round_view (
    org_id,
    cohort_id,
    user_id,
    project_id,
    project_name,
    assessment_round_id,
    round_name,
    round_no,
    round_ended_at,
    attempt_id,
    attempt_status,
    terminal_reason_code,
    validity_review_status,
    report_id,
    snapshot_id,
    report_generation_status,
    trainee_release_status,
    trainee_disclosure_scope,
    round_report_view_status,
    report_publish_not_before_at,
    published_at,
    trainee_released_at,
    review_status,
    review_due_at,
    can_view_report,
    can_contact_manager,
    as_of_at
) AS
SELECT
    (rpt.org_id)::UUID AS org_id,
    (rpt.cohort_id)::UUID AS cohort_id,
    (rpt.user_id)::UUID AS user_id,
    (p.project_id)::UUID AS project_id,
    (p.name)::VARCHAR(200) AS project_name,
    (r.assessment_round_id)::UUID AS assessment_round_id,
    (r.round_name)::VARCHAR(200) AS round_name,
    (r.round_no)::INTEGER AS round_no,
    (COALESCE(ma.terminal_at,r.report_publish_not_before_at))::TIMESTAMPTZ AS round_ended_at,
    (ma.attempt_id)::UUID AS attempt_id,
    (ma.status)::VARCHAR(100) AS attempt_status,
    (ma.terminal_reason_code)::VARCHAR(100) AS terminal_reason_code,
    (ma.validity_review_status)::VARCHAR(100) AS validity_review_status,
    (rpt.report_id)::UUID AS report_id,
    (rs.snapshot_id)::UUID AS snapshot_id,
    (gr.status)::VARCHAR(100) AS report_generation_status,
    (rpt.trainee_release_status)::VARCHAR(100) AS trainee_release_status,
    (rpt.trainee_disclosure_scope)::VARCHAR(100) AS trainee_disclosure_scope,
    (CASE WHEN rpt.published_at IS NOT NULL THEN 'VIEWABLE'
 WHEN gr.status IN ('QUEUED','RUNNING','RETRYING') THEN 'GENERATING'
 WHEN rpt.report_id IS NULL THEN 'NOT_CREATED' ELSE 'WITHHELD' END)::VARCHAR(100) AS round_report_view_status,
    (r.report_publish_not_before_at)::TIMESTAMPTZ AS report_publish_not_before_at,
    (rpt.published_at)::TIMESTAMPTZ AS published_at,
    (rpt.trainee_released_at)::TIMESTAMPTZ AS trainee_released_at,
    (rev.status)::VARCHAR(100) AS review_status,
    (rev.review_due_at)::TIMESTAMPTZ AS review_due_at,
    (rpt.published_at IS NOT NULL AND rs.snapshot_id IS NOT NULL)::BOOLEAN AS can_view_report,
    (TRUE)::BOOLEAN AS can_contact_manager,
    (COALESCE(rs.as_of_at,CURRENT_TIMESTAMP))::TIMESTAMPTZ AS as_of_at
FROM public.report rpt
JOIN public.project_assessment_round r ON r.assessment_round_id=rpt.assessment_round_id
JOIN public.project p ON p.project_id=r.project_id
LEFT JOIN public.measurement_attempt ma ON ma.assessment_round_id=r.assessment_round_id AND ma.user_id=rpt.user_id AND ma.attempt_type='INITIAL'
LEFT JOIN public.report_snapshot rs ON rs.report_id=rpt.report_id AND rs.is_active
LEFT JOIN LATERAL (
 SELECT x.* FROM public.report_generation_run x WHERE x.report_id=rpt.report_id ORDER BY x.execution_no DESC LIMIT 1
) gr ON TRUE
LEFT JOIN LATERAL (
 SELECT x.* FROM public.measurement_attempt x WHERE x.source_attempt_id=ma.attempt_id AND x.attempt_type='REVIEW'
 ORDER BY x.attempt_sequence_no DESC LIMIT 1
) rev ON TRUE
WHERE rpt.user_id IS NOT NULL;


CREATE OR REPLACE VIEW public.trainee_report_view (
    org_id,
    cohort_id,
    user_id,
    project_id,
    assessment_round_id,
    project_name,
    round_name,
    report_id,
    report_type,
    lifecycle_status,
    report_generation_status,
    snapshot_id,
    completion_status,
    published_at,
    trainee_release_status,
    trainee_disclosure_scope,
    trainee_released_at,
    round_report_view_status,
    can_view_report,
    can_view_own_answers,
    can_view_curriculum_location,
    concept_items,
    review_summary,
    as_of_at,
    calculation_version
) AS
SELECT
    (rpt.org_id)::UUID AS org_id,
    (rpt.cohort_id)::UUID AS cohort_id,
    (rpt.user_id)::UUID AS user_id,
    (p.project_id)::UUID AS project_id,
    (rpt.assessment_round_id)::UUID AS assessment_round_id,
    (p.name)::VARCHAR(200) AS project_name,
    (r.round_name)::VARCHAR(200) AS round_name,
    (rpt.report_id)::UUID AS report_id,
    (rpt.report_type)::VARCHAR(100) AS report_type,
    (rpt.lifecycle_status)::VARCHAR(30) AS lifecycle_status,
    (gr.status)::VARCHAR(100) AS report_generation_status,
    (rs.snapshot_id)::UUID AS snapshot_id,
    (rs.completion_status)::VARCHAR(100) AS completion_status,
    (rpt.published_at)::TIMESTAMPTZ AS published_at,
    (rpt.trainee_release_status)::VARCHAR(100) AS trainee_release_status,
    (rpt.trainee_disclosure_scope)::VARCHAR(100) AS trainee_disclosure_scope,
    (rpt.trainee_released_at)::TIMESTAMPTZ AS trainee_released_at,
    (CASE WHEN rpt.published_at IS NOT NULL AND rs.snapshot_id IS NOT NULL THEN 'VIEWABLE'
 WHEN gr.status IN ('QUEUED','RUNNING','RETRYING') THEN 'GENERATING'
 WHEN rpt.published_at IS NULL THEN 'NOT_PUBLISHED' ELSE 'WITHHELD' END)::VARCHAR(100) AS round_report_view_status,
    (rpt.published_at IS NOT NULL AND rs.snapshot_id IS NOT NULL)::BOOLEAN AS can_view_report,
    (rpt.published_at IS NOT NULL)::BOOLEAN AS can_view_own_answers,
    (rpt.published_at IS NOT NULL)::BOOLEAN AS can_view_curriculum_location,
    (COALESCE(rs.summary_payload->'conceptItems','[]'::jsonb))::JSONB AS concept_items,
    (rs.summary_payload->>'reviewSummary')::TEXT AS review_summary,
    (rs.as_of_at)::TIMESTAMPTZ AS as_of_at,
    (rs.calculation_version)::INTEGER AS calculation_version
FROM public.report rpt
LEFT JOIN LATERAL (
 SELECT x.* FROM public.report_generation_run x WHERE x.report_id=rpt.report_id ORDER BY x.execution_no DESC LIMIT 1
) gr ON TRUE
LEFT JOIN public.report_snapshot rs ON rs.report_id=rpt.report_id AND rs.is_active
LEFT JOIN public.project_assessment_round r ON r.assessment_round_id=rpt.assessment_round_id
LEFT JOIN public.project p ON p.project_id=r.project_id
WHERE rpt.user_id IS NOT NULL;



-- -----------------------------------------------------------------------------
-- 5. 적용 확인
-- -----------------------------------------------------------------------------

-- 5-1. 조합 제약이 없어졌고 값 집합 제약 둘은 남아 있어야 한다 (2행)
SELECT conname
  FROM pg_constraint
 WHERE conrelid = 'report'::regclass
   AND conname LIKE 'ck_report_trainee_%'
 ORDER BY conname;

-- 5-2. 기본값이 붙었는지 (RELEASED · FULL 2행)
SELECT column_name, column_default
  FROM information_schema.columns
 WHERE table_name = 'report'
   AND column_name IN ('trainee_release_status', 'trainee_disclosure_scope')
 ORDER BY column_name;

-- 5-3. 남은 행이 없어야 한다 (0행)
SELECT report_id, trainee_release_status, trainee_disclosure_scope
  FROM report
 WHERE trainee_release_status <> 'RELEASED'
    OR trainee_disclosure_scope IS DISTINCT FROM 'FULL';

-- 5-4. 발행 전 리포트가 "볼 수 있다"로 새지 않는지 (0행)
--      3절 백필로 모든 행이 RELEASED가 되므로, 이 확인이 4절 뷰 수정의 실효를 본다.
SELECT r.report_id, r.published_at, v.can_view_report
  FROM report r
  JOIN trainee_report_view v ON v.report_id = r.report_id
 WHERE r.published_at IS NULL
   AND v.can_view_report;


-- -----------------------------------------------------------------------------
-- 6. 2단계 — 컬럼 정리 (이 파일에서는 하지 않는다)
-- -----------------------------------------------------------------------------

/*
 * 아래는 뷰 컬럼 목록이 함께 바뀌는 작업이라 별도 배포로 뺀다. 순서가 있다.
 *
 *   ① 백엔드에서 패스스루 컬럼을 읽는 곳을 먼저 없앤다
 *      - JdbcTraineeHomeRoundRepository 가 v.trainee_release_status 를 읽는다
 *      - TraineeReportQueryRepository / JdbcTraineeReportQueryRepository 의 조회 컬럼
 *   ② DROP VIEW 후 컬럼을 뺀 목록으로 재생성한다 (CREATE OR REPLACE 로는 못 뺀다)
 *        trainee_home_round_view · manager_project_result_view · trainee_report_archive_view
 *        trainee_report_problem_view · trainee_report_round_view · trainee_report_view
 *   ③ ALTER TABLE report
 *        DROP CONSTRAINT ck_report_trainee_release_status,
 *        DROP CONSTRAINT ck_report_trainee_disclosure_scope,
 *        DROP CONSTRAINT fk_report_trainee_released_by,
 *        DROP COLUMN trainee_release_status,
 *        DROP COLUMN trainee_disclosure_scope,
 *        DROP COLUMN trainee_released_at,
 *        DROP COLUMN trainee_released_by;
 *
 * ①을 건너뛰고 ②를 하면 조회부가 "column does not exist" 로 깨진다.
 * organization_policy.default_disclosure_scope 와 cohort.disclosure_scope 는 그대로 둔다 —
 * 리포트 공개와 무관한 기관 정책 컬럼이고, 지금은 아무 데도 반영되지 않는 설정으로만 남는다.
 */
