package com.bigproject.backend.domain.intervention.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MG-03 면담 목록 조회.
 *
 * <p>집계 조회라 JPA가 아니라 네이티브 SQL이다. 대부분을 {@code manager_interview_list_view}가
 * 이미 계산해 두었으므로 <b>조인을 새로 짜지 않는다</b> — 담당 반 스코프
 * ({@code manager_assignment … status='ACTIVE' AND unassigned_at IS NULL})도 그 뷰 안에 있다.
 *
 * <h2>뷰가 주지 않아 따로 붙이는 값</h2>
 *
 * <pre>
 * attemptId        무효 확인 API(PATCH /assessment-attempts/{id}/validity) 호출에 필요
 * briefStatus      뷰는 confirmed_brief_id만 준다 — DRAFT 여부와 생성 실패를 구분하지 못한다
 * excludedByName   뷰는 excluded_by(UUID)만 준다. 화면은 이름을 쓴다
 * latestNextAction 뷰는 follow_up_action_count만 준다. 화면은 문구를 쓴다
 * </pre>
 *
 * <h2>위험 사유를 뷰 컬럼으로 읽지 않는 이유</h2>
 *
 * <p>뷰의 {@code active_matched_reason_codes}는 {@code evaluation_status='MATCHED'}만 모은다.
 * 1차 회차는 비교할 직전 회차가 없어 {@code NOT_APPLICABLE/FIRST_MINI_PROJECT}로 등재되므로
 * (테이블 COMMENT) 그 배열이 <b>비어 버린다</b> — 화면의 `관찰` 배지를 그릴 수 없다.
 * 그래서 {@code interview_candidate_reason}을 직접 읽어 평가 상태까지 가져온다.
 */
public interface InterviewListRepository {

	/**
	 * 회차 하나의 면담 케이스를 정렬된 상태로 전부 반환한다.
	 *
	 * <p>페이저가 없는 화면이라(담당 반 한 회차 = 수십 명) 페이지네이션을 두지 않는다.
	 * 정렬은 사용자가 고를 수 없는 서버 고정 규칙이다(정의서 §3 "이미 급한 순으로 온다").
	 */
	List<InterviewListRow> findCases(InterviewListQuery query);

	/**
	 * 필터와 무관한 <b>회차 전체</b> 집계. 필터 옵션 라벨에 개수를 싣기 때문에
	 * 필터링된 결과로 세면 안 된다(OP-03·MG-07과 같은 원칙).
	 */
	List<InterviewCountRow> countByRound(UUID managerUserId, UUID orgId, UUID assessmentRoundId);

	/**
	 * 이 회차에서 면담 대상이 있는 반. 화면 <b>반 필터 드롭다운</b>을 채운다.
	 *
	 * <p>상태·위험 유형과 달리 값 집합을 고정할 수 없다 — 매니저마다 담당이 다르다.
	 *
	 * <p>{@code items[]}의 {@code className}으로 유도하지 않는 이유: 반 필터를 A반으로 걸면
	 * 결과에 A반만 남아 <b>드롭다운에서 나머지 반이 사라진다.</b> {@code counts}와 같은 이유로
	 * 필터와 무관하게 내려야 한다. 다만 <b>회차 스코프는 건다</b> — 아래 참고.
	 *
	 * <p>🔴 {@code assessmentRoundId}가 필요한 이유(2026-08-26): 이 값이 없던 시절에는
	 * {@code manager_assignment}에서 담당 반을 전부 긁어왔고, 그 결과 <b>A반이 세 번 나왔다.</b>
	 * 5기 A반·6기 A반·7기 A반은 이름만 같고 {@code class_id}가 서로 다른 별개의 행이라
	 * {@code SELECT DISTINCT (class_id, name)}으로는 접히지 않는다. 회차는 곧 한 기수이므로
	 * 회차로 좁히면 동명 반이 하나로 정리되고, 덤으로 그 회차에 면담 대상이 없는 반도 빠진다.
	 */
	List<ClassOption> findManagedClasses(UUID managerUserId, UUID orgId, UUID assessmentRoundId);

	record ClassOption(UUID classId, String className) {
	}

	/**
	 * @param search   이름 부분 일치. 공백이면 무시한다
	 * @param status   {@code PLANNED} / {@code DONE} / {@code EXCLUDED}. null이면 전체
	 * @param riskType {@code INVALID} / {@code LOW_PERSISTENT} / {@code DECLINE} / {@code OBSERVE}.
	 *                 null이면 전체
	 */
	record InterviewListQuery(
			UUID managerUserId,
			UUID orgId,
			UUID assessmentRoundId,
			String search,
			String status,
			String riskType,
			UUID classId) {
	}

	/**
	 * 목록 한 행. 화면 표시용 가공(상태 3종 병합·위험 유형 판정)은 하지 않고
	 * <b>DB가 준 값 그대로</b> 담는다 — 가공은 application 계층이 한다.
	 *
	 * <p>⚠️ {@code screenStatus}·{@code screenRiskType}은 <b>SQL이 계산해 내려준 값</b>이다.
	 * 같은 규칙을 Java에서 다시 구현하지 않는다 — 필터·정렬·집계가 SQL 식을 쓰는데 표시만
	 * Java가 따로 계산하면 "개수는 5인데 배지는 4개"처럼 조용히 어긋난다.
	 *
	 * @param screenStatus    화면 3종 {@code PLANNED} / {@code DONE} / {@code EXCLUDED}
	 * @param screenRiskType  화면 4종 {@code INVALID} / {@code LOW_PERSISTENT} / {@code DECLINE} / {@code OBSERVE}
	 * @param reasonCodes     활성 사유 코드. 1차는 NOT_APPLICABLE 사유만 들어 있을 수 있다
	 * @param reasonSummaries 사유 요약. 판정 배치가 만든 완성된 문장이 화면 문구 그대로다
	 *                        ("평균 도달 단계 2.33 → 1.67. 2단 미만 2개."). 대괄호 태그는 없다(30차 R8)
	 * @param briefStatus     {@code interview_brief.status}. 브리프가 없으면 null
	 * @param briefHasContent {@code opening_remark_text IS NOT NULL} — 생성 실패한 DRAFT를 가른다
	 */
	record InterviewListRow(
			UUID candidateId,
			UUID traineeUserId,
			String traineeName,
			UUID classId,
			String className,
			String screenStatus,
			String screenRiskType,
			String candidateStatus,
			String interviewStatus,
			List<String> reasonCodes,
			List<String> reasonSummaries,
			List<String> reasonEvaluationStatuses,
			boolean firstMiniProject,
			String validityReviewStatus,
			UUID attemptId,
			String briefStatus,
			boolean briefHasContent,
			Instant excludedAt,
			String excludedByName,
			Instant completedAt,
			String latestNextAction) {
	}

	/** 회차 전체 상태별 개수. {@code status}는 병합 전 원본이 아니라 화면 3종이다. */
	record InterviewCountRow(String status, String riskType, long count) {
	}
}
