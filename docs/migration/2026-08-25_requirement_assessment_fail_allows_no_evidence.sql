-- 2026-08-25 · project_requirement_assessment.evidence_summary — FAIL만 NULL 허용
--
-- 배경: AI 엔진(requirements.py)은 FAIL 판정에 근거를 요구하지 않는 게 의도된 설계다
-- (2026-08-05 레드팀 감사 대응 — PASS만 fragments.locate_symbol로 실제 소스 대조 검증,
-- FAIL은 검증 대상이 아님). 그런데 이 CHECK는 PASS/FAIL을 구분 없이 evidence_summary를
-- 강제해서, AI가 정상적으로 만드는 "FAIL + evidence 없음" 조합이 INSERT부터 거부됐다.
-- 그 결과가 트랜잭션 롤백 → analysis_job이 SUCCEEDED인데 결과가 비는 상태 → 응시가
-- ANALYSIS_FAILED로 닫히는 연쇄였다(2026-08-24 "미프 4차" 15건 인시던트, 원인 조사
-- 문서: 미프4차_분석실패_원인조사_회신_2026-08-24.md, 정책 결정 요청서:
-- 요구사항판정_FAIL_근거없음_정책결정요청_2026-08-25.md).
--
-- 이 마이그레이션은 그 정책 결정의 옵션 B(CHECK 완화)를 적용한다. PASS는 원래 취지대로
-- "근거 검증된 것만 유지"를 그대로 강제하고, FAIL만 evidence_summary NULL을 허용한다.

BEGIN;

ALTER TABLE project_requirement_assessment
    DROP CONSTRAINT ck_project_requirement_assessment_result_fields;

ALTER TABLE project_requirement_assessment
    ADD CONSTRAINT ck_project_requirement_assessment_result_fields CHECK (
        (
            result = 'PENDING'
            AND evidence_summary IS NULL
            AND source_submission_id IS NULL
            AND analysis_id IS NULL
            AND assessed_at IS NULL
            AND assessed_by IS NULL
        )
        OR (
            result = 'PASS'
            AND evidence_summary IS NOT NULL
            AND source_submission_id IS NOT NULL
            AND assessed_at IS NOT NULL
            AND (analysis_id IS NOT NULL OR assessed_by IS NOT NULL)
        )
        OR (
            -- FAIL만 완화: evidence_summary는 있어도 없어도 된다.
            result = 'FAIL'
            AND source_submission_id IS NOT NULL
            AND assessed_at IS NOT NULL
            AND (analysis_id IS NOT NULL OR assessed_by IS NOT NULL)
        )
    );

COMMIT;
