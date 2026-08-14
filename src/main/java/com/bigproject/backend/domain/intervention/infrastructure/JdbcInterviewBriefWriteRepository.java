package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.application.dto.InterviewBriefAiResponse;
import com.bigproject.backend.domain.intervention.domain.InterviewBriefWriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcInterviewBriefWriteRepository implements InterviewBriefWriteRepository {

	/**
	 * 브리프 생성 정책 버전. 생성 시점에 고정한다(테이블 COMMENT) — 나중에 프롬프트나 항목 규칙이
	 * 바뀌어도 이미 만든 브리프가 어느 규칙으로 나왔는지 남는다.
	 */
	private static final int BRIEF_GENERATION_POLICY_VERSION = 1;

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 면담 행. {@code org_id}·{@code cohort_id}·{@code class_id} 등은 후보에서 그대로 가져온다 —
	 * 후보가 "판정 당시 귀속을 고정"한 값이라(테이블 COMMENT) 현재 소속을 다시 조회하면
	 * 반이 바뀐 교육생의 면담이 엉뚱한 반에 붙는다.
	 */
	@Override
	public UUID insertInterview(UUID candidateId, UUID assigneeUserId) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO interview
				    (candidate_id, org_id, cohort_id, class_id, target_user_id,
				     assessment_round_id, assignee_id, status, created_by)
				SELECT ic.candidate_id, ic.org_id, ic.cohort_id, ic.class_id, ic.user_id,
				       ic.assessment_round_id, ?, 'PENDING', ?
				FROM interview_candidate ic
				WHERE ic.candidate_id = ?
				RETURNING interview_id
				""", UUID.class, assigneeUserId, assigneeUserId, candidateId);
	}

	@Override
	public void markInterviewCreated(UUID candidateId) {
		jdbcTemplate.update("""
				UPDATE interview_candidate
				   SET status      = 'INTERVIEW_CREATED',
				       updated_at  = CURRENT_TIMESTAMP,
				       row_version = row_version + 1
				 WHERE candidate_id = ?
				   AND status       = 'ELIGIBLE'
				""", candidateId);
	}

	@Override
	public int nextVersionNo(UUID interviewId) {
		Integer max = jdbcTemplate.queryForObject(
				"SELECT COALESCE(MAX(version_no), 0) FROM interview_brief WHERE interview_id = ?",
				Integer.class, interviewId);
		return (max == null ? 0 : max) + 1;
	}

	@Override
	public UUID insertBriefDraft(UUID interviewId, UUID candidateId, String briefType,
			int versionNo, boolean firstInterview, UUID actorUserId) {
		/*
		 * org_id·cohort_id·user_id·assessment_round_id는 "RLS와 조회 성능을 위한 의도적
		 * 비정규화"이고 Interview 경로와 일치해야 한다(테이블 COMMENT). 그래서 후보에서
		 * 직접 복사한다 — 따로 넘겨받으면 호출부마다 어긋날 수 있다.
		 */
		return jdbcTemplate.queryForObject("""
				INSERT INTO interview_brief
				    (interview_id, org_id, cohort_id, user_id, assessment_round_id,
				     brief_type, version_no, is_first_interview, brief_generation_policy_version,
				     status, created_by)
				SELECT ?, ic.org_id, ic.cohort_id, ic.user_id, ic.assessment_round_id,
				       ?, ?, ?, ?, 'DRAFT', ?
				FROM interview_candidate ic
				WHERE ic.candidate_id = ?
				RETURNING brief_id
				""", UUID.class,
				interviewId, briefType, versionNo, firstInterview,
				BRIEF_GENERATION_POLICY_VERSION, actorUserId, candidateId);
	}

	@Override
	public Optional<ExistingDraft> findReusableDraft(UUID interviewId) {
		List<ExistingDraft> rows = jdbcTemplate.query("""
				SELECT brief_id, version_no
				FROM interview_brief
				WHERE interview_id        = ?
				  AND status              = 'DRAFT'
				  AND opening_remark_text IS NULL
				ORDER BY version_no DESC
				LIMIT 1
				""",
				(rs, rowNum) -> new ExistingDraft(
						rs.getObject("brief_id", UUID.class), rs.getInt("version_no")),
				interviewId);

		return rows.stream().findFirst();
	}

	/**
	 * 재시도 전 청소.
	 *
	 * <p>항목은 보통 없다(TX2까지 못 갔으므로). 근거는 TX1에서 만들어져 남아 있으므로
	 * 지워야 한다 — 안 지우면 시도마다 쌓여 AI가 <b>버려진 시도의 근거 id</b>를 받게 된다.
	 *
	 * <p>순서가 중요하다. {@code interview_brief_item.interview_source_id}가 FK라
	 * 항목을 먼저 지워야 근거를 지울 수 있다.
	 */
	@Override
	public void clearDraftArtifacts(UUID briefId, UUID interviewId) {
		jdbcTemplate.update("DELETE FROM interview_brief_item WHERE brief_id = ?", briefId);
		jdbcTemplate.update("DELETE FROM interview_source WHERE interview_id = ?", interviewId);
	}

	@Override
	public void saveGeneratedBrief(UUID briefId, String openingRemark, UUID requestId, String requestFingerprint) {
		jdbcTemplate.update("""
				UPDATE interview_brief
				   SET opening_remark_text         = ?,
				       opening_remark_generated_at = CURRENT_TIMESTAMP,
				       last_request_id             = ?,
				       last_request_fingerprint    = ?,
				       updated_at                  = CURRENT_TIMESTAMP,
				       row_version                 = row_version + 1
				 WHERE brief_id = ?
				""", openingRemark, requestId, requestFingerprint, briefId);
	}

	/**
	 * 질문 항목.
	 *
	 * <p>{@code source_type}을 {@code interview_source}에서 조회해 넣는다 — 고정 상수가 아니다.
	 * 테이블 COMMENT의 "INTERVIEW_SOURCE 단일 값"은 <i>항목의 출처가 InterviewSource뿐이고
	 * MANUAL 항목이 없다</i>는 뜻이고, 시드 실측값은 {@code CANDIDATE_REASON}이다.
	 *
	 * <p>{@code is_selected=FALSE}로 넣는다. 확정(IV-06) 때 {@code TRUE}로 올린다 —
	 * 생성 직후엔 매니저가 실제로 아무것도 고르지 않았고, DRAFT 선택 0건은 허용된다(COMMENT).
	 */
	@Override
	public void insertBriefItems(UUID briefId, List<InterviewBriefAiResponse.Item> items) {
		jdbcTemplate.batchUpdate("""
				INSERT INTO interview_brief_item
				    (brief_id, source_type, interview_source_id, question_text, question_rationale,
				     suggested_order, is_selected)
				SELECT ?, s.source_type, s.interview_source_id, ?, ?, ?, FALSE
				FROM interview_source s
				WHERE s.interview_source_id = ?
				""", items.stream()
						.map(item -> new Object[] {
								briefId, item.questionText(), item.questionRationale(),
								item.suggestedOrder(), item.interviewSourceId() })
						.toList());
	}

	/**
	 * 생성 이력.
	 *
	 * <p>{@code before_snapshot}·{@code after_snapshot}이 둘 다 JSONB NOT NULL이라 생성 시
	 * before에 빈 객체를 넣는다 — 만들어지기 전 상태라 담을 값이 없다.
	 */
	@Override
	public void insertItemHistory(UUID briefId, UUID actorUserId, UUID requestId) {
		jdbcTemplate.update("""
				INSERT INTO interview_brief_item_history
				    (brief_item_id, change_type, command_code, command_request_id,
				     brief_row_version, before_snapshot, after_snapshot, changed_by, changed_at)
				SELECT bi.brief_item_id, 'CREATED', 'INITIALIZE_BRIEF', ?,
				       b.row_version, '{}'::jsonb,
				       JSONB_BUILD_OBJECT('questionText', bi.question_text,
				                          'suggestedOrder', bi.suggested_order),
				       ?, CURRENT_TIMESTAMP
				FROM interview_brief_item bi
				JOIN interview_brief b ON b.brief_id = bi.brief_id
				WHERE bi.brief_id = ?
				""", requestId, actorUserId, briefId);
	}

	/**
	 * 재생성 전 정리. {@code SUPERSEDED}는 이미 밀려난 것이라 건드리지 않는다.
	 *
	 * <p>{@code confirmed_by}·{@code confirmed_at}은 그대로 둔다 —
	 * {@code ck_interview_brief_status_2}가 {@code SUPERSEDED}에는 아무 조건도 걸지 않으므로
	 * "언제 누가 확정했던 버전인지"를 지울 이유가 없다.
	 */
	@Override
	public int supersedeExistingDrafts(UUID interviewId) {
		return jdbcTemplate.update("""
				UPDATE interview_brief
				   SET status      = 'SUPERSEDED',
				       updated_at  = CURRENT_TIMESTAMP,
				       row_version = row_version + 1
				 WHERE interview_id = ?
				   AND status IN ('DRAFT', 'CONFIRMED')
				""", interviewId);
	}

	@Override
	public boolean hasPriorCompletedInterview(UUID traineeUserId) {
		Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM interview
				WHERE target_user_id = ? AND status = 'COMPLETED'
				""", Integer.class, traineeUserId);
		return count != null && count > 0;
	}
}
