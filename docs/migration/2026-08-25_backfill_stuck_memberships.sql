-- 해체된 팀에 갇힌 소속 행을 미배정으로 풀어 줍니다.
--
-- 근거: 47차 대응 중 실 DB 확인 — J반 유령 4팀에 17명이 to_at NULL로 남아 있습니다.
-- 요청: 2026-08-25 · 박종호(백엔드) → DB 운영 담당
--
-- 실행:  psql "$PGURL" -f docs/migration/2026-08-25_backfill_stuck_memberships.sql
--        (사전 확인부터: docs/migration/2026-08-25_precheck_stuck_memberships.sql)
--
--
-- ■ 무엇을 바꾸나
--
--   team.deleted_at이 찍혔는데 team_membership.to_at이 비어 있는 행의 to_at에
--   **이 트랜잭션의 시각(transaction_timestamp())을 넣습니다.** 그것뿐입니다.
--
--   행을 지우지 않습니다. 팀 행도 건드리지 않습니다. 소속 이력은 그대로 남고,
--   "언제까지 그 팀이었는지"만 사실대로 채워집니다.
--
--
-- ■ 왜
--
--   팀 해체는 team 행에 deleted_at을 찍고 그 팀의 소속 행에 to_at을 찍는 두 가지를 함께
--   해야 합니다. 46차 수정 **이전**의 해체는 앞의 하나만 했습니다. 그래서 08-24에 해체된
--   J반 1·2·4·6팀에 17명이 "해체된 팀에 배정된 채" 남아 있습니다.
--   (같은 반 3팀은 08-25 08:41 해체 — 46차 수정 이후라 정상적으로 풀렸습니다.)
--
--   조회는 `JOIN team … deleted_at IS NULL`로 유령 팀을 걸러 내므로 화면에는 이미 미배정으로
--   보입니다. 문제는 그 상태에서 재배정할 때입니다 — 새 소속 행이 생겨도 갇힌 행이 남아
--   한 사람이 **활성 소속 2건**을 갖게 됩니다. 정의서가 "프로젝트당 활성 TeamMembership은
--   최대 1건"이라고 못 박은 자리인데 DB 제약이 없어 조용히 어긋납니다.
--
--
-- ■ 왜 deleted_at이 아니라 트랜잭션 시각인가
--
--   17행 **전부** from_at이 팀의 deleted_at보다 **뒤**입니다(배정 08-24 16:57~16:58,
--   해체 15:22~15:23·09:41). 팀이 해체된 뒤에 사람이 배정된 것입니다 — 46차 이전에는
--   조회에 `deleted_at IS NULL` 필터가 없어 **해체된 팀이 배정 대상 목록에 그대로 남았고**,
--   거기에 배정이 실제로 들어갔습니다.
--
--   그래서 to_at에 deleted_at을 넣으면 from_at > to_at이 되어
--   ck_team_membership_period_order(to_at IS NULL OR from_at < to_at)를 깹니다. 의미로도
--   거짓입니다 — "해체 그 순간 미배정으로 돌아갔다"가 사실이 아닙니다.
--
--   트랜잭션 시각을 쓰면 기록이 사실대로 남습니다: 그 사람은 08-24 16:57부터 **이 정리
--   시점까지** 해체된 팀에 배정된 상태였다. 한 트랜잭션 안의 모든 행이 **같은 값**을 받으므로
--   나중에 그 값 하나로 되돌릴 수도 있습니다.
--
--
-- ■ 왜 안전한가
--
--   · UPDATE 대상이 `t.deleted_at IS NOT NULL AND tm.to_at IS NULL`뿐입니다.
--     살아 있는 팀의 소속은 조건에 들어오지 않습니다(STEP 4에서 수로 대조합니다).
--   · 지우는 것이 없어 되돌릴 수 있습니다(맨 아래 롤백 SQL).
--   · to_at은 한 트랜잭션 안에서 모두 같은 값이라, 그 값 하나로 되돌릴 수 있습니다.
--   · ck_team_membership_period_order(to_at IS NULL OR from_at < to_at)를 깰 행이 있으면
--     STEP 2에서 멈춥니다(from_at이 미래인 행).
--
--   전체가 BEGIN/COMMIT 한 덩어리입니다. 어느 단계에서 멈춰도 한 행도 바뀌지 않습니다.

-- 첫 에러에서 즉시 멈춥니다. 없으면 가드가 걸린 뒤 남은 문장이 전부
-- "current transaction is aborted"로 찍혀 진짜 원인 줄이 묻힙니다.
\set ON_ERROR_STOP on

BEGIN;

-- ========================================================================
-- STEP 0. 대상 확정 — 여기서 잡힌 행만 바뀝니다.
-- ========================================================================

CREATE TEMP TABLE tmp_stuck ON COMMIT DROP AS
SELECT tm.membership_id, tm.team_id, tm.project_membership_id,
       tm.from_at, t.deleted_at,
       transaction_timestamp() AS new_to_at   -- 한 트랜잭션 안의 모든 행이 같은 값을 받습니다
FROM team_membership tm
JOIN team t ON t.team_id = tm.team_id
WHERE t.deleted_at IS NOT NULL
  AND tm.to_at IS NULL;

-- 손대면 안 되는 것이 그대로인지 나중에 대조할 기준값.
CREATE TEMP TABLE tmp_alive_before ON COMMIT DROP AS
SELECT count(*) AS active_rows
FROM team_membership tm
JOIN team t ON t.team_id = tm.team_id
WHERE t.deleted_at IS NULL AND tm.to_at IS NULL;

\echo '=== STEP 1. 풀어 줄 대상 ==='
SELECT c.name AS class_name, t.team_number, t.name AS team_name,
       t.deleted_at, min(s.from_at) AS earliest_from_at,
       count(*) AS stuck_rows,
       count(*) FILTER (WHERE s.from_at > t.deleted_at) AS assigned_after_disband,
       max(s.new_to_at) AS new_to_at
FROM tmp_stuck s
JOIN team t    ON t.team_id  = s.team_id
JOIN "class" c ON c.class_id = t.class_id
GROUP BY c.name, t.team_number, t.name, t.deleted_at
ORDER BY c.name, t.team_number;

-- ========================================================================
-- STEP 2. 가드 — 하나라도 걸리면 여기서 멈춥니다(아무것도 안 바뀝니다).
-- ========================================================================

DO $$
DECLARE
	target_rows int;
	bad_rows    int;
BEGIN
	SELECT count(*) INTO target_rows FROM tmp_stuck;

	-- (1) 이미 정리된 DB에서 다시 돌려도 조용히 끝납니다.
	IF target_rows = 0 THEN
		RAISE NOTICE '대상 없음 — 갇힌 소속이 없습니다. 이미 정리된 상태입니다.';
		RETURN;
	END IF;

	-- (2) 대상이 예상보다 많으면 멈춥니다. 2026-08-25 기준 기대값은 17행입니다.
	IF target_rows > 50 THEN
		RAISE EXCEPTION '🔴 중단 — 대상이 %행입니다. 50행을 넘으면 범위가 잘못 잡힌 것으로 봅니다. STEP 1 출력을 확인하세요.', target_rows;
	END IF;

	-- (3) 기간 제약. from_at이 지금보다 뒤(미래)인 행이 있으면 to_at을 넣을 수 없습니다.
	SELECT count(*) INTO bad_rows FROM tmp_stuck WHERE from_at >= new_to_at;
	IF bad_rows > 0 THEN
		RAISE EXCEPTION '🔴 중단 — from_at이 현재 시각보다 뒤인 행이 %건 있습니다. ck_team_membership_period_order를 깨므로 값을 따로 정해야 합니다.', bad_rows;
	END IF;

	-- (참고) 해체 뒤에 배정된 행 — 46차 이전 버그의 흔적입니다. 막지 않고 알리기만 합니다.
	SELECT count(*) INTO bad_rows FROM tmp_stuck WHERE from_at > deleted_at;
	IF bad_rows > 0 THEN
		RAISE NOTICE '참고 — %행은 팀이 해체된 **뒤에** 배정된 행입니다(46차 이전에는 해체된 팀이 배정 목록에 남았습니다). 그래서 to_at에 deleted_at을 쓸 수 없습니다.', bad_rows;
	END IF;

	RAISE NOTICE '통과 — 대상 %행. 미배정 처리로 진행합니다.', target_rows;
END $$;

-- ========================================================================
-- STEP 3. 백필 — 해체 시각을 소속 종료 시각으로 채웁니다.
-- ========================================================================

UPDATE team_membership tm
SET to_at = s.new_to_at
FROM tmp_stuck s
WHERE tm.membership_id = s.membership_id;

-- ========================================================================
-- STEP 4. 사후 확인 — 커밋 전에 봅니다. 이상하면 이 자리에서 ROLLBACK 하세요.
-- ========================================================================

\echo '=== STEP 4-1. 해체된 팀에 남은 활성 소속 — 0줄이어야 합니다 ==='
SELECT tm.membership_id
FROM team_membership tm
JOIN team t ON t.team_id = tm.team_id
WHERE t.deleted_at IS NOT NULL AND tm.to_at IS NULL;

\echo '=== STEP 4-2. 살아 있는 팀의 활성 소속 수 — before/after가 같아야 합니다 ==='
SELECT b.active_rows AS active_before,
       (SELECT count(*) FROM team_membership tm
          JOIN team t ON t.team_id = tm.team_id
         WHERE t.deleted_at IS NULL AND tm.to_at IS NULL) AS active_after
FROM tmp_alive_before b;

\echo '=== STEP 4-3. 이번에 넣은 to_at 값 — 되돌릴 때 씁니다. 기록해 두세요 ==='
SELECT DISTINCT new_to_at AS applied_to_at FROM tmp_stuck;

\echo '=== STEP 4-4. 풀려난 사람들 — 이제 어느 팀에도 활성 소속이 없어야 정상입니다 ==='
SELECT count(DISTINCT s.project_membership_id) AS released_people,
       count(*) FILTER (WHERE alive.membership_id IS NOT NULL) AS still_in_some_team
FROM tmp_stuck s
LEFT JOIN team_membership alive
       ON alive.project_membership_id = s.project_membership_id
      AND alive.to_at IS NULL;

DO $$
DECLARE
	leftover  int;
	mismatch  int;
BEGIN
	SELECT count(*) INTO leftover
	FROM team_membership tm
	JOIN team t ON t.team_id = tm.team_id
	WHERE t.deleted_at IS NOT NULL AND tm.to_at IS NULL;

	IF leftover > 0 THEN
		RAISE EXCEPTION '🔴 중단 — 해체된 팀에 활성 소속이 %건 남았습니다. 전부 롤백합니다.', leftover;
	END IF;

	SELECT count(*) INTO mismatch
	FROM tmp_alive_before b
	WHERE b.active_rows <> (SELECT count(*) FROM team_membership tm
	                          JOIN team t ON t.team_id = tm.team_id
	                         WHERE t.deleted_at IS NULL AND tm.to_at IS NULL);

	IF mismatch > 0 THEN
		RAISE EXCEPTION '🔴 중단 — 살아 있는 팀의 활성 소속 수가 바뀌었습니다. 전부 롤백합니다.';
	END IF;

	RAISE NOTICE '통과 — 갇힌 소속만 풀렸고 살아 있는 팀의 소속은 그대로입니다.';
END $$;

COMMIT;

-- ── 되돌리기 ──────────────────────────────────────────────────────────────
-- 지운 것이 없어 되돌릴 수 있습니다. 이 백필이 넣은 to_at은 **한 트랜잭션의 같은 값**이라
-- STEP 4-3이 출력한 그 값 하나로 정확히 특정됩니다. 다른 경로가 남긴 to_at은 그 값과
-- 마이크로초 단위로 다르므로 걸리지 않습니다.
--
-- BEGIN;
-- UPDATE team_membership tm SET to_at = NULL
--   FROM team t
--  WHERE t.team_id = tm.team_id
--    AND t.deleted_at IS NOT NULL
--    AND tm.to_at = '<STEP 4-3이 출력한 값>'::timestamptz;
-- COMMIT;
