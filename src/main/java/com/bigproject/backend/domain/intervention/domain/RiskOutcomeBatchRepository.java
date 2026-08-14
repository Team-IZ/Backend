package com.bigproject.backend.domain.intervention.domain;

import java.util.List;
import java.util.UUID;

/**
 * 회차 유형 판정과 면담 후보 등재의 영속성 경계다.
 *
 * <p>세 단계가 순서를 가진다 — 판정({@link #judgeRound})이 원장을 쓰고, 등재({@link #enrollCandidates}
 * ·{@link #enrollReasons})가 그 원장을 읽어 면담 후보를 만들고, 해소({@link #resolveByRetry})가 재시험
 * 결과로 사유를 닫는다. 판정 없이 등재를 부르면 아무 일도 일어나지 않는다.
 *
 * <p>등재가 두 메서드로 나뉜 이유는 Postgres의 스냅샷 규칙이다. 데이터 수정 CTE가 삽입한 행은 같은
 * 문장의 다른 부분에서 보이지 않으므로, 후보를 넣은 뒤 사유를 넣으려면 문장을 나눠야 한다.
 */
public interface RiskOutcomeBatchRepository {

	/**
	 * 아직 판정되지 않은 회차의 INITIAL 수행을 판정해 {@code measurement_attempt.outcome_*}에 쓴다.
	 *
	 * @return 판정한 수행 수
	 */
	int judgeRound(UUID assessmentRoundId, int policyVersion);

	/** 판정 결과에 위험 사유가 있는 교육생을 면담 후보로 만든다. 이미 있으면 건너뛴다. */
	int enrollCandidates(UUID assessmentRoundId);

	/** 판정 결과의 MATCHED·NOT_APPLICABLE 유형을 후보 사유로 옮겨 적는다. 멱등이다. */
	int enrollReasons(UUID assessmentRoundId);

	/** 재시험에서 게이트를 만든 문제가 전부 2단 이상에 도달한 사유를 RESOLVED로 닫는다. */
	int resolveByRetry(UUID assessmentRoundId);

	/**
	 * 판정 대상 회차를 찾는다.
	 *
	 * <p>미니프로젝트이고, 판정되지 않은 INITIAL 수행이 남아 있는 회차만 돌려준다. 정렬은
	 * {@code project.sequence_no, round_no}다 — 회차 간 비교가 이전 회차 판정 결과에 의존하지는
	 * 않지만, 순서대로 돌아야 로그와 장애 대응이 읽힌다.
	 */
	List<UUID> findRoundsToJudge(int limit);
}
