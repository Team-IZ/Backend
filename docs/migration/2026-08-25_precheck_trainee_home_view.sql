-- 2026-08-25 · trainee_home_round_view 교체 전 사전 확인 (읽기만 한다)
--
-- 2026-08-25_trainee_home_covers_resultless_success.sql 를 적용하기 전에 이 파일을 먼저 돌린다.
-- 대상 DB 의 현재 뷰가 이 마이그레이션이 가정한 그 정의가 맞는지 확인하는 것이 목적이다.
--
-- 🔴 왜 이 확인이 필요한가
--   이 저장소의 뷰 마이그레이션은 CREATE OR REPLACE VIEW 전문을 통째로 옮겨 적는 방식이라,
--   대상 DB 가 기준으로 삼은 정의와 다르면 그 차이가 조용히 덮인다. 실제로
--   2026-08-19_report_disclosure_removal.sql 이 본문을 8/16 파일에서 옮겨 적으면서
--   2026-08-13 에 넣었던 응시 창 가드를 지웠고, 에러 없이 성공했기 때문에 아무도 몰랐다.
--
--   이 마이그레이션의 기준은 docs/table-definition/PostgreSQL_View_v08.sql(2026-08-21 RDS 덤프)이다.

\echo '=== 1. 뷰 정의 지문 ==='

WITH v AS (SELECT pg_get_viewdef('public.trainee_home_round_view', TRUE) AS d)
SELECT
    strpos(d, 'rpt.published_at IS NOT NULL THEN ''VIEW_REPORT''') > 0
        AS "8/19 적용됨",
    strpos(d, 'primary_terminal_reason_code::text = ''ANALYSIS_FAILED''::text') > 0
        AS "이미 적용됨",
    substring(d from '.{200}THEN ''RESUME_ASSESSMENT''') LIKE '%primary_assessment_close_at%'
        AS "8/13 응시창 가드"
FROM v;

-- 기대값
--   8/19 적용됨    = t   ← f 이면 대상 DB 가 기준보다 낡았다. 적용하지 말고 알려 주세요.
--   이미 적용됨    = f   ← t 이면 이미 적용된 DB 다. 다시 돌려도 무해하지만 할 일이 없다.
--   8/13 응시창 가드 = f   ← 별건이다. 이 마이그레이션은 이 값을 바꾸지 않는다(f 로 남는다).

\echo ''
\echo '=== 2. 이번에 상태가 바뀔 행 (교육생 목록) ==='

SELECT co.name AS cohort, cl.name AS class_name, u.name, v.round_name,
       v.representative_status, v.default_action_code, v.analysis_phase,
       v.analysis_job_status, v.submission_due_at > CURRENT_TIMESTAMP AS 재제출_가능
  FROM public.trainee_home_round_view v
  JOIN public.app_user u ON u.user_id = v.trainee_user_id
  LEFT JOIN public.cohort co ON co.cohort_id = v.cohort_id
  LEFT JOIN public.class cl ON cl.class_id = v.class_id_at_round
 WHERE v.initial_terminal_reason_code = 'ANALYSIS_FAILED'
   AND v.analysis_job_status NOT IN ('QUEUED', 'RUNNING')
 ORDER BY co.name, cl.name, u.name;

\echo ''
\echo '=== 3. 적용 전 전체 분포 (적용 후 대조군으로 쓴다 — 결과를 저장해 두세요) ==='

SELECT representative_status, default_action_code, count(*)
  FROM public.trainee_home_round_view
 GROUP BY 1, 2
 ORDER BY 3 DESC;
