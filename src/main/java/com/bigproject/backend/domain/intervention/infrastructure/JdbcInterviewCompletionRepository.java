package com.bigproject.backend.domain.intervention.infrastructure;

import com.bigproject.backend.domain.intervention.domain.InterviewCompletionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcInterviewCompletionRepository implements InterviewCompletionRepository {

	/** 면담 기록의 활동 유형. 시드 실측값이 이 하나다. */
	private static final String ACTIVITY_TYPE_NOTE = "INTERVIEW_NOTE";

	private final JdbcTemplate jdbcTemplate;

	@Override
	public void replaceCauses(UUID interviewId, List<String> causeCodes, UUID actorUserId) {
		jdbcTemplate.update("DELETE FROM interview_cause WHERE interview_id = ?", interviewId);

		if (causeCodes == null || causeCodes.isEmpty()) {
			return;
		}
		jdbcTemplate.batchUpdate("""
				INSERT INTO interview_cause (interview_id, cause_code, recorded_by, recorded_at)
				VALUES (?, ?, ?, CURRENT_TIMESTAMP)
				""", causeCodes.stream()
						.distinct()
						.map(code -> new Object[] { interviewId, code, actorUserId })
						.toList());
	}

	/**
	 * 매니저 기록.
	 *
	 * <h2>🔴 32차 R8 — 빈 사유를 문구로 바꾸지 않는다</h2>
	 *
	 * <p>{@code content}가 NOT NULL이라 종전에는 상세 사유가 비면 {@code "(기록 없음)"}을 넣었다.
	 * 그러면 셋이 어긋난다.
	 *
	 * <ul>
	 *   <li><b>화면 문구를 서버가 만든다.</b> 「없음」을 무엇이라 쓸지는 화면이 정해 온 것이고,
	 *       다른 자리에서는 프론트가 {@code —}로 그린다</li>
	 *   <li><b>다시 열면 입력칸에 그 글자가 들어 있다.</b> 매니저가 이어서 쓰려면 먼저 지워야 한다</li>
	 *   <li>그 상태로 저장하면 <b>{@code (기록 없음)}이 진짜 타이핑한 서술로 남아</b>
	 *       「안 쓴 것」과 「그렇게 쓴 것」이 구분되지 않는다</li>
	 * </ul>
	 *
	 * <p>대신 <b>빈 문자열</b>을 넣는다. NOT NULL을 만족하면서도 문구가 아니고, 읽는 쪽이
	 * {@code NULLIF}로 되돌려 API는 {@code null}을 준다 — 25차 R9(매니저 이름)에서 같은 제약을
	 * 같은 방법으로 풀었다. {@code content}에는 길이 CHECK가 없어 빈 문자열이 들어간다.
	 *
	 * <p>{@code nextAction}은 애초에 치환이 없었다. 두 필드가 이제 같은 규칙이다.
	 */
	@Override
	public void appendActivity(UUID interviewId, String why, String nextAction, UUID actorUserId) {
		jdbcTemplate.update("""
				INSERT INTO interview_activity
				    (interview_id, author_id, activity_type, content, occurred_at, next_action)
				VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
				""",
				interviewId, actorUserId, ACTIVITY_TYPE_NOTE,
				(why == null || why.isBlank()) ? "" : why,
				(nextAction == null || nextAction.isBlank()) ? null : nextAction);
	}

	/**
	 * 브리프 확정.
	 *
	 * <p>항목을 전부 {@code is_selected=TRUE}로 올린다. CHECK가 {@code selected_by}·
	 * {@code selected_at}·{@code display_order}를 동반 NOT NULL로 강제하므로 함께 채운다 —
	 * {@code display_order}는 {@code suggested_order}를 그대로 쓴다(화면에 순서를 바꾸는 UI가 없다).
	 *
	 * <p>{@code UNIQUE(brief_id, display_order)}가 선택 항목 범위에 걸려 있어
	 * {@code suggested_order}가 중복되면 실패한다 — AI 계약이 "1부터 중복 없는 연속 정수"를
	 * 요구하므로 정상 응답에서는 발생하지 않는다.
	 */
	@Override
	public void confirmBrief(UUID briefId, UUID actorUserId) {
		jdbcTemplate.update("""
				UPDATE interview_brief_item
				   SET is_selected   = TRUE,
				       selected_by   = ?,
				       selected_at   = CURRENT_TIMESTAMP,
				       display_order = suggested_order,
				       updated_at    = CURRENT_TIMESTAMP,
				       row_version   = row_version + 1
				 WHERE brief_id    = ?
				   AND is_selected = FALSE
				""", actorUserId, briefId);

		jdbcTemplate.update("""
				UPDATE interview_brief
				   SET status       = 'CONFIRMED',
				       confirmed_by = ?,
				       confirmed_at = CURRENT_TIMESTAMP,
				       updated_by   = ?,
				       updated_at   = CURRENT_TIMESTAMP,
				       row_version  = row_version + 1
				 WHERE brief_id = ?
				   AND status   = 'DRAFT'
				""", actorUserId, actorUserId, briefId);
	}

	@Override
	public void insertConfirmHistory(UUID briefId, UUID actorUserId, UUID requestId) {
		jdbcTemplate.update("""
				INSERT INTO interview_brief_item_history
				    (brief_item_id, change_type, command_code, command_request_id,
				     brief_row_version, before_snapshot, after_snapshot, changed_by, changed_at)
				SELECT bi.brief_item_id, 'SELECTED', 'CONFIRM_BRIEF', ?,
				       b.row_version,
				       JSONB_BUILD_OBJECT('isSelected', FALSE),
				       JSONB_BUILD_OBJECT('isSelected', TRUE, 'displayOrder', bi.display_order),
				       ?, CURRENT_TIMESTAMP
				FROM interview_brief_item bi
				JOIN interview_brief b ON b.brief_id = bi.brief_id
				WHERE bi.brief_id = ?
				""", requestId, actorUserId, briefId);
	}

	@Override
	public int countSelectedItems(UUID briefId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM interview_brief_item WHERE brief_id = ? AND is_selected = TRUE",
				Integer.class, briefId);
		return count == null ? 0 : count;
	}

	/**
	 * 직행 종결. {@code started_at}을 모르면 {@code completed_at}과 같은 값으로 채운다 —
	 * COMMENT가 규정한 방식이고, {@code ck_interview_status_2}가 COMPLETED에
	 * {@code started_at}·{@code started_by}를 요구하기 때문이다.
	 */
	@Override
	public int completeInterview(UUID interviewId, UUID actorUserId) {
		return jdbcTemplate.update("""
				UPDATE interview
				   SET status       = 'COMPLETED',
				       started_at   = COALESCE(started_at, CURRENT_TIMESTAMP),
				       started_by   = COALESCE(started_by, ?),
				       completed_at = CURRENT_TIMESTAMP,
				       completed_by = ?,
				       updated_at   = CURRENT_TIMESTAMP,
				       row_version  = row_version + 1
				 WHERE interview_id = ?
				   AND status IN ('PENDING', 'IN_PROGRESS')
				""", actorUserId, actorUserId, interviewId);
	}

	@Override
	public void insertStatusHistory(UUID interviewId, UUID actorUserId, UUID requestId) {
		jdbcTemplate.update("""
				INSERT INTO interview_status_history
				    (interview_id, from_status, to_status, changed_by, changed_at, reason, request_id)
				VALUES (?, 'PENDING', 'COMPLETED', ?, CURRENT_TIMESTAMP, ?, ?)
				""", interviewId, actorUserId, "브리프 저장과 함께 종결", requestId);
	}

	@Override
	public boolean isCompleted(UUID interviewId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM interview WHERE interview_id = ? AND status = 'COMPLETED'",
				Integer.class, interviewId);
		return count != null && count > 0;
	}
}
