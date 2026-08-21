-- =============================================================================
-- manager_trainee_detail_timeline_view.review_target — 다시 보기 대상 수 산식 교체
--
-- 작성일: 2026-08-21
-- 관련: 리포트_변경사항.md §2-3 · 리포트_DB변경_제안서.md §5 (손진원, 2026-08-20)
--       docs/migration/2026-08-20_report_snapshot_retry_target_count.sql (report_snapshot
--       .retry_target_count 컬럼 신설 — 이 마이그레이션의 선행 조건)
--
-- 상태: ✅ 백엔드 구현 불필요(계약 불변) · ✅ 운영 DB 적용 완료(2026-08-21)
--
-- 무엇이 바뀌나
--
--   REPORT 이벤트 payload의 reviewTargetCount(매니저 화면 "다시 보기 N건 지정")가
--   종전에는 problem_result를 다시 집계해서(미응답 문제 제외) 구했는데, 학생 화면·
--   AssessmentReviewService.REVIEW_TARGET_PROBLEMS(실제로 다시 보기 세션에 문제를
--   깔아 주는 로직, 미응답 포함)와 기준이 달라 매니저가 보는 숫자와 학생이 실제로
--   받는 문제 수가 어긋났다.
--
--   review_target CTE를 report_snapshot.retry_target_count를 그대로 읽도록 바꿨다 —
--   그 컬럼이 이미 같은 기준(도달 단계 2단 미만, 미응답 포함)으로 채워져 있으므로
--   매니저 화면과 학생 화면이 같은 수를 말하게 된다.
--
-- 왜 CREATE OR REPLACE VIEW로 끝나는가
--
--   review_target의 출력 컬럼(manager_user_id·cohort_id·target_user_id·
--   assessment_round_id·target_count)은 이름·개수·타입이 그대로다 — 계산식만
--   problem_result 재집계에서 report_snapshot 조회로 바뀌었다. 뷰 전체의 최종
--   SELECT 컬럼 목록도 안 바뀐다. DROP VIEW 없이 CREATE OR REPLACE로 끝나고,
--   읽는 쪽(member 도메인 JdbcTraineeTimelineRepository)도 코드 변경이 필요 없다.
--
-- 🔴 problem_result CTE는 그대로 둔다
--
--   review_target만 report_snapshot을 읽도록 바뀌었을 뿐, problem_result는
--   assessment_events·review_problem 등 다른 CTE가 여전히 쓴다. 지우면 안 된다.
--
-- 실측(적용 전 대조, 2026-08-21)
--
--   REPORT 이벤트 4059건 중 349건(8.6%)에서 값이 달라짐. 대부분은 report_snapshot의
--   레거시 백필 한계(evidence_category='RESULT_EXPLANATION' 행이 없는 옛 스냅샷은
--   retry_target_count=0)로 인한 것이고, 별도 마이그레이션(2026-08-20_report_snapshot
--   _retry_target_count.sql)에서 이미 문서화된 한계다. 새로 생기는 문제가 아니다.
--
-- 검증
--
--   ✅ 적용 전후 information_schema.columns 시그니처(순번·이름·타입) 일치 확인(자동,
--      DO 블록으로 트랜잭션 내 검증 — 어긋나면 자동 롤백).
--   ✅ 적용 후 실제 REPORT 이벤트 payload.reviewTargetCount가 report_snapshot
--      .retry_target_count와 일치하는 것 실측 확인.
-- =============================================================================

CREATE OR REPLACE VIEW public.manager_trainee_detail_timeline_view AS
 WITH manager_scope AS (
         SELECT ma.org_id,
            ma.manager_user_id,
            c.cohort_id,
            ma.class_id
           FROM manager_assignment ma
             JOIN class c ON c.class_id = ma.class_id
          WHERE ma.status::text = 'ACTIVE'::text AND ma.unassigned_at IS NULL
        ), attempt_scope AS (
         SELECT ms.org_id,
            ms.manager_user_id,
            ms.cohort_id,
            ma.user_id AS target_user_id,
            ma.attempt_id,
            ma.attempt_type,
            ma.source_attempt_id,
            ma.code_analysis_id,
            ma.status,
            ma.terminal_reason_code,
            ma.terminal_at,
            ma.updated_at,
            ma.assessment_open_at,
            ma.assessment_close_at,
            ma.review_due_at,
            ma.attempt_sequence_no,
            ma.project_id,
            ma.assessment_round_id,
            r.round_name,
            p.name AS project_name,
            p.sequence_no AS analysis_sequence_no,
            tm.team_id,
            t.name AS team_name,
            s.session_id
           FROM manager_scope ms
             JOIN project_membership pm ON pm.class_id = ms.class_id AND pm.status::text = 'ACTIVE'::text
             JOIN measurement_attempt ma ON ma.user_id = pm.user_id AND ma.project_id = pm.project_id
             JOIN project_assessment_round r ON r.assessment_round_id = ma.assessment_round_id
             JOIN project p ON p.project_id = r.project_id
             LEFT JOIN LATERAL ( SELECT x.team_id
                   FROM team_membership x
                  WHERE x.project_membership_id = pm.project_membership_id AND x.from_at <= COALESCE(ma.assessment_open_at, CURRENT_TIMESTAMP) AND (x.to_at IS NULL OR x.to_at > COALESCE(ma.assessment_open_at, CURRENT_TIMESTAMP))
                  ORDER BY x.from_at DESC
                 LIMIT 1) tm ON true
             LEFT JOIN team t ON t.team_id = tm.team_id
             LEFT JOIN assessment_session s ON s.attempt_id = ma.attempt_id
        ), round_context AS (
         SELECT attempt_scope.manager_user_id,
            attempt_scope.cohort_id,
            attempt_scope.target_user_id,
            attempt_scope.assessment_round_id,
            max(attempt_scope.project_id::text)::uuid AS project_id,
            max(attempt_scope.analysis_sequence_no) AS analysis_sequence_no,
            max(attempt_scope.round_name::text) AS round_name,
            max(attempt_scope.project_name::text) AS project_name,
            max(attempt_scope.team_id::text) FILTER (WHERE attempt_scope.attempt_type::text = 'INITIAL'::text)::uuid AS team_id,
            max(attempt_scope.team_name::text) FILTER (WHERE attempt_scope.attempt_type::text = 'INITIAL'::text) AS team_name,
            min(attempt_scope.assessment_open_at) FILTER (WHERE attempt_scope.attempt_type::text = 'INITIAL'::text) AS activity_start_at,
            max(COALESCE(attempt_scope.terminal_at, attempt_scope.assessment_close_at)) FILTER (WHERE attempt_scope.attempt_type::text = 'INITIAL'::text) AS activity_end_at
           FROM attempt_scope
          GROUP BY attempt_scope.manager_user_id, attempt_scope.cohort_id, attempt_scope.target_user_id, attempt_scope.assessment_round_id
        ), problem_result AS (
         SELECT a.attempt_id,
            ap.problem_id,
            ap.problem_no,
            ap.project_verification_concept_id AS concept_id,
            COALESCE(tc.canonical_name, ap.title::character varying) AS concept_name,
            ap.generation_status,
            COALESCE(st.answered_axis_count, 0) AS answered_axis_count,
            st.reach_level,
            st.hint_used_count
           FROM attempt_scope a
             JOIN assessment_problem ap ON ap.measurement_attempt_id = a.attempt_id OR ap.code_analysis_id = a.code_analysis_id AND ap.problem_scope::text = 'TEAM_SHARED_PROBLEM'::text
             LEFT JOIN project_verification_concept pvc ON pvc.project_concept_id = ap.project_verification_concept_id
             LEFT JOIN teaches tc ON tc.teaches_id = pvc.teaches_id
             LEFT JOIN LATERAL ( SELECT count(*) FILTER (WHERE ps.status::text = ANY (ARRAY['PASSED'::character varying::text, 'NOT_PASSED'::character varying::text]))::integer AS answered_axis_count,
                    COALESCE(max(SUBSTRING(ps.axis_code FROM 2)::integer) FILTER (WHERE ps.status::text = 'PASSED'::text), 0) AS reach_level,
                    (array_agg((ps.first_hint_answered_at IS NOT NULL)::integer + (ps.second_hint_answered_at IS NOT NULL)::integer ORDER BY (ps.status::text = 'PASSED'::text) DESC, (SUBSTRING(ps.axis_code FROM 2)::integer) DESC) FILTER (WHERE ps.status::text = ANY (ARRAY['PASSED'::character varying::text, 'NOT_PASSED'::character varying::text])))[1] AS hint_used_count
                   FROM problem_stage ps
                  WHERE ps.session_id = a.session_id AND ps.problem_id = ap.problem_id) st ON true
        ), review_target AS (
         SELECT a.manager_user_id,
            a.cohort_id,
            a.target_user_id,
            a.assessment_round_id,
            COALESCE(rs.retry_target_count, 0) AS target_count
           FROM attempt_scope a
             LEFT JOIN LATERAL ( SELECT x.report_id
                   FROM report x
                  WHERE x.assessment_round_id = a.assessment_round_id AND x.user_id = a.target_user_id AND x.lifecycle_status::text <> 'SUPERSEDED'::text
                  ORDER BY x.published_at DESC NULLS LAST, x.report_id DESC
                 LIMIT 1) rpt ON true
             LEFT JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
          WHERE a.attempt_type::text = 'INITIAL'::text
        ), review_problem AS (
         SELECT a.org_id,
            a.manager_user_id,
            a.cohort_id,
            a.target_user_id,
            a.attempt_id,
            a.attempt_type,
            a.source_attempt_id,
            a.code_analysis_id,
            a.status,
            a.terminal_reason_code,
            a.terminal_at,
            a.updated_at,
            a.assessment_open_at,
            a.assessment_close_at,
            a.review_due_at,
            a.attempt_sequence_no,
            a.project_id,
            a.assessment_round_id,
            a.round_name,
            a.project_name,
            a.analysis_sequence_no,
            a.team_id,
            a.team_name,
            a.session_id,
            pr.problem_id,
            pr.problem_no,
            pr.concept_id,
            pr.concept_name,
            pr.generation_status,
            pr.answered_axis_count,
            pr.reach_level AS to_level,
            src.reach_level AS from_level
           FROM attempt_scope a
             JOIN problem_result pr ON pr.attempt_id = a.attempt_id
             LEFT JOIN problem_result src ON src.attempt_id = a.source_attempt_id AND src.problem_id = pr.problem_id
          WHERE a.attempt_type::text = 'REVIEW'::text
        ), assessment_events AS (
         SELECT a.org_id,
            a.manager_user_id,
            a.cohort_id,
            a.target_user_id,
            a.assessment_round_id,
            a.attempt_id AS event_id,
            'ASSESSMENT'::text AS event_type,
            10 AS event_type_order,
            COALESCE(a.terminal_at, a.updated_at) AS occurred_at,
            'MEASUREMENT_ATTEMPT'::text AS source_entity_type,
            a.attempt_id AS source_entity_id,
            a.status::text AS source_status,
            a.session_id,
            a.session_id IS NOT NULL AS is_expandable,
            'OPEN_ASSESSMENT'::text AS detail_action_code,
            jsonb_build_object('attemptSequenceNo', a.attempt_sequence_no, 'problems', COALESCE(pp.problems, '[]'::jsonb)) AS payload,
            'COMPLETE'::text AS row_aggregation_status
           FROM attempt_scope a
             LEFT JOIN LATERAL ( SELECT jsonb_agg(jsonb_build_object('problemNo', pr.problem_no, 'problemId', pr.problem_id, 'conceptId', pr.concept_id, 'conceptName', pr.concept_name, 'generationStatus', pr.generation_status, 'reachLevel',
                        CASE
                            WHEN pr.generation_status::text = 'GENERATED'::text AND pr.answered_axis_count > 0 THEN pr.reach_level
                            ELSE NULL::integer
                        END, 'hintUsedCount',
                        CASE
                            WHEN pr.generation_status::text = 'GENERATED'::text AND pr.answered_axis_count > 0 THEN COALESCE(pr.hint_used_count, 0)
                            ELSE NULL::integer
                        END) ORDER BY pr.problem_no) AS problems
                   FROM problem_result pr
                  WHERE pr.attempt_id = a.attempt_id) pp ON true
          WHERE a.attempt_type::text = ANY (ARRAY['INITIAL'::character varying::text, 'RETRY'::character varying::text])
        ), report_events AS (
         SELECT ms.org_id,
            ms.manager_user_id,
            ms.cohort_id,
            rpt.user_id AS target_user_id,
            rpt.assessment_round_id,
            rpt.report_id AS event_id,
            'REPORT'::text AS event_type,
            20 AS event_type_order,
            COALESCE(rpt.published_at, rs.as_of_at) AS occurred_at,
            'REPORT'::text AS source_entity_type,
            rpt.report_id AS source_entity_id,
            rpt.lifecycle_status::text AS source_status,
            NULL::uuid AS session_id,
            true AS is_expandable,
            'OPEN_REPORT'::text AS detail_action_code,
            jsonb_build_object('reviewTargetCount', COALESCE(rt.target_count, 0), 'summary', rs.summary_payload ->> 'summary'::text) AS payload,
            rs.completion_status::text AS row_aggregation_status
           FROM manager_scope ms
             JOIN project_membership pm ON pm.class_id = ms.class_id AND pm.status::text = 'ACTIVE'::text
             JOIN report rpt ON rpt.user_id = pm.user_id
             JOIN report_snapshot rs ON rs.report_id = rpt.report_id AND rs.is_active
             JOIN project_assessment_round ar ON ar.assessment_round_id = rpt.assessment_round_id
             JOIN project p ON p.project_id = ar.project_id AND p.project_id = pm.project_id
             LEFT JOIN review_target rt ON rt.manager_user_id = ms.manager_user_id AND rt.cohort_id = ms.cohort_id AND rt.target_user_id = rpt.user_id AND rt.assessment_round_id = rpt.assessment_round_id
        ), review_events AS (
         SELECT rp.org_id,
            rp.manager_user_id,
            rp.cohort_id,
            rp.target_user_id,
            rp.assessment_round_id,
            rp.attempt_id AS event_id,
            'REVIEW'::text AS event_type,
            30 AS event_type_order,
            COALESCE(rp.terminal_at, rp.updated_at) AS occurred_at,
            'MEASUREMENT_ATTEMPT'::text AS source_entity_type,
            rp.attempt_id AS source_entity_id,
            rp.status::text AS source_status,
            max(rp.session_id::text)::uuid AS session_id,
            max(rp.session_id::text) IS NOT NULL AS is_expandable,
            'OPEN_ASSESSMENT'::text AS detail_action_code,
            jsonb_build_object('dueAt', max(rp.review_due_at), 'changes', jsonb_agg(jsonb_build_object('problemNo', rp.problem_no, 'problemId', rp.problem_id, 'conceptId', rp.concept_id, 'conceptName', rp.concept_name, 'fromReachLevel', rp.from_level, 'toReachLevel', rp.to_level, 'improved', COALESCE(rp.to_level, '-1'::integer) > COALESCE(rp.from_level, '-1'::integer)) ORDER BY rp.problem_no)) AS payload,
            'COMPLETE'::text AS row_aggregation_status
           FROM review_problem rp
          WHERE rp.answered_axis_count > 0
          GROUP BY rp.org_id, rp.manager_user_id, rp.cohort_id, rp.target_user_id, rp.assessment_round_id, rp.attempt_id, rp.terminal_at, rp.updated_at, rp.status
        ), review_closed_events AS (
         SELECT rp.org_id,
            rp.manager_user_id,
            rp.cohort_id,
            rp.target_user_id,
            rp.assessment_round_id,
            rp.attempt_id AS event_id,
            'REVIEW_CLOSED'::text AS event_type,
            40 AS event_type_order,
            max(COALESCE(rp.review_due_at, rp.terminal_at, rp.updated_at)) AS occurred_at,
            'MEASUREMENT_ATTEMPT'::text AS source_entity_type,
            rp.attempt_id AS source_entity_id,
            rp.status::text AS source_status,
            NULL::uuid AS session_id,
            false AS is_expandable,
            NULL::text AS detail_action_code,
            jsonb_build_object('dueAt', max(rp.review_due_at), 'missedCount', count(*), 'missed', jsonb_agg(jsonb_build_object('problemNo', rp.problem_no, 'problemId', rp.problem_id, 'conceptId', rp.concept_id, 'conceptName', rp.concept_name) ORDER BY rp.problem_no)) AS payload,
            'COMPLETE'::text AS row_aggregation_status
           FROM review_problem rp
          WHERE rp.answered_axis_count = 0 AND rp.generation_status::text = 'GENERATED'::text AND (rp.review_due_at <= CURRENT_TIMESTAMP OR (rp.status::text = ANY (ARRAY['EXPIRED'::character varying::text, 'FAILED'::character varying::text])) OR (rp.terminal_reason_code::text = ANY (ARRAY['NOT_ATTENDED'::character varying::text, 'REVIEW_NOT_COMPLETED'::character varying::text])))
          GROUP BY rp.org_id, rp.manager_user_id, rp.cohort_id, rp.target_user_id, rp.assessment_round_id, rp.attempt_id, rp.terminal_at, rp.updated_at, rp.status
        ), interview_events AS (
         SELECT ms.org_id,
            ms.manager_user_id,
            ms.cohort_id,
            i.target_user_id,
            i.assessment_round_id,
            i.interview_id AS event_id,
            'INTERVIEW'::text AS event_type,
            50 AS event_type_order,
            COALESCE(i.completed_at, i.started_at, i.created_at) AS occurred_at,
            'INTERVIEW'::text AS source_entity_type,
            i.interview_id AS source_entity_id,
            i.status::text AS source_status,
            NULL::uuid AS session_id,
            true AS is_expandable,
            'OPEN_INTERVIEW'::text AS detail_action_code,
            jsonb_build_object('recordStatus', i.status, 'identifiedCause', i.result_summary, 'managerNote', ib.manager_note, 'nextAction', act.next_action, 'nextActionConfirmedAt', ( SELECT min(COALESCE(nx.started_at, nx.created_at)) AS min
                   FROM interview nx
                  WHERE nx.target_user_id = i.target_user_id AND nx.class_id = i.class_id AND COALESCE(nx.started_at, nx.created_at) > COALESCE(i.completed_at, i.started_at, i.created_at)), 'managerActions', COALESCE(bi.items, '[]'::jsonb)) AS payload,
                CASE
                    WHEN ib.brief_id IS NULL THEN 'PARTIAL'::text
                    ELSE 'COMPLETE'::text
                END AS row_aggregation_status
           FROM manager_scope ms
             JOIN interview i ON i.class_id = ms.class_id
             LEFT JOIN LATERAL ( SELECT x.brief_id,
                    x.interview_id,
                    x.org_id,
                    x.cohort_id,
                    x.user_id,
                    x.assessment_round_id,
                    x.brief_type,
                    x.version_no,
                    x.is_first_interview,
                    x.brief_generation_policy_version,
                    x.status,
                    x.manager_note,
                    x.opening_remark_text,
                    x.opening_remark_generated_at,
                    x.created_by,
                    x.created_at,
                    x.updated_by,
                    x.updated_at,
                    x.confirmed_by,
                    x.confirmed_at,
                    x.row_version,
                    x.last_request_id,
                    x.last_request_fingerprint
                   FROM interview_brief x
                  WHERE x.interview_id = i.interview_id
                  ORDER BY x.version_no DESC
                 LIMIT 1) ib ON true
             LEFT JOIN LATERAL ( SELECT jsonb_agg(jsonb_build_object('displayOrder', bii.display_order, 'question', bii.question_text, 'rationale', bii.question_rationale) ORDER BY bii.display_order) AS items
                   FROM interview_brief_item bii
                  WHERE bii.brief_id = ib.brief_id AND bii.is_selected) bi ON true
             LEFT JOIN LATERAL ( SELECT ia.next_action
                   FROM interview_activity ia
                  WHERE ia.interview_id = i.interview_id
                  ORDER BY ia.occurred_at DESC
                 LIMIT 1) act ON true
        ), z AS (
         SELECT assessment_events.org_id,
            assessment_events.manager_user_id,
            assessment_events.cohort_id,
            assessment_events.target_user_id,
            assessment_events.assessment_round_id,
            assessment_events.event_id,
            assessment_events.event_type,
            assessment_events.event_type_order,
            assessment_events.occurred_at,
            assessment_events.source_entity_type,
            assessment_events.source_entity_id,
            assessment_events.source_status,
            assessment_events.session_id,
            assessment_events.is_expandable,
            assessment_events.detail_action_code,
            assessment_events.payload,
            assessment_events.row_aggregation_status
           FROM assessment_events
        UNION ALL
         SELECT report_events.org_id,
            report_events.manager_user_id,
            report_events.cohort_id,
            report_events.target_user_id,
            report_events.assessment_round_id,
            report_events.event_id,
            report_events.event_type,
            report_events.event_type_order,
            report_events.occurred_at,
            report_events.source_entity_type,
            report_events.source_entity_id,
            report_events.source_status,
            report_events.session_id,
            report_events.is_expandable,
            report_events.detail_action_code,
            report_events.payload,
            report_events.row_aggregation_status
           FROM report_events
        UNION ALL
         SELECT review_events.org_id,
            review_events.manager_user_id,
            review_events.cohort_id,
            review_events.target_user_id,
            review_events.assessment_round_id,
            review_events.event_id,
            review_events.event_type,
            review_events.event_type_order,
            review_events.occurred_at,
            review_events.source_entity_type,
            review_events.source_entity_id,
            review_events.source_status,
            review_events.session_id,
            review_events.is_expandable,
            review_events.detail_action_code,
            review_events.payload,
            review_events.row_aggregation_status
           FROM review_events
        UNION ALL
         SELECT review_closed_events.org_id,
            review_closed_events.manager_user_id,
            review_closed_events.cohort_id,
            review_closed_events.target_user_id,
            review_closed_events.assessment_round_id,
            review_closed_events.event_id,
            review_closed_events.event_type,
            review_closed_events.event_type_order,
            review_closed_events.occurred_at,
            review_closed_events.source_entity_type,
            review_closed_events.source_entity_id,
            review_closed_events.source_status,
            review_closed_events.session_id,
            review_closed_events.is_expandable,
            review_closed_events.detail_action_code,
            review_closed_events.payload,
            review_closed_events.row_aggregation_status
           FROM review_closed_events
        UNION ALL
         SELECT interview_events.org_id,
            interview_events.manager_user_id,
            interview_events.cohort_id,
            interview_events.target_user_id,
            interview_events.assessment_round_id,
            interview_events.event_id,
            interview_events.event_type,
            interview_events.event_type_order,
            interview_events.occurred_at,
            interview_events.source_entity_type,
            interview_events.source_entity_id,
            interview_events.source_status,
            interview_events.session_id,
            interview_events.is_expandable,
            interview_events.detail_action_code,
            interview_events.payload,
            interview_events.row_aggregation_status
           FROM interview_events
        )
 SELECT z.org_id,
    z.manager_user_id,
    z.cohort_id,
    z.target_user_id,
    rc.project_id,
    z.assessment_round_id,
    rc.analysis_sequence_no,
    rc.round_name,
    rc.project_name,
    rc.team_id AS team_id_at_round,
    rc.team_name AS team_name_at_round,
    rc.activity_start_at AS round_activity_start_at,
    rc.activity_end_at AS round_activity_end_at,
    rc.analysis_sequence_no AS round_sort_at,
    z.event_id,
    z.event_type::character varying(100) AS event_type,
    z.event_type_order,
    z.occurred_at,
    z.source_entity_type::character varying(100) AS source_entity_type,
    z.source_entity_id,
    z.source_status::character varying(100) AS source_status,
    z.session_id,
    z.is_expandable,
    z.detail_action_code::character varying(100) AS detail_action_code,
    z.payload,
    z.row_aggregation_status::character varying(100) AS row_aggregation_status,
    false AS is_stale,
    CURRENT_TIMESTAMP AS as_of_at,
    2 AS calculation_version
   FROM z
     JOIN round_context rc ON rc.manager_user_id = z.manager_user_id AND rc.cohort_id = z.cohort_id AND rc.target_user_id = z.target_user_id AND rc.assessment_round_id = z.assessment_round_id;
