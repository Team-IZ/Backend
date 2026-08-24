package com.bigproject.backend.domain.intervention.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 면담 목록의 회차 선택·회차 메타.
 *
 * <h2>{@code manager_interview_list_context_view}를 쓰지 않는 이유</h2>
 *
 * <p>그 뷰는 회차를 <b>매니저가 고를 수 없다</b> — 안쪽 LATERAL이
 * {@code ORDER BY (OPEN·PLANNED 먼저), sequence_no DESC, round_no DESC LIMIT 1}로
 * 현재 회차 하나를 자동 선택하고 모든 집계를 그 회차 기준으로 낸다. 화면은 회차 드롭다운으로
 * 지난 회차를 되짚어 보므로(정의서 §3 "지난 회차를 보려면 위에서 회차를 바꾸세요")
 * 선택 회차 기준 집계가 필요하다. 집계는 {@link InterviewListRepository#countByRound}가 갖는다.
 *
 * <p>회차 목록 자체는 그 뷰의 {@code available_assessment_rounds}와 같은 질의다. 다만 그쪽은
 * JSONB를 TEXT로 캐스팅해 내보내므로 파싱이 한 겹 더 든다 — 같은 값을 행으로 직접 읽는다.
 */
public interface InterviewRoundRepository {

	/**
	 * 담당 기수의 회차 전부. 프론트의 고정 배열({@code ROUND_OPTIONS})을 대체한다.
	 *
	 * <p>{@code PLANNED} 회차도 뺀다 없이 담는다 — 결과가 아직 없는 회차를 고르면 화면이
	 * "이 회차는 아직 결과가 없어요"를 그리는 것이 정의된 동작이라(§6), 목록에서 아예 빼면
	 * 그 상태를 보여줄 방법이 없다. 삭제된 회차만 제외한다.
	 *
	 * @param cohortId 기수 하나로 좁힌다. {@code null}이면 담당 기수 전부다.
	 *                 <b>한 매니저가 여러 기수에서 반을 맡으면 좁히지 않는 쪽이 위험하다</b> —
	 *                 종료 기수와 진행 기수의 회차가 한 드롭다운에 섞이고, 화면이 고른 회차가
	 *                 보고 있는 기수의 것이 아니면 격자·명부가 조용히 빈 채로 그려진다.
	 */
	List<RoundOption> findRoundOptions(UUID managerUserId, UUID orgId, UUID cohortId);

	/** 선택한 회차의 표시용 메타. 담당 밖 회차면 비어 있다. */
	Optional<RoundMeta> findRoundMeta(UUID managerUserId, UUID orgId, UUID assessmentRoundId);

	/**
	 * @param label 화면 드롭다운 문구. 회차 번호가 <b>프로젝트 안에서만 유일</b>하므로
	 *              프로젝트명을 함께 붙여야 서로 다른 회차가 구분된다
	 */
	record RoundOption(
			UUID assessmentRoundId,
			UUID projectId,
			/** 32차 R2 — 회차를 생략했을 때 「이번 회차」를 고르는 판정에 넘긴다. */
			UUID cohortId,
			String projectName,
			int roundNo,
			String roundName,
			String label,
			String status) {
	}

	/**
	 * @param publishedAt     회차 리포트 발행 시각. 화면의 "N일째 안 끝났습니다" 경고줄이 이 값을 센다
	 * @param outcomeJudgedAt 위험 판정이 끝난 시각. 이 회차 수행 중 가장 이른 판정 시각이며,
	 *                        <b>면담 후보가 등재된 시점</b>이기도 하다
	 * @param firstRound      1차인가. 비교할 직전 회차가 없어 위험 유형이 붙지 않는다(9-5)
	 */
	record RoundMeta(
			UUID assessmentRoundId,
			int roundNo,
			String label,
			boolean firstRound,
			Instant publishedAt,
			Instant outcomeJudgedAt) {

		/**
		 * 결과가 나왔는가.
		 *
		 * <h2>🔴 32차 R1 — 리포트 발행이 아니라 <b>위험 판정</b>이 기준이다</h2>
		 *
		 * <p>종전에는 {@code publishedAt}으로 판정하면서 "발행 전에는 위험 판정 자체가 없어 목록이
		 * 비는 것이 정상"이라고 적어 두었다. <b>그 전제가 정책과 어긋난다.</b>
		 * {@code RiskOutcomeBatchService}는 판정 트리거를 "회차의 마지막 응시 마감 + 1시간 1분"으로
		 * 잡으면서 <b>"리포트 발행 이벤트가 아니다 — 발행 시각은 운영자가 미룰 수 있어 판정이 운영
		 * 재량에 묶인다"</b>고 명시한다. 두 축은 <b>의도적으로 독립</b>이다.
		 *
		 * <p>그래서 운영자가 발행을 미루면 판정은 이미 끝났는데 이 값만 {@code PENDING}이 됐고,
		 * 화면은 <b>"이 회차는 아직 결과가 없어요"를 그리면서 위험 유형이 붙은 케이스 4건을 함께
		 * 받는</b> 상태가 됐다(32차 R1 실측). 한 응답 안에서 두 필드가 서로 다른 말을 한 것이다.
		 *
		 * <p>{@code outcome_judged_at}이 곧 후보 등재 조건이다({@code ENROLL_CANDIDATE_SQL}이
		 * {@code IS NOT NULL}을 걸고 {@code detected_at}에 그 값을 쓴다). 이 기준으로 판정하면
		 * <b>"items가 있는데 PENDING"이 구조적으로 나올 수 없다.</b>
		 */
		public String resultStatus() {
			return outcomeJudgedAt == null ? "PENDING" : "READY";
		}
	}
}
