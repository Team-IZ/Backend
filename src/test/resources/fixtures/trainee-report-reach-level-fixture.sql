-- 교육생 리포트 도달 단계(level) 검증용 최소 픽스처.
--
-- 미니프로젝트를 그대로 재현한다 — 문제 3개가 전부 problem_scope='TEAM_SHARED_PROBLEM'이고
-- best_success_stage 는 CHECK(ck_assessment_problem_best_success_stage_2)가 NULL 로 강제한다.
-- 즉 뷰의 reach_display_code 는 세 개념 모두 'L0'다. 그런데 실제 도달 단계는 셋이 다르다.
--
--   개념 A — evidence.trace_payload.reachedLevel = 3, problem_stage L1~L3 통과  → 3
--   개념 B — trace_payload 에 reachedLevel 없음, problem_stage L1·L2 통과       → 2 (폴백)
--   개념 C — trace_payload 에 reachedLevel 없음, 통과한 축 없음                 → 0
--
-- 종전 구현(reach_display_code)은 셋 다 0을 냈다(20차 R2).
--
-- 관련 없는 FK까지 채우지 않으려고 FK 트리거를 끈 세션에서 넣는다
-- (manager-roster-cohort-scope-fixture.sql 과 같은 방식이다).
SET session_replication_role = replica;

TRUNCATE organization, "role", app_user, cohort, class, project, project_assessment_round,
         team, submission, project_extraction_scope, code_analysis, measurement_attempt,
         assessment_session, assessment_problem, problem_stage, report, report_generation_run,
         report_snapshot, report_evidence CASCADE;

INSERT INTO organization (org_id, name, normalized_name, created_by) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '아이즈', 'ize',
     '00000000-0000-0000-0000-0000000000b1');

INSERT INTO "role" (role_id, code, name) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'TRAINEE', '교육생');

INSERT INTO app_user (user_id, org_id, role_id, email, normalized_email, name, status,
                      password_hash, password_changed_at) VALUES
    ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-0000000000c1', 'trainee-fixture@example.com',
     'trainee-fixture@example.com', '교육생1', 'ACTIVE', 'h', now());

INSERT INTO cohort (cohort_id, org_id, name, start_date, end_date, created_by,
                    disclosure_scope, disclosure_policy_id) VALUES
    ('00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-0000000000a1',
     '1기', DATE '2026-01-01', DATE '2026-12-31', '00000000-0000-0000-0000-0000000000b1',
     'COHORT', '00000000-0000-0000-0000-0000000000d9');

INSERT INTO class (class_id, org_id, cohort_id, name, capacity, created_by) VALUES
    ('00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-0000000000d1', '1반', 30, '00000000-0000-0000-0000-0000000000b1');

INSERT INTO project (project_id, org_id, cohort_id, name, sequence_no, project_category,
                     concept_source_mode, start_date, end_date, default_extraction_scope_code,
                     created_by) VALUES
    ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-0000000000d1', '미니프로젝트', 1, 'MINI_PROJECT',
     'PROJECT_FIXED', DATE '2026-02-01', DATE '2026-02-28', 'TOTAL',
     '00000000-0000-0000-0000-0000000000b1');

INSERT INTO project_assessment_round (assessment_round_id, project_id, org_id, cohort_id,
                                      round_no, round_name, trigger_type, submission_due_at,
                                      assessment_open_at, assessment_due_at,
                                      report_publish_mode, is_final, status, created_by, updated_by) VALUES
    ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-0000000000f1',
     '00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000d1',
     1, '미프 1차', 'MANUAL', TIMESTAMPTZ '2026-02-20T00:00:00Z',
     TIMESTAMPTZ '2026-02-20T00:00:00Z', TIMESTAMPTZ '2026-02-21T00:00:00Z',
     'ROUND_BATCH', TRUE, 'CLOSED', '00000000-0000-0000-0000-0000000000b1',
     '00000000-0000-0000-0000-0000000000b1');

INSERT INTO team (team_id, project_id, class_id, org_id, team_number, name,
                  min_member_count, max_member_count, created_by) VALUES
    ('00000000-0000-0000-0000-000000000111', '00000000-0000-0000-0000-0000000000f1',
     '00000000-0000-0000-0000-0000000000e1', '00000000-0000-0000-0000-0000000000a1',
     '1', '1팀', 1, 6, '00000000-0000-0000-0000-0000000000b1');

INSERT INTO submission (submission_id, org_id, team_id, assessment_round_id, method,
                        submitted_by, status, submitted_at, is_current) VALUES
    ('00000000-0000-0000-0000-000000000121', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000111', '00000000-0000-0000-0000-000000000101',
     'ZIP_WITH_GITLOG', '00000000-0000-0000-0000-0000000000b1', 'ACCEPTED',
     TIMESTAMPTZ '2026-02-19T00:00:00Z', TRUE);

INSERT INTO project_extraction_scope (extraction_scope_id, project_id, assessment_round_id,
                                      org_id, scope_code, version_no, effective_from, changed_by) VALUES
    ('00000000-0000-0000-0000-000000000131', '00000000-0000-0000-0000-0000000000f1',
     '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-0000000000a1',
     'TOTAL', 1, TIMESTAMPTZ '2026-02-01T00:00:00Z', '00000000-0000-0000-0000-0000000000b1');

INSERT INTO code_analysis (analysis_id, org_id, assessment_round_id, team_id,
                           source_submission_id, extraction_scope_id, status, analysis_document) VALUES
    ('00000000-0000-0000-0000-000000000141', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000111',
     '00000000-0000-0000-0000-000000000121', '00000000-0000-0000-0000-000000000131',
     'ACTIVE', '{}'::jsonb);

INSERT INTO measurement_attempt (attempt_id, org_id, cohort_id, assessment_round_id, project_id,
                                 user_id, attempt_type, attempt_sequence_no, status,
                                 validity_review_status, terminal_reason_code, terminal_at,
                                 code_analysis_id, source_submission_id) VALUES
    ('00000000-0000-0000-0000-000000000151', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-0000000000d1', '00000000-0000-0000-0000-000000000101',
     '00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-0000000000b1',
     'INITIAL', 1, 'COMPLETED', 'NOT_REQUIRED', 'COMPLETED',
     TIMESTAMPTZ '2026-02-21T00:00:00Z', '00000000-0000-0000-0000-000000000141',
     '00000000-0000-0000-0000-000000000121');

INSERT INTO assessment_session (session_id, org_id, attempt_id, status,
                                window_leave_count, total_away_seconds,
                                connection_loss_count, total_disconnected_seconds) VALUES
    ('00000000-0000-0000-0000-000000000161', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000151', 'COMPLETED', 0, 0, 0, 0);

-- 문제 3개. 전부 팀 공유 문제이므로 best_success_stage 는 NULL 로 강제되고
-- (ck_assessment_problem_best_success_stage_2), 같은 CHECK 가 project_verification_concept_id 를
-- 요구한다. 개념 이름은 뷰가 evidence 스냅샷에서 먼저 읽으므로 검증 개념 행 자체는 두지 않는다.
INSERT INTO assessment_problem (problem_id, org_id, code_analysis_id, project_verification_concept_id,
                                problem_scope, problem_no, generation_status, title,
                                source_snippet_key, code_language, source_path,
                                source_line_start, source_line_end, code_snippet_hash) VALUES
    ('00000000-0000-0000-0000-00000000017a', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000141', '00000000-0000-0000-0000-000000000191',
     'TEAM_SHARED_PROBLEM', 1, 'GENERATED', '예외 처리와 롤백 전략',
     'snippet-a', 'JAVA', 'src/A.java', 1, 20, 'hash-a'),
    ('00000000-0000-0000-0000-00000000017b', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000141', '00000000-0000-0000-0000-000000000192',
     'TEAM_SHARED_PROBLEM', 2, 'GENERATED', 'API 응답 계약 설계',
     'snippet-b', 'JAVA', 'src/B.java', 1, 20, 'hash-b'),
    ('00000000-0000-0000-0000-00000000017c', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000141', '00000000-0000-0000-0000-000000000193',
     'TEAM_SHARED_PROBLEM', 3, 'GENERATED', '영속성 매핑과 지연 로딩',
     'snippet-c', 'JAVA', 'src/C.java', 1, 20, 'hash-c');

-- 개념 A: L1~L3 통과, L4 미통과 → problem_stage 기준 3단.
INSERT INTO problem_stage (problem_stage_id, session_id, problem_id, axis_code,
                           question_sequence_no, question_text, first_hint_text, second_hint_text,
                           question_answer_text, question_score, question_passed, question_answered_at,
                           first_hint_answer_text, first_hint_score, first_hint_passed, first_hint_answered_at,
                           second_hint_answer_text, second_hint_score, second_hint_passed, second_hint_answered_at,
                           status) VALUES
    ('00000000-0000-0000-0000-0000000001a1', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017a', 'L1', 1, 'q', 'h1', 'h2', 'a', 4, TRUE,
     TIMESTAMPTZ '2026-02-21T00:00:00Z', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'PASSED'),
    ('00000000-0000-0000-0000-0000000001a2', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017a', 'L2', 2, 'q', 'h1', 'h2', 'a', 4, TRUE,
     TIMESTAMPTZ '2026-02-21T00:00:00Z', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'PASSED'),
    ('00000000-0000-0000-0000-0000000001a3', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017a', 'L3', 3, 'q', 'h1', 'h2', 'a', 4, TRUE,
     TIMESTAMPTZ '2026-02-21T00:00:00Z', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'PASSED'),
    ('00000000-0000-0000-0000-0000000001a4', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017a', 'L4', 4, 'q', 'h1', 'h2', 'a', 1, FALSE,
     TIMESTAMPTZ '2026-02-21T00:00:00Z', 'a', 1, FALSE, TIMESTAMPTZ '2026-02-21T00:00:00Z',
     'a', 1, FALSE, TIMESTAMPTZ '2026-02-21T00:00:00Z', 'NOT_PASSED');

-- 개념 B: L1·L2 통과 → 폴백 2단.
INSERT INTO problem_stage (problem_stage_id, session_id, problem_id, axis_code,
                           question_sequence_no, question_text, first_hint_text, second_hint_text,
                           question_answer_text, question_score, question_passed, question_answered_at,
                           first_hint_answer_text, first_hint_score, first_hint_passed, first_hint_answered_at,
                           second_hint_answer_text, second_hint_score, second_hint_passed, second_hint_answered_at,
                           status) VALUES
    ('00000000-0000-0000-0000-0000000001b1', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017b', 'L1', 1, 'q', 'h1', 'h2', 'a', 4, TRUE,
     TIMESTAMPTZ '2026-02-21T00:00:00Z', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'PASSED'),
    ('00000000-0000-0000-0000-0000000001b2', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017b', 'L2', 2, 'q', 'h1', 'h2', 'a', 4, TRUE,
     TIMESTAMPTZ '2026-02-21T00:00:00Z', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'PASSED'),
    ('00000000-0000-0000-0000-0000000001b3', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017b', 'L3', 3, 'q', 'h1', 'h2', 'a', 1, FALSE,
     TIMESTAMPTZ '2026-02-21T00:00:00Z', 'a', 1, FALSE, TIMESTAMPTZ '2026-02-21T00:00:00Z',
     'a', 1, FALSE, TIMESTAMPTZ '2026-02-21T00:00:00Z', 'NOT_PASSED');

-- 개념 C: 통과 축 없음 → 0단.
INSERT INTO problem_stage (problem_stage_id, session_id, problem_id, axis_code,
                           question_sequence_no, question_text, first_hint_text, second_hint_text,
                           question_answer_text, question_score, question_passed, question_answered_at,
                           first_hint_answer_text, first_hint_score, first_hint_passed, first_hint_answered_at,
                           second_hint_answer_text, second_hint_score, second_hint_passed, second_hint_answered_at,
                           status) VALUES
    ('00000000-0000-0000-0000-0000000001c1', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017c', 'L1', 1, 'q', 'h1', 'h2', 'a', 1, FALSE,
     TIMESTAMPTZ '2026-02-21T00:00:00Z', 'a', 1, FALSE, TIMESTAMPTZ '2026-02-21T00:00:00Z',
     'a', 1, FALSE, TIMESTAMPTZ '2026-02-21T00:00:00Z', 'NOT_PASSED');

INSERT INTO report (report_id, org_id, cohort_id, class_id, user_id, assessment_round_id,
                    report_type, lifecycle_status, trainee_release_status,
                    trainee_disclosure_scope, trainee_released_at, trainee_released_by,
                    published_at) VALUES
    ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-0000000000d1', NULL, '00000000-0000-0000-0000-0000000000b1',
     '00000000-0000-0000-0000-000000000101', 'TRAINEE_FINAL', 'ACTIVE', 'RELEASED',
     'FULL', TIMESTAMPTZ '2026-02-22T00:00:00Z', '00000000-0000-0000-0000-0000000000b1',
     TIMESTAMPTZ '2026-02-22T00:00:00Z');

INSERT INTO report_generation_run (generation_run_id, report_id, trigger_type, idempotency_key,
                                   calculation_version, status, execution_no, request_fingerprint,
                                   started_at, completed_at) VALUES
    ('00000000-0000-0000-0000-000000000211', '00000000-0000-0000-0000-000000000201',
     'ROUND_CLOSED', 'fixture-key-1', 1, 'COMPLETED', 1, repeat('0', 64),
     TIMESTAMPTZ '2026-02-22T00:00:00Z', TIMESTAMPTZ '2026-02-22T00:10:00Z');

INSERT INTO report_snapshot (snapshot_id, org_id, report_id, snapshot_version, as_of_at,
                             calculation_version, summary_payload, completion_status,
                             payload_hash, is_active, generation_run_id, sample_count, missing_count) VALUES
    ('00000000-0000-0000-0000-000000000221', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000201', 1, TIMESTAMPTZ '2026-02-22T00:10:00Z',
     1, '{}'::jsonb, 'FULL', repeat('0', 64), TRUE,
     '00000000-0000-0000-0000-000000000211', 3, 0);

-- 개념 카드 3장. A만 trace_payload 에 reachedLevel 을 갖는다.
INSERT INTO report_evidence (evidence_id, snapshot_id, problem_id, problem_stage_id,
                             evidence_category, subject_display_snapshot, axis_code, decision_code,
                             evidence_summary, display_order, filter_snapshot, policy_version,
                             trace_payload) VALUES
    ('00000000-0000-0000-0000-000000000231', '00000000-0000-0000-0000-000000000221',
     '00000000-0000-0000-0000-00000000017a', '00000000-0000-0000-0000-0000000001a4',
     'RESULT_EXPLANATION', '{"conceptName":"예외 처리와 롤백 전략"}'::jsonb, 'L4', 'NOT_REQUIRED',
     '실패 조건까지 짚었지만 대안 설계는 제시하지 못했습니다.', 1, '{}'::jsonb, 1,
     '{"reachedLevel":3,"curriculumLocation":{},"reviewBeforeAfterItems":[]}'::jsonb),
    ('00000000-0000-0000-0000-000000000232', '00000000-0000-0000-0000-000000000221',
     '00000000-0000-0000-0000-00000000017b', '00000000-0000-0000-0000-0000000001b3',
     'RESULT_EXPLANATION', '{"conceptName":"API 응답 계약 설계"}'::jsonb, 'L3', 'NOT_REQUIRED',
     '선택 이유까지 설명했지만 그 다음에서 막혔습니다.', 2, '{}'::jsonb, 1,
     '{"curriculumLocation":{},"reviewBeforeAfterItems":[]}'::jsonb),
    ('00000000-0000-0000-0000-000000000233', '00000000-0000-0000-0000-000000000221',
     '00000000-0000-0000-0000-00000000017c', '00000000-0000-0000-0000-0000000001c1',
     'RESULT_EXPLANATION', '{"conceptName":"영속성 매핑과 지연 로딩"}'::jsonb, 'L1', 'REVIEW_REQUIRED',
     '네 축 가운데 어느 것도 통과하지 못했습니다.', 3, '{}'::jsonb, 1,
     '{"curriculumLocation":{},"reviewBeforeAfterItems":[]}'::jsonb);
