-- 팀 UNIQUE를 "활성 범위"로 좁힙니다 — 해체한 팀이 이름·번호를 계속 붙잡는 문제.
-- 근거: 46차 R1(해체가 204인데 반영되지 않습니다)
--
-- 실행:  psql "$PGURL" -f docs/migration/2026-08-25_team_unique_active_scope.sql
--
-- 무엇을 바꾸나
--   team의 전체 UNIQUE 제약 2건을 같은 이름의 **부분 UNIQUE 인덱스**로 바꿉니다.
--   데이터는 한 행도 바뀌지 않습니다(제약·인덱스 정의만 바뀝니다).
--
-- 왜
--   팀 해체는 행을 지우지 않고 deleted_at만 찍는 소프트 삭제입니다(과거 소속·제출 귀속을
--   남겨야 합니다). 그런데 UNIQUE가 해체된 행까지 보고 있어서, 매니저가 "2팀"을 해체한 뒤
--   같은 이름으로 다시 만들면 409 DATA_INTEGRITY_VIOLATION을 받았습니다.
--   테이블 코멘트가 이미 "활성 범위 UNIQUE(...)"라고 적고 있습니다 — 제약이 그 문장을
--   따라가지 못하고 있던 자리입니다.
--
-- 안전성
--   지금 제약이 새 인덱스보다 엄격하므로(전체 ⊃ 활성) 활성 행 중복은 존재할 수 없고,
--   CREATE UNIQUE INDEX가 실패할 경우가 없습니다. 사전 확인 2번으로 한 번 더 봅니다.
--   되돌리려면 이 파일 맨 아래 롤백 SQL을 쓰면 됩니다.

BEGIN;

-- 제약을 지우면 그 뒷받침 인덱스도 함께 사라집니다. 같은 이름을 바로 다시 쓸 수 있습니다.
ALTER TABLE team DROP CONSTRAINT uq_team_project_id_class_id_name;
ALTER TABLE team DROP CONSTRAINT uq_team_project_id_class_id_team_number;

CREATE UNIQUE INDEX uq_team_project_id_class_id_name
    ON team (project_id, class_id, name)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_team_project_id_class_id_team_number
    ON team (project_id, class_id, team_number)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX uq_team_project_id_class_id_name IS
    '활성 팀 안에서만 팀명이 유일합니다. 해체된 팀(deleted_at IS NOT NULL)은 이름을 붙잡지 않습니다(46차 R1).';
COMMENT ON INDEX uq_team_project_id_class_id_team_number IS
    '활성 팀 안에서만 팀 번호가 유일합니다. 해체된 팀(deleted_at IS NOT NULL)은 번호를 붙잡지 않습니다(46차 R1).';

COMMIT;

-- ── 롤백 ──────────────────────────────────────────────────────────────────
-- 되돌리기 전에 해체된 팀과 이름·번호가 겹치는 활성 팀이 생겼는지 먼저 보세요.
-- 겹치는 행이 하나라도 있으면 아래 ALTER는 실패합니다(그 경우 되돌릴 수 없습니다).
--
-- BEGIN;
-- DROP INDEX uq_team_project_id_class_id_name;
-- DROP INDEX uq_team_project_id_class_id_team_number;
-- ALTER TABLE team ADD CONSTRAINT uq_team_project_id_class_id_name
--     UNIQUE (project_id, class_id, name);
-- ALTER TABLE team ADD CONSTRAINT uq_team_project_id_class_id_team_number
--     UNIQUE (project_id, class_id, team_number);
-- COMMIT;
