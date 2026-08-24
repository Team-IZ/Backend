-- 2026-08-24 · 그린컴퍼니 부트캠프 7기 — 프로젝트 참여자(project_membership) 소급 편성
--
-- ════════════════════════════════════════════════════════════════════════════
-- 왜 필요한가
-- ════════════════════════════════════════════════════════════════════════════
--
--   백엔드에는 project_membership 을 INSERT 하는 코드가 **하나도 없다**(엔티티도 없고
--   생성 API 에 참여자 필드도 없다). 그래서 화면으로 만든 프로젝트는 구조적으로 항상
--   참여자 0명이고, assessment_round_attendance 뷰가
--
--       JOIN project_membership pm ON pm.project_id = p.project_id AND pm.status = 'ACTIVE'
--
--   로 시작하는 INNER JOIN 이라 (회차 × 사람) 행이 아예 안 생긴다. 결과적으로
--   trainee_home_round_view 가 비고, 교육생 홈의 「예정된 일」에 그 회차가 뜨지 않는다.
--
--   앞으로는 프로젝트 생성 트랜잭션이 이 편성을 함께 수행하도록 코드를 고친다
--   (ProjectCreationTransaction.createOnce). 이 파일은 **그 코드가 들어가기 전에 이미
--   만들어져 버린 프로젝트**를 같은 상태로 맞추는 소급 작업이다.
--
-- ════════════════════════════════════════════════════════════════════════════
-- 🔴 대상 확인 — 「4회차」가 아니라 미프 5차다
-- ════════════════════════════════════════════════════════════════════════════
--
--   docs/dummy_data/v08 실측(2026-08-24):
--
--     프로젝트    project_membership   편성 대상과의 차이
--     ─────────   ──────────────────   ──────────────────
--     미프 1차           250 (ACTIVE)   extra 1  (뒤에 LEFT 처리된 오하준)
--     미프 2차           250 (ACTIVE)   extra 1  (동일)
--     미프 3차           249 (ACTIVE)   완전 일치
--     미프 4차           249 (ACTIVE)   완전 일치   ← 추가할 것이 없다
--     미프 5차             0            missing 249  ← 이 파일의 대상
--
--   미프 4차는 이미 249건이 빠짐없이 들어가 있어 이 스크립트가 할 일이 없다.
--   비어 있는 것은 미프 5차(871802ba-…, PLANNED, 2026-08-31~09-04) 하나뿐이라
--   그쪽을 대상으로 작성했다. 다른 프로젝트에 돌리려면 §2 의 project_id 리터럴
--   **한 줄만** 바꾸면 된다(멱등하므로 이미 채워진 프로젝트에 돌려도 0건이다).
--
-- ════════════════════════════════════════════════════════════════════════════
-- 편성 대상의 정의
-- ════════════════════════════════════════════════════════════════════════════
--
--   ① 반에 배정돼 있다        class_membership.unassigned_at IS NULL
--   ② 기수에 실제로 들어왔다   cohort_member.status = 'ACTIVE'
--
--   ②가 'INVITED'(초대만 되고 미가입)를 자연히 배제한다. project_membership.class_id 는
--   NOT NULL 이므로 ① 없이는 애초에 행을 만들 수 없다.
--
--   7기 실측: cohort_member ACTIVE 252 · LEFT 1.
--   ACTIVE 252 중 반 미배정 3명(오지훈·haneul.jung·한도현)이 ①에서 빠져 **249명**이 된다.
--   이 249 는 미프 3차·4차의 현재 편성과 정확히 같은 집합이다.
--
-- 🔴 멱등하다 — uq_project_membership_active (project_id, user_id) WHERE status='ACTIVE'
--   가 이미 있으므로 ON CONFLICT DO NOTHING 으로 재실행이 안전하다. DDL 변경 없음.

-- ════════════════════════════════════════════════════════════════════════════
-- 검증 완료 — 임시 Postgres 16 컨테이너 + v08 DDL/View + v08 시드 전량
-- ════════════════════════════════════════════════════════════════════════════
--
--   §1  현재_편성 0 · 편성_대상 249 · 이번에_들어갈_건수 249
--   §2  INSERT 0 249
--   §3-1 A~H·J반 25명 · I반 24명 = 249
--   §3-2 미프 4차와의 차집합 양방향 0 — 같은 집합이다
--   §3-3 trainee_home_round_view 에 249행 · round_status PLANNED · 마감 2026-09-01 14:59+00
--   §3-4 해당 교육생 홈에 미프 5차가 PLANNED 로 조회됨
--        → AssessmentRoundQueryService 의 isPlanned() 를 타고 upcoming[] 에 담긴다
--   재실행 INSERT 0 0 — 멱등성 확인


-- ════════════════════════════════════════════════════════════════════════════
-- §1 사전 점검 — 넣기 전에 249가 맞는지 눈으로 본다
-- ════════════════════════════════════════════════════════════════════════════

WITH target AS (
    SELECT p.project_id, p.org_id, p.cohort_id, p.name
    FROM project p
    WHERE p.project_id = '871802ba-d318-428b-ba14-855226e3ab9a'
      AND p.deleted_at IS NULL
)
SELECT t.name                                                        AS project_name,
       (SELECT count(*) FROM project_membership pm
         WHERE pm.project_id = t.project_id AND pm.status = 'ACTIVE') AS 현재_편성,
       count(*)                                                       AS 편성_대상,
       count(*) FILTER (WHERE pm.project_membership_id IS NULL)       AS 이번에_들어갈_건수
FROM target t
JOIN class cl             ON cl.cohort_id = t.cohort_id
                         AND cl.org_id = t.org_id
                         AND cl.deleted_at IS NULL
JOIN class_membership clm ON clm.class_id = cl.class_id
                         AND clm.unassigned_at IS NULL
JOIN cohort_member cm     ON cm.cohort_member_id = clm.cohort_member_id
                         AND cm.cohort_id = t.cohort_id
                         AND cm.status = 'ACTIVE'
LEFT JOIN project_membership pm ON pm.project_id = t.project_id
                               AND pm.user_id = cm.user_id
                               AND pm.status = 'ACTIVE'
GROUP BY t.name, t.project_id;

-- 기대값:  project_name = 미프 5차 · 현재_편성 = 0 · 편성_대상 = 249 · 이번에_들어갈_건수 = 249


-- ════════════════════════════════════════════════════════════════════════════
-- §2 편성
-- ════════════════════════════════════════════════════════════════════════════

BEGIN;

WITH target AS (
    SELECT p.project_id, p.org_id, p.cohort_id, p.created_by
    FROM project p
    -- ▼▼▼ 대상 프로젝트. 다른 프로젝트에 돌리려면 이 줄만 바꾼다 ▼▼▼
    WHERE p.project_id = '871802ba-d318-428b-ba14-855226e3ab9a'
    -- ▲▲▲
      AND p.deleted_at IS NULL
)
INSERT INTO project_membership (
    project_id, user_id, org_id, class_id, class_membership_id,
    joined_at, status, change_reason, changed_by, created_at
)
SELECT t.project_id,
       cm.user_id,
       t.org_id,
       clm.class_id,
       clm.class_membership_id,
       CURRENT_TIMESTAMP,
       'ACTIVE',
       '프로젝트 생성 시 자동 편성 소급(2026-08-24)',
       -- 프로젝트를 만든 사람에게 귀속시킨다. 코드가 편성하게 된 뒤의 actorUserId 와 같은 값이다.
       t.created_by,
       CURRENT_TIMESTAMP
FROM target t
JOIN class cl             ON cl.cohort_id = t.cohort_id
                         AND cl.org_id = t.org_id
                         AND cl.deleted_at IS NULL
JOIN class_membership clm ON clm.class_id = cl.class_id
                         AND clm.unassigned_at IS NULL
JOIN cohort_member cm     ON cm.cohort_member_id = clm.cohort_member_id
                         AND cm.cohort_id = t.cohort_id
                         AND cm.status = 'ACTIVE'
ON CONFLICT DO NOTHING;

-- 249 이면 COMMIT, 아니면 ROLLBACK.
COMMIT;


-- ════════════════════════════════════════════════════════════════════════════
-- §3 검증 — 뷰까지 실제로 뚫렸는지 본다
-- ════════════════════════════════════════════════════════════════════════════

-- (1) 반별 편성 인원. 10개 반이 고르게 나와야 한다.
SELECT cl.name AS 반, count(*) AS 인원
FROM project_membership pm
JOIN class cl ON cl.class_id = pm.class_id
WHERE pm.project_id = '871802ba-d318-428b-ba14-855226e3ab9a'
  AND pm.status = 'ACTIVE'
GROUP BY cl.name
ORDER BY cl.name;

-- (2) 미프 3차·4차와 같은 집합인지. 두 값 모두 0 이어야 한다.
SELECT count(*) FILTER (WHERE p5.user_id IS NULL) AS "4차에만_있음",
       count(*) FILTER (WHERE p4.user_id IS NULL) AS "5차에만_있음"
FROM      (SELECT user_id FROM project_membership
            WHERE project_id = 'c94e2655-0c0a-500d-b872-88f54ba6c29a' AND status = 'ACTIVE') p4
FULL JOIN (SELECT user_id FROM project_membership
            WHERE project_id = '871802ba-d318-428b-ba14-855226e3ab9a' AND status = 'ACTIVE') p5
       ON p4.user_id = p5.user_id;

-- (3) 🔴 진짜 확인 — 교육생 홈 뷰에 미프 5차가 실렸는가.
--     이게 249 여야 「예정된 일」에 카드가 뜬다.
SELECT count(*) AS 뷰에_잡힌_교육생,
       min(round_status) AS 회차상태,
       min(submission_due_at) AS 제출마감
FROM trainee_home_round_view
WHERE assessment_round_id = 'd50ad9e3-c58d-4c40-a2c5-c73e7990cf10';

-- (4) 임의의 교육생 하나로 최종 확인. round_status = 'PLANNED' 이므로
--     AssessmentRoundQueryService 의 isPlanned() 를 타고 upcoming[] 에 담긴다.
SELECT round_name, round_status, submission_due_at, representative_status, default_action_code
FROM trainee_home_round_view
WHERE trainee_user_id = (
        SELECT user_id FROM project_membership
        WHERE project_id = '871802ba-d318-428b-ba14-855226e3ab9a' AND status = 'ACTIVE'
        ORDER BY created_at LIMIT 1)
ORDER BY submission_due_at;
