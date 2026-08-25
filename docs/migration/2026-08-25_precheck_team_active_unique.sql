-- 사전 확인 — 팀 UNIQUE를 "활성 범위"로 좁히기 전에 읽기만 합니다.
-- 근거: 46차 R1(해체가 204인데 반영되지 않습니다)
--
-- 실행:  psql "$PGURL" -f docs/migration/2026-08-25_precheck_team_active_unique.sql
--
-- 이 파일은 아무것도 바꾸지 않습니다. 1·2번 결과가 기대값과 다르면 멈추고 알려 주세요.

\echo '=== 1. 지금 걸려 있는 제약 — 부분 인덱스가 아니라 전체 UNIQUE여야 정상(=고칠 대상) ==='
-- 기대: 두 줄 모두 contype='u'(테이블 제약), 즉 deleted_at 조건이 없는 전체 UNIQUE.
-- 이미 부분 인덱스로 바뀌어 있으면 0줄이고, 그러면 본 작업은 실행할 필요가 없습니다.
SELECT conname, contype, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'team'::regclass
  AND conname IN ('uq_team_project_id_class_id_name',
                  'uq_team_project_id_class_id_team_number')
ORDER BY conname;

\echo '=== 2. 활성 팀 안의 중복 — 반드시 0줄이어야 합니다 ==='
-- 지금 제약이 더 엄격하므로 0줄이 정상입니다. 한 줄이라도 나오면 본 작업의
-- CREATE UNIQUE INDEX가 실패하므로 멈추고 알려 주세요.
SELECT project_id, class_id, name, count(*) AS rows
FROM team
WHERE deleted_at IS NULL
GROUP BY project_id, class_id, name
HAVING count(*) > 1
UNION ALL
SELECT project_id, class_id, team_number, count(*)
FROM team
WHERE deleted_at IS NULL
GROUP BY project_id, class_id, team_number
HAVING count(*) > 1;

\echo '=== 3. 해체된 팀이 이름·번호를 붙잡고 있는 자리 — 이 작업으로 풀리는 자리입니다 ==='
-- 해체된 팀과 같은 (project_id, class_id, name)을 다시 쓰려다 409를 받는 조합입니다.
-- 0줄이어도 무방합니다(아직 아무도 재사용을 시도하지 않았다는 뜻).
SELECT t.project_id, t.class_id, c.name AS class_name,
       t.team_number, t.name AS team_name, t.deleted_at
FROM team t
JOIN "class" c ON c.class_id = t.class_id
WHERE t.deleted_at IS NOT NULL
ORDER BY t.deleted_at DESC
LIMIT 50;

\echo '=== 4. 46차 재현 대상 확인 — 프론트가 남겨 둔 E반 두 팀의 실제 상태 ==='
-- 기대: fe67cdc0(2팀)은 deleted_at이 채워져 있습니다(DELETE는 정상 커밋됐습니다).
--       88fdda73(1)도 채워져 있으면, 제출 현황이 NOT_STARTED라고 답한 것이 정답이고
--       팀 목록이 유령을 보여 준 것이 맞습니다(46차 R2와 같은 원인).
-- (88fdda73은 프론트가 앞자리만 적어 줘서 반 전체를 그대로 봅니다.)
SELECT team_id, team_number, name, status, created_at, deleted_at
FROM team
WHERE project_id = '871802ba-d318-428b-ba14-855226e3ab9a'
  AND class_id = '5d5866b3-5522-56c6-abe1-da4ba3ef2dcd'
ORDER BY team_number;
