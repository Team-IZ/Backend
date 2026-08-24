-- 2026-08-25 · 결과 없는 성공으로 닫힌 응시가 교육생 홈에서 「분석 중」으로 보인다
--
-- trainee_home_round_view 재생성 (정본: docs/table-definition/PostgreSQL_View_v08.sql)
-- 제안서: 교육생홈_분석실패_대표상태_수정_제안서.md (손진원 → 박종호)
--
-- 배경
--   AI 분석은 SUCCEEDED 로 끝났는데 code_analysis 적재가 실패하는 경로가 있다
--   (AnalysisBatchService.recordResult → closeAttemptsForResultlessSuccess). 비용이 이미 나간
--   job 을 FAILED 로 되돌리지 않고 대신 응시를 닫는데, 그때 남는 흔적이 이 모양이다.
--
--     analysis_job.status = 'SUCCEEDED'  AND  analysis_job.analysis_id IS NULL
--     measurement_attempt.status = 'FAILED'  AND  terminal_reason_code = 'ANALYSIS_FAILED'
--
--   이 뷰의 판정식 넷이 분석 실패를 analysis_job.status 로만 읽어서 위 조합을 못 본다.
--   primary_attempt_status='FAILED' 를 잡는 WHEN 이 어느 CASE 에도 없어 그대로 ELSE 까지 흘러
--   representative_status='ANALYZING' · default_action_code='NONE' 이 된다. 프론트는 NONE 이면
--   버튼을 그리지 않으므로 학생은 「코드 분석이 진행 중이에요」 + 버튼 없음 카드를 보고
--   아무것도 하지 못한다 — 재제출하면 복구되는 상황인데 재제출 버튼이 없다.
--
--   실측: 신세림(t243@naver.com) · 7기 J반 · 미프 4차. 같은 회차의 리포트 화면은
--   measurement_attempt.terminal_reason_code 를 직접 읽어(TraineeReportServiceImpl:243)
--   「분석 실패」라고 말한다. 두 화면이 같은 학생을 두고 다른 말을 하고 있었다.
--
--   근본 원인은 별건으로 이미 끝났다 — 8/24 미프 4차 15건은 AI 가 정상 범위로 만드는
--   "FAIL + evidence 없음" 조합을 ck_project_requirement_assessment_result_fields 가 거부해
--   적재 트랜잭션이 통째로 롤백된 것이었고, 그 CHECK 는
--   2026-08-25_requirement_assessment_fail_allows_no_evidence.sql 로 완화해 머지했다.
--   다만 그때 이미 닫힌 응시는 저절로 되살아나지 않는다. 이 마이그레이션은 그 학생들이
--   상황을 정확히 보고 재제출로 행동할 수 있게 만드는 몫이다.
--
-- 왜 2026-08-21_fix_trainee_home_analysis_failed_fallthrough.sql 로 끝나지 않았나
--   그 파일은 같은 결함을 진단했지만 운영 DB 에 적용되지 않았다(정본 View_v08 이 8/21 RDS
--   덤프인데 그 변경이 없다). 게다가 본문을 8/16 파일에서 옮겨 적어서, 그대로 적용하면
--   8/19 report_disclosure_removal 의 published_at 판정이 trainee_release_status 로 되돌아간다.
--   그래서 그 파일을 적용하지 않고, 현재 정본(=운영 DB 현재 정의)을 기준으로 다시 만든다.
--   적용 후 그 파일은 이 마이그레이션이 대신하므로 실행하지 않는다.
--
-- 변경 네 곳 — 전부 "analysis_job.status 만 보던 자리"에 응시 원장을 함께 보게 하는 것이다
--
--   1) analysis_phase          — 'COMPLETED' 가 나가 "분석은 됐다"는 거짓을 말하던 자리
--   2) analysis_failure_code   — 같은 기준으로 ANALYSIS_FAILED 를 내보낸다
--   3) warning_codes           — 「분석 실패」 배지. 프론트 WARNING_LABELS 에 이미 있는 값이다
--   4) representative_status · default_action_code
--                              — 배지가 ANALYSIS_FAILED 가 되고, 제출 마감 전이면 재제출 버튼이
--                                살아난다(RESUBMIT_ZIP · RESUBMIT_REPOSITORY). 마감 후에는
--                                CONTACT_MANAGER 다.
--
--   ANALYSIS_FAILED 는 새로 만드는 값이 아니라 기존 계약값이다
--   (TraineeRepresentativeStatus.ANALYSIS_FAILED). 프론트 수정이 필요 없다.
--
-- 🔴 제안서와 한 곳 다르다 — 재분석 중 가드
--   제안서 5-1·5-2 는 terminal_reason_code='ANALYSIS_FAILED' 만 보라고 썼는데, 그러면 학생이
--   재제출한 직후가 문제가 된다. openForTeam·markAttemptsAnalyzing 은 FAILED 응시를 건드리지
--   않으므로 terminal_reason_code 는 markAttemptsReady 가 지울 때까지 'ANALYSIS_FAILED' 로
--   남고, 그 사이 analysis_status 는 새 job 의 QUEUED/RUNNING 이 된다. 방금 재제출한 학생에게
--   「분석 실패 + 재제출」 카드를 다시 보여주면 중복 제출을 유도한다.
--   그래서 새로 넣는 판정마다
--     AND a.analysis_status::text IS DISTINCT FROM 'QUEUED'::text
--     AND a.analysis_status::text IS DISTINCT FROM 'RUNNING'::text
--   를 함께 건다. 재분석이 도는 동안에는 종전대로 ANALYZING · WAIT_FOR_ANALYSIS 다.
--   ( = ANY 대신 IS DISTINCT FROM 두 줄인 이유는 analysis_status 가 NULL 일 때 <> ALL 이
--     true 가 아니라 NULL 이 되어 절이 통째로 죽기 때문이다.)
--   analysis_phase 만 가드를 안 쓴다 — 새 WHEN 을 QUEUED/RUNNING 분기 '아래' 에 두어
--   위치가 같은 일을 한다.
--
-- 판정 순서 — 앞선 절을 뺏지 않는다
--   representative_status 의 넓힌 절은 ASSESSMENT_WINDOW_CLOSED 보다 아래에 그대로 둔다.
--   markAttemptsAnalysisFailed 는 status IN ('NOT_STARTED','SUBMITTED','ANALYZING') 인 응시만
--   닫고, assessment_open_at·close_at 은 markAttemptsReady 와 ensureAttempts 가 SESSION_READY
--   와 함께만 쓴다. 그래서 ANALYSIS_FAILED 로 닫힌 응시는 창이 NULL 이라 그 절에 안 걸린다
--   (8/16 시드도 이 상태에 창을 NULL 로 심는다). 나머지 절도 COMPLETED·세션 진행·
--   SESSION_READY/NOT_STARTED 라 status='FAILED' 와 배타적이다.
--   즉 지금 ELSE 'ANALYZING' 으로 떨어지던 행만 옮겨간다.
--
-- 출력 컬럼 목록·이름·타입·순서가 그대로이므로 DROP 없이 CREATE OR REPLACE 로 교체된다.
--
-- 적용 전 확인 — 대상이 몇 명인가
--   SELECT co.name AS cohort, cl.name AS class_name, u.name, u.email,
--          v.round_name, v.representative_status, v.default_action_code
--     FROM public.trainee_home_round_view v
--     JOIN public.app_user u ON u.user_id = v.trainee_user_id
--     LEFT JOIN public.cohort co ON co.cohort_id = v.cohort_id
--     LEFT JOIN public.class cl ON cl.class_id = v.class_id_at_round
--    WHERE v.initial_terminal_reason_code = 'ANALYSIS_FAILED'
--      AND v.representative_status = 'ANALYZING'
--    ORDER BY co.name, cl.name, u.name;
--
-- 적용 후 확인 ① — 재분석 중이 아닌 ANALYSIS_FAILED 는 하나도 안 남아야 한다
--   SELECT count(*) AS should_be_zero
--     FROM public.trainee_home_round_view
--    WHERE initial_terminal_reason_code = 'ANALYSIS_FAILED'
--      AND analysis_job_status NOT IN ('QUEUED', 'RUNNING')
--      AND representative_status = 'ANALYZING';
--
-- 적용 후 확인 ② — 옮겨간 행이 네 컬럼 모두 제대로 된 값을 갖는지
--   SELECT representative_status, default_action_code, analysis_phase, warning_codes, count(*)
--     FROM public.trainee_home_round_view
--    WHERE initial_terminal_reason_code = 'ANALYSIS_FAILED'
--    GROUP BY 1, 2, 3, 4 ORDER BY 5 DESC;
--
--   기대 — representative_status='ANALYSIS_FAILED' · analysis_phase='FAILED' ·
--   warning_codes 에 'ANALYSIS_FAILED' 포함 · default_action_code 는 제출 마감 전이면
--   RESUBMIT_ZIP|RESUBMIT_REPOSITORY, 지났으면 CONTACT_MANAGER.
--   재분석이 도는 행이 있으면 그것만 ANALYZING · WAIT_FOR_ANALYSIS 로 남는다.
--
-- 대조군 — 그 밖의 행은 분포가 하나도 달라지지 않아야 한다(적용 전후 결과가 같아야 한다)
--   SELECT representative_status, default_action_code, count(*)
--     FROM public.trainee_home_round_view
--    WHERE initial_terminal_reason_code IS DISTINCT FROM 'ANALYSIS_FAILED'
--      AND analysis_job_status IS DISTINCT FROM 'FAILED'
--    GROUP BY 1, 2 ORDER BY 3 DESC;
--
-- 되돌리기 — docs/table-definition/PostgreSQL_View_v08.sql 의 이전 정의로 CREATE OR REPLACE.
--
-- 남은 일(이 마이그레이션 밖)
--   · initial_terminal_reason_code 가 뷰에는 있는데 JdbcTraineeHomeRoundRepository 의 SELECT
--     목록에 빠져 응답에 안 나온다. 백엔드 조회부 건이라 손진원님이 처리한다(제안서 7-4).
--   · AssessmentRoundController 의 analysisPhase↔analysisJobStatus 대응표에 이번에 생기는
--     조합(FAILED ↔ SUCCEEDED)을 한 줄 더해야 한다.
--   · 이 뷰의 RESUME_ASSESSMENT 절에는 응시 창 마감 가드가 없다. 2026-08-13 에 넣었던 가드가
--     8/19 report_disclosure_removal 이 본문을 옮겨 적으면서 조용히 빠졌다. 이번 건과 원인이
--     달라 섞지 않는다 — 별도 마이그레이션으로 되돌려야 한다.

BEGIN;

CREATE OR REPLACE VIEW public.trainee_home_round_view
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

COMMIT;
