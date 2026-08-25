-- 적용 후 확인 — 읽기만 합니다.
-- 실행:  psql "$PGURL" -f docs/migration/2026-08-25_verify_team_active_unique.sql

\echo '=== 1. 제약이 부분 UNIQUE 인덱스로 바뀌었는가 ==='
-- 기대: 두 줄 모두 indexdef 끝에 "WHERE (deleted_at IS NULL)"이 붙어 있습니다.
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'team'
  AND indexname IN ('uq_team_project_id_class_id_name',
                    'uq_team_project_id_class_id_team_number')
ORDER BY indexname;

\echo '=== 2. 테이블 제약 쪽에는 남아 있지 않아야 합니다 ==='
-- 기대: 0줄.
SELECT conname, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'team'::regclass
  AND conname IN ('uq_team_project_id_class_id_name',
                  'uq_team_project_id_class_id_team_number');

\echo '=== 3. 해체된 이름을 다시 쓸 수 있는가 — 넣었다가 되돌립니다(커밋하지 않습니다) ==='
-- 해체된 팀이 하나도 없으면 "재사용 대상 없음"이 나오고 그대로 통과입니다.
DO $$
DECLARE
	ghost team%ROWTYPE;
	new_id uuid;
BEGIN
	SELECT * INTO ghost FROM team WHERE deleted_at IS NOT NULL LIMIT 1;
	IF NOT FOUND THEN
		RAISE NOTICE '재사용 대상 없음 — 해체된 팀이 없습니다. 통과로 봅니다.';
		RETURN;
	END IF;

	INSERT INTO team (project_id, class_id, org_id, team_number, name, status,
	                  min_member_count, max_member_count, created_by)
	VALUES (ghost.project_id, ghost.class_id, ghost.org_id,
	        ghost.team_number, ghost.name, 'DRAFT', 1, 6, ghost.created_by)
	RETURNING team_id INTO new_id;

	RAISE NOTICE '통과 — 해체된 팀 %(%)의 이름·번호를 다시 쓸 수 있습니다.', ghost.name, ghost.team_id;

	-- 확인용으로 넣은 행은 즉시 지웁니다. 트랜잭션 밖에 남기지 않습니다.
	DELETE FROM team WHERE team_id = new_id;
END $$;

\echo '=== 4. 활성 중복이 생기지 않는가 — 같은 이름을 두 번 넣으면 막혀야 합니다 ==='
-- 기대: NOTICE "통과 — 활성 중복은 그대로 막힙니다".
DO $$
DECLARE
	alive team%ROWTYPE;
BEGIN
	SELECT * INTO alive FROM team WHERE deleted_at IS NULL LIMIT 1;
	IF NOT FOUND THEN
		RAISE NOTICE '대상 없음 — 활성 팀이 없습니다.';
		RETURN;
	END IF;

	BEGIN
		INSERT INTO team (project_id, class_id, org_id, team_number, name, status,
		                  min_member_count, max_member_count, created_by)
		VALUES (alive.project_id, alive.class_id, alive.org_id,
		        alive.team_number || '_dup', alive.name, 'DRAFT', 1, 6, alive.created_by);
		RAISE EXCEPTION '🔴 실패 — 활성 팀명 중복이 통과했습니다. 인덱스를 다시 보세요.';
	EXCEPTION WHEN unique_violation THEN
		RAISE NOTICE '통과 — 활성 중복은 그대로 막힙니다.';
	END;
END $$;
