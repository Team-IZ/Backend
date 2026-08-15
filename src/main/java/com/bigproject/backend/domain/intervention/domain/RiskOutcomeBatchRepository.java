package com.bigproject.backend.domain.intervention.domain;

import java.util.List;
import java.util.UUID;

/**
 * 회차 유형 판정과 면담 후보 등재의 영속성 경계다.
 *
 * <p>흐름이 <b>두 큐</b>로 갈린다.
 *
 * <ul>
 *   <li><b>판정</b> {@link #findRoundsToJudge} → {@link #judgeRound} → {@link #enrollCandidates} →
 *       {@link #enrollReasons}. 판정이 원장을 쓰고, 등재가 그 원장을 읽어 면담 후보를 만든다. 판정
 *       없이 등재를 부르면 아무 일도 일어나지 않는다.
 *   <li><b>해소</b> {@link #findRoundsToResolve} → {@link #resolveByRetry} →
 *       {@link #markResolutionEvaluated}. 재시험 결과로 사유를 닫는다.
 * </ul>
 *
 * <p>두 큐를 나눈 이유는 시점이 다르기 때문이다. 판정은 회차의 마지막 응시 마감 직후 한 번에 끝나고,
 * 재시험은 그 뒤에 열린다. 한 큐로 묶으면 판정이 끝난 회차가 목록에서 빠져 해소가 영영 실행되지 않는다.
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
	 * 재시험 결과와 맞춰 봤지만 풀리지 않은 사유에 확인 시각을 남긴다. {@link #resolveByRetry}
	 * <b>뒤에</b> 불러야 한다.
	 *
	 * <p>이 표시가 {@link #findRoundsToResolve}의 워터마크다. 없으면 끝내 풀리지 않는 사유를 가진
	 * 회차가 매 주기 목록에 남아 새 회차를 굶긴다.
	 *
	 * @return 표시한 사유 수
	 */
	int markResolutionEvaluated(UUID assessmentRoundId);

	/**
	 * 판정 대상 회차를 찾는다.
	 *
	 * <p>미니프로젝트이고, 마지막 응시 마감이 유예 시간만큼 지났으며, 판정되지 않은 INITIAL 수행이
	 * 남아 있는 회차만 돌려준다. 정렬은 {@code project.sequence_no, round_no}다 — 회차 간 비교가
	 * 이전 회차 판정 결과에 의존하지는 않지만, 순서대로 돌아야 로그와 장애 대응이 읽힌다.
	 */
	List<UUID> findRoundsToJudge(int limit);

	/**
	 * 해소 대상 회차를 찾는다.
	 *
	 * <p>ACTIVE인 해소 가능 사유가 있고, 그 사유를 마지막으로 확인한 뒤에 끝난 재시험이 있는 회차만
	 * 돌려준다. 오래 기다린 회차가 앞에 온다.
	 */
	List<UUID> findRoundsToResolve(int limit);
}
