package com.bigproject.backend.domain.intervention.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MG-03 면담 목록.
 *
 * <p>면담 대상은 <b>매니저가 고르는 것이 아니라 위험 판정이 켜지면 자동 등재된다</b>
 * (정의서 §1). 그래서 이 서비스에는 케이스를 만드는 동작이 없고 조회·제외·되돌리기만 있다.
 */
public interface InterviewService {

	InterviewListResult findCases(InterviewListCriteria criteria);

	/**
	 * 이번 회차 대상에서 뺀다. 되돌릴 수 있다(정의서 §6).
	 *
	 * <p>화면이 확인 다이얼로그 없이 즉시 실행하고 배너로 되돌리기를 남기므로 요청 본문이 없다.
	 * 사유 코드는 서버가 채운다 — {@code exclusion_reason_code}가 NOT NULL이다.
	 *
	 * <p>연결된 면담이 이미 시작·종결됐으면 거부한다(2026-08-14 DDL COMMENT).
	 */
	void exclude(UUID managerUserId, UUID orgId, UUID caseId);

	/**
	 * 제외를 되돌린다.
	 *
	 * <p>복귀 상태는 <b>연결된 면담 유무로 갈린다</b> — 면담이 남아 있으면
	 * {@code INTERVIEW_CREATED}, 없으면 {@code ELIGIBLE}이다. 브리프를 만들어 둔 케이스를
	 * 되돌리면 그 브리프가 그대로 살아난다.
	 */
	void reinclude(UUID managerUserId, UUID orgId, UUID caseId);

	/**
	 * @param search   이름 부분 일치
	 * @param status   {@code PLANNED} / {@code DONE} / {@code EXCLUDED}. null이면 전체
	 * @param riskType {@code INVALID} / {@code LOW_PERSISTENT} / {@code DECLINE} / {@code OBSERVE}
	 */
	record InterviewListCriteria(
			UUID managerUserId,
			UUID orgId,
			UUID assessmentRoundId,
			String search,
			String status,
			String riskType,
			UUID classId) {
	}

	/**
	 * @param counts     상태별 개수. <b>필터와 무관한 회차 전체 기준</b>이다 —
	 *                   필터 옵션 라벨에 개수를 싣기 때문에 필터링된 결과로 세면 안 된다
	 * @param riskCounts 위험 유형별 개수. 위와 같은 이유
	 */
	record InterviewListResult(
			List<InterviewCaseView> items,
			int total,
			Map<String, Long> counts,
			Map<String, Long> riskCounts,
			List<ClassOptionView> classes,
			RoundView round) {
	}

	/**
	 * 반 필터 드롭다운 재료. 상태·위험 유형과 달리 <b>매니저마다 다르므로</b> 서버가 준다.
	 * {@code counts}처럼 필터와 무관한 전체 목록이다.
	 */
	record ClassOptionView(UUID classId, String className) {
	}

	/** 담당 기수의 회차 목록. 화면 드롭다운을 채운다. */
	List<RoundOptionView> findRoundOptions(UUID managerUserId, UUID orgId);

	record RoundOptionView(UUID assessmentRoundId, String label, int roundNo, String status) {
	}

	/**
	 * @param resultStatus  {@code PENDING}이면 화면이 "이 회차는 아직 결과가 없어요"를 그린다.
	 *                      리포트 발행 전이라 위험 판정 자체가 없는 상태다
	 * @param firstRound    1차. 비교할 직전 회차가 없어 위험 유형이 붙지 않는다(9-5)
	 * @param publishedAt   리포트 발행 시각 = 위험 판정 등재 시각
	 * @param daysSincePublish 발행 후 경과일. 상단 경고줄("N일째 안 끝났습니다")에 쓴다.
	 *                      대기는 개인별이 아니라 <b>회차 경과</b>다 — 일괄 발행이라 회차 안에서
	 *                      모두 같은 값이라서 개인별 열을 두지 않는다(정의서 §3)
	 */
	record RoundView(
			UUID assessmentRoundId,
			String label,
			String resultStatus,
			boolean firstRound,
			Instant publishedAt,
			Integer daysSincePublish) {
	}

	/**
	 * 케이스 한 건.
	 *
	 * @param riskSummary   위험 판정 근거 문구. {@code interview_candidate_reason.reason_summary}를
	 *                      그대로 쓴다 — 판정 배치가 수치를 박아 만든 완성된 문장이며 화면 문구가
	 *                      곧 이 값이다("평균 도달 단계 2.33 → 1.67. 2단 미만 2개."). 대괄호 태그는
	 *                      붙지 않는다(30차 R8 — 태그가 섞인 값은 정책 v1 이전 더미 시드였다)
	 * @param briefState    {@code NONE} / {@code FAILED} / {@code DRAFT} / {@code CONFIRMED}.
	 *                      화면의 버튼 문구를 가른다(브리프 생성 / 다시 생성 / 브리프 열기 / 브리프 수정)
	 * @param attemptId     무효 확인 API 호출에 필요하다. 화면은 이 값을 모른 채
	 *                      caseId만 들고 있으므로 목록이 실어 보내야 한다
	 * @param voidConfirmed 무효 확인을 마쳤는가. {@code validity_review_status='RESTORED_VALID'}에서 유도한다
	 */
	record InterviewCaseView(
			UUID caseId,
			UUID traineeId,
			String name,
			UUID classId,
			String className,
			String status,
			String riskType,
			String riskSummary,
			String briefState,
			UUID attemptId,
			boolean voidConfirmed,
			boolean voidReviewPending,
			Instant excludedAt,
			String excludedBy,
			Instant interviewedAt,
			String nextAction) {
	}
}
