-- 사전 확인 — 해체된 팀에 갇힌 소속 행을 풀기 전에 읽기만 합니다. 아무것도 바꾸지 않습니다.
--
-- 근거: 47차 대응 중 실 DB 확인에서 드러남 — J반 유령 4팀에 17명이 to_at NULL로 남아 있습니다.
-- 실행:  psql "$PGURL" -f docs/migration/2026-08-25_precheck_stuck_memberships.sql
--
-- 본 작업: docs/migration/2026-08-25_backfill_stuck_memberships.sql
--
-- 2·3번 결과가 기대값과 다르면 멈추고 알려 주세요.

\echo '=== 1. 갇힌 소속 — 해체된 팀에 아직 배정된 채 남아 있는 행 ==='
-- 팀이 해체(deleted_at)됐는데 소속이 종료(to_at)되지 않은 행입니다.
-- 46차 수정 이전에 해체된 팀에서만 나옵니다 — 그 시절 해체는 team 행만 건드리고
-- 팀원을 미배정으로 돌려놓지 않았습니다.
SELECT c.name AS class_name, p.name AS project_name,
       t.team_number, t.name AS team_name, t.deleted_at,
       count(*) AS stuck_rows,
       min(tm.from_at) AS earliest_from_at
FROM team_membership tm
JOIN team t   ON t.team_id  = tm.team_id
JOIN "class" c ON c.class_id = t.class_id
JOIN project p ON p.project_id = t.project_id
WHERE t.deleted_at IS NOT NULL
  AND tm.to_at IS NULL
GROUP BY c.name, p.name, t.team_id, t.team_number, t.name, t.deleted_at
ORDER BY c.name, t.team_number;

\echo '=== 2. 합계 — 2026-08-25 기준 기대값은 17행 / 4팀(J반 1·2·4·6팀) ==='
-- 크게 다르면 그 사이 편성이 바뀐 것이므로 멈추고 알려 주세요.
SELECT count(*) AS stuck_rows, count(DISTINCT tm.team_id) AS stuck_teams
FROM team_membership tm
JOIN team t ON t.team_id = tm.team_id
WHERE t.deleted_at IS NOT NULL AND tm.to_at IS NULL;

\echo '=== 3. 해체된 뒤에 배정된 행 — 2026-08-25 기준 17행 전부가 여기 해당합니다 ==='
-- 배정(from_at 08-24 16:57~16:58)이 해체(deleted_at 15:22~15:23·09:41)보다 뒤입니다.
-- 46차 이전에는 조회에 deleted_at 필터가 없어 **해체된 팀이 배정 목록에 그대로 남았고**,
-- 거기에 배정이 실제로 들어갔습니다. 그 흔적입니다.
--
-- 그래서 본 작업은 to_at에 deleted_at이 아니라 **트랜잭션 시각**을 넣습니다 — deleted_at을
-- 넣으면 from_at > to_at이 되어 ck_team_membership_period_order를 깹니다.
-- 이 목록이 비어 있어도(전부 해체 전 배정이어도) 본 작업은 그대로 동작합니다.
SELECT c.name AS class_name, t.name AS team_name,
       tm.membership_id, tm.from_at, t.deleted_at
FROM team_membership tm
JOIN team t   ON t.team_id  = tm.team_id
JOIN "class" c ON c.class_id = t.class_id
WHERE t.deleted_at IS NOT NULL
  AND tm.to_at IS NULL
  AND tm.from_at >= t.deleted_at
ORDER BY c.name, t.name;

\echo '=== 4. 이 사람들이 이미 다른 팀에도 배정돼 있는가 — 0줄이면 아직 이중 소속은 없습니다 ==='
-- 갇힌 상태로 재배정되면 한 사람이 활성 소속 2건을 갖게 됩니다(정의서: 프로젝트당 활성 1건).
-- 이미 그런 사람이 있으면 백필이 그 어긋남까지 함께 정리합니다.
SELECT c.name AS class_name, t.name AS ghost_team, tm.project_membership_id,
       alive_t.name AS also_in_team
FROM team_membership tm
JOIN team t     ON t.team_id  = tm.team_id  AND t.deleted_at IS NOT NULL
JOIN "class" c  ON c.class_id = t.class_id
JOIN team_membership alive
     ON alive.project_membership_id = tm.project_membership_id
    AND alive.to_at IS NULL
    AND alive.team_id <> tm.team_id
JOIN team alive_t ON alive_t.team_id = alive.team_id AND alive_t.deleted_at IS NULL
WHERE tm.to_at IS NULL
ORDER BY c.name, t.name;

\echo '=== 5. 참고 — 살아 있는 팀의 활성 소속 수(백필 후에도 이 값은 그대로여야 합니다) ==='
SELECT count(*) AS active_memberships_in_alive_teams
FROM team_membership tm
JOIN team t ON t.team_id = tm.team_id
WHERE t.deleted_at IS NULL AND tm.to_at IS NULL;
