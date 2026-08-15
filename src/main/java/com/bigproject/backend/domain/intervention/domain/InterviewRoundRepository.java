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
	 */
	List<RoundOption> findRoundOptions(UUID managerUserId, UUID orgId);

	/** 선택한 회차의 표시용 메타. 담당 밖 회차면 비어 있다. */
	Optional<RoundMeta> findRoundMeta(UUID managerUserId, UUID orgId, UUID assessmentRoundId);

	/**
	 * @param label 화면 드롭다운 문구. 회차 번호가 <b>프로젝트 안에서만 유일</b>하므로
	 *              프로젝트명을 함께 붙여야 서로 다른 회차가 구분된다
	 */
	record RoundOption(
			UUID assessmentRoundId,
			UUID projectId,
			String projectName,
			int roundNo,
			String roundName,
			String label,
			String status) {
	}

	/**
	 * @param publishedAt   회차 리포트 발행 시각. <b>위험 판정 등재 시점</b>이기도 하다
	 *                      (정의서 §3 "리포트 일괄 발행 → 위험 판정 등재, 전원 동일")
	 * @param firstRound    1차인가. 비교할 직전 회차가 없어 위험 유형이 붙지 않는다(9-5)
	 */
	record RoundMeta(
			UUID assessmentRoundId,
			int roundNo,
			String label,
			boolean firstRound,
			Instant publishedAt) {

		/**
		 * 결과가 나왔는가. 리포트 발행이 곧 "이 회차 채점이 끝났다"는 신호다 —
		 * 발행 전에는 위험 판정 자체가 없어 목록이 비는 것이 정상이다.
		 */
		public String resultStatus() {
			return publishedAt == null ? "PENDING" : "READY";
		}
	}
}
