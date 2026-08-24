-- 2026-08-25 · trainee_home_round_view 교체 후 확인 (읽기만 한다)

\echo '=== 1. 재분석 중이 아닌 ANALYSIS_FAILED 가 「분석 중」으로 남아 있으면 안 된다 ==='

SELECT count(*) AS should_be_zero
  FROM public.trainee_home_round_view
 WHERE initial_terminal_reason_code = 'ANALYSIS_FAILED'
   AND analysis_job_status NOT IN ('QUEUED', 'RUNNING')
   AND representative_status = 'ANALYZING';

\echo ''
\echo '=== 2. 옮겨간 행이 네 컬럼 모두 제대로 된 값을 갖는가 ==='

SELECT representative_status, default_action_code, analysis_phase, warning_codes, count(*)
  FROM public.trainee_home_round_view
 WHERE initial_terminal_reason_code = 'ANALYSIS_FAILED'
 GROUP BY 1, 2, 3, 4
 ORDER BY 5 DESC;

-- 기대 — representative_status = 'ANALYSIS_FAILED' · analysis_phase = 'FAILED' ·
--   warning_codes 에 'ANALYSIS_FAILED' 포함 · default_action_code 는 제출 마감 전이면
--   RESUBMIT_ZIP | RESUBMIT_REPOSITORY, 마감 후면 CONTACT_MANAGER.
--   재분석이 도는 행이 있으면 그것만 ANALYZING / WAIT_FOR_ANALYSIS 로 남는다(정상).

\echo ''
\echo '=== 3. 대조군 — 그 밖의 행은 분포가 하나도 달라지면 안 된다 ==='

SELECT representative_status, default_action_code, count(*)
  FROM public.trainee_home_round_view
 WHERE initial_terminal_reason_code IS DISTINCT FROM 'ANALYSIS_FAILED'
 GROUP BY 1, 2
 ORDER BY 3 DESC;

-- 사전 확인 3번 결과에서 ANALYSIS_FAILED 행만 빠진 모습이어야 한다.
