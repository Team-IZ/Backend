-- =============================================================================
-- 세션 채점 멱등키를 problem_stage에 보존한다 — DDL 변경 사항
--
-- 작성일: 2026-08-18 (37차 R1 후속)
-- 관련: SessionAnswerGrader · docs/37차_개발계획.md W2
--
-- 상태: ⬜ 백엔드 구현 예정 · ⬜ 운영 DB 미적용
--
--   ★ 이 DDL이 배포보다 먼저다. 적용하지 않은 DB에 코드를 올리면 답변 저장 UPDATE가
--     "column question_request_id does not exist"로 실패한다 — 즉 채점이 통째로 막힌다.
--
--   ADD COLUMN 3개(전부 nullable, 기본값 없음)라 테이블 재작성 없이 즉시 끝난다.
--   기존 행은 세 값이 NULL이 되고, 그건 "이 컬럼이 생기기 전에 답한 슬롯"을 뜻한다.
--
-- 검증: 2026-08-18, 임시 Postgres 16에 v07 스키마 전량을 올린 뒤 확인했다(아래 3절).
--
-- ★ 테이블을 신설하지 않는다. problem_stage에 컬럼 3개만 추가한다.
--
--   repository_verification처럼 "요청 1건 = 행 1건" 원장을 따로 두는 방법도 있지만, 세션
--   채점은 이미 슬롯 단위로 행이 있다. problem_stage는 question_* · first_hint_* ·
--   second_hint_* 세 묶음에 답변 원문·점수·통과·시각을 전부 들고 있어서, 멱등키만 그 묶음에
--   더하면 된다. 원장을 새로 만들면 같은 사실이 두 곳에 생기고 조인이 하나 늘 뿐이다.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. 컬럼 추가 (3개)
-- -----------------------------------------------------------------------------

/*
 * 슬롯마다 하나씩 — 한 축에서 답변이 최대 세 번 나오기 때문이다(질문 → 힌트1 뒤 → 힌트2 뒤).
 * 축 단위로 컬럼 하나만 두면 두 번째 답의 키가 첫 번째를 덮어써서, 정작 다시 봐야 할
 * "몇 번째 시도가 어떤 채점을 받았나"가 사라진다. 기존 answer_text·score·passed·answered_at이
 * 전부 슬롯별로 나뉘어 있는 것과 같은 이유다.
 *
 * ── 무엇에 쓰나 ──
 *
 * 값은 백엔드가 만들어 AI에 clientRequestId로 보내는 멱등키다. AI는 같은 키를 다시 받으면
 * 처음 응답을 그대로 돌려준다. 지금까지 이 값은 어디에도 남지 않았고, 그래서 답변이
 * 접수되지 않는다는 신고를 받았을 때(37차 R1) 서버 로그와 DB 행과 AI 로그를 이어 볼 방법이
 * 없었다 — "이 점수가 어느 호출에서 나왔나"에 답할 근거가 없었다.
 *
 * 저장하면 셋이 하나의 키로 이어진다:
 *   · 백엔드 로그   `채점 AI 응답: … clientRequestId=…`
 *   · DB           problem_stage.<슬롯>_request_id
 *   · AI 로그       같은 clientRequestId
 *
 * ── 왜 UUID인가 ──
 *
 * 키를 세션·단계·축·힌트사용수로 만든 결정론적 UUID(v3)로 뽑기 때문이다. 값 자체가 UUID라
 * TEXT로 받을 이유가 없고, UUID 타입이면 형식이 어긋난 값이 애초에 들어오지 못한다.
 * reminder_dispatch.request_idempotency_key가 TEXT인 것은 그쪽이 클라이언트가 준 문자열을
 * 그대로 보관하기 때문이고, 여기는 서버가 만든 값이라 사정이 다르다.
 *
 * ── UNIQUE를 걸지 않는다 ──
 *
 * 키가 (session_id, problem_stage_id, axis_code, hintsUsed)로 이미 결정되므로 같은 값이 두 행에
 * 들어올 수 없다 — 들어온다면 그건 키 생성 규칙이 깨졌다는 뜻이고, 그때는 UNIQUE 위반으로
 * 저장이 막히는 것보다 값이 남아 있어 원인을 되짚는 편이 낫다. 게다가 세 컬럼에 걸쳐야 해서
 * 인덱스가 셋 늘어나는데, 조회 축이 아니라 대조용 값이라 얻는 것이 없다.
 */
ALTER TABLE problem_stage ADD COLUMN IF NOT EXISTS question_request_id     UUID;
ALTER TABLE problem_stage ADD COLUMN IF NOT EXISTS first_hint_request_id   UUID;
ALTER TABLE problem_stage ADD COLUMN IF NOT EXISTS second_hint_request_id  UUID;

COMMENT ON COLUMN problem_stage.question_request_id IS
    '질문 슬롯 답변을 채점할 때 AI에 보낸 멱등키(clientRequestId)다. 서버가 세션·단계·축·힌트사용수로 만드는 결정론적 UUID이며, 백엔드 로그·AI 로그와 이 행을 잇는 대조 값이다. 이 컬럼이 생기기 전에 답한 슬롯은 NULL이다.';
COMMENT ON COLUMN problem_stage.first_hint_request_id IS
    '힌트 1개를 보고 답한 슬롯의 채점 멱등키다. 규칙은 question_request_id와 같다.';
COMMENT ON COLUMN problem_stage.second_hint_request_id IS
    '힌트 2개를 보고 답한 슬롯의 채점 멱등키다. 규칙은 question_request_id와 같다.';


-- -----------------------------------------------------------------------------
-- 2. 적용 확인
-- -----------------------------------------------------------------------------

SELECT column_name, data_type, is_nullable
  FROM information_schema.columns
 WHERE table_name = 'problem_stage'
   AND column_name IN ('question_request_id', 'first_hint_request_id', 'second_hint_request_id')
 ORDER BY column_name;
-- 3행이 나오고 전부 uuid · YES 면 성공이다.


-- -----------------------------------------------------------------------------
-- 3. 되돌리기
-- -----------------------------------------------------------------------------
--
-- 값을 읽는 곳이 없고(대조용이다) 제약도 없으므로 그냥 지우면 된다. 다만 코드가 이 컬럼에
-- 쓰기 시작한 뒤에 지우면 답변 저장이 실패하므로, 코드를 먼저 되돌린 뒤에 실행한다.
--
--   ALTER TABLE problem_stage DROP COLUMN IF EXISTS question_request_id;
--   ALTER TABLE problem_stage DROP COLUMN IF EXISTS first_hint_request_id;
--   ALTER TABLE problem_stage DROP COLUMN IF EXISTS second_hint_request_id;
