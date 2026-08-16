-- 문제 종료 확정(closeProblem · end) 검증용 최소 픽스처.
--
-- 핵심은 **L1이 답변만 들어간 채 열려 있는 축**이다. 질문에 미달했는데 AI 커서가 옮겨 가
-- 힌트를 열지 않은 상태이며, 2026-08-17 CHECK 완화 이전에는 이 행에 줄 종료 상태가 없어
-- 영구히 IN_PROGRESS 로 남았다.
--
--   문제 1 (…17a)  L1 answered-open · L2 미착수 · L3 PASSED · L4 미착수   → closeProblem 대상
--   문제 2 (…17b)  L1 answered-open · L2 미착수                          → end 대상
--
-- 관련 없는 FK까지 채우지 않으려고 FK 트리거를 끈 세션에서 넣는다
-- (trainee-report-reach-level-fixture.sql 과 같은 방식이다). CHECK 는 그대로 살아 있으므로
-- 이 픽스처가 들어간다는 것 자체가 상태 조합이 유효하다는 뜻이다.
SET session_replication_role = replica;

TRUNCATE assessment_session, assessment_problem, problem_stage CASCADE;

INSERT INTO assessment_session (session_id, org_id, attempt_id, status, started_at,
                                window_leave_count, total_away_seconds,
                                connection_loss_count, total_disconnected_seconds) VALUES
    ('00000000-0000-0000-0000-000000000161', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000151', 'IN_PROGRESS',
     TIMESTAMPTZ '2026-02-21T00:00:00Z', 0, 0, 0, 0);

INSERT INTO assessment_problem (problem_id, org_id, code_analysis_id, project_verification_concept_id,
                                problem_scope, problem_no, generation_status, title,
                                source_snippet_key, code_language, source_path,
                                source_line_start, source_line_end, code_snippet_hash) VALUES
    ('00000000-0000-0000-0000-00000000017a', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000141', '00000000-0000-0000-0000-000000000191',
     'TEAM_SHARED_PROBLEM', 1, 'GENERATED', '문제1',
     'snippet-a', 'JAVA', 'src/A.java', 1, 20, 'hash-a'),
    ('00000000-0000-0000-0000-00000000017b', '00000000-0000-0000-0000-0000000000a1',
     '00000000-0000-0000-0000-000000000141', '00000000-0000-0000-0000-000000000192',
     'TEAM_SHARED_PROBLEM', 2, 'GENERATED', '문제2',
     'snippet-b', 'JAVA', 'src/B.java', 1, 20, 'hash-b');

-- 문제 1. L1 만 답변이 들어가 있고 힌트는 열지 않았다(question_passed=FALSE, 힌트 둘 NULL).
INSERT INTO problem_stage (problem_stage_id, session_id, problem_id, axis_code,
                           question_sequence_no, question_text, first_hint_text, second_hint_text,
                           question_answer_text, question_score, question_passed, question_answered_at,
                           status) VALUES
    ('00000000-0000-0000-0000-0000000001a1', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017a', 'L1', 1, 'q', 'h1', 'h2',
     'a', 1, FALSE, TIMESTAMPTZ '2026-02-21T00:10:00Z', 'IN_PROGRESS'),
    ('00000000-0000-0000-0000-0000000001a2', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017a', 'L2', 2, 'q', 'h1', 'h2',
     NULL, NULL, NULL, NULL, 'PREPARED'),
    ('00000000-0000-0000-0000-0000000001a3', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017a', 'L3', 3, 'q', 'h1', 'h2',
     'a', 4, TRUE, TIMESTAMPTZ '2026-02-21T00:20:00Z', 'PASSED'),
    ('00000000-0000-0000-0000-0000000001a4', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017a', 'L4', 4, 'q', 'h1', 'h2',
     NULL, NULL, NULL, NULL, 'PREPARED');

-- 문제 2. 세션 종료(end) 경로에서 한꺼번에 닫히는 몫이다.
INSERT INTO problem_stage (problem_stage_id, session_id, problem_id, axis_code,
                           question_sequence_no, question_text, first_hint_text, second_hint_text,
                           question_answer_text, question_score, question_passed, question_answered_at,
                           status) VALUES
    ('00000000-0000-0000-0000-0000000001b1', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017b', 'L1', 1, 'q', 'h1', 'h2',
     'a', 2, FALSE, TIMESTAMPTZ '2026-02-21T00:30:00Z', 'IN_PROGRESS'),
    ('00000000-0000-0000-0000-0000000001b2', '00000000-0000-0000-0000-000000000161',
     '00000000-0000-0000-0000-00000000017b', 'L2', 2, 'q', 'h1', 'h2',
     NULL, NULL, NULL, NULL, 'PREPARED');
