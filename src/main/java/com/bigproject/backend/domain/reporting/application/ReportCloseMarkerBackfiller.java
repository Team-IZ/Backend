package com.bigproject.backend.domain.reporting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 종료 표식을 놓친 문제에 사후로 찍는다. <b>안전망 전용</b>이다.
 *
 * <p>정상 경로는 Assessment의 {@code JdbcSessionRepository.closeProblem}이 문제를 접을 때
 * {@code problem_closed_at}을 찍는 것이고, 리포트 대상 선별은 그 표식만 본다. 그 호출이 빠진
 * 경로가 생기면 <b>그 문제는 영영 리포트를 받지 못한다</b> — 조용히 끊긴다. 이 배치는 그 구멍을
 * 하루 한 번 훑어 메운다.
 *
 * <h2>🔴 Reporting이 {@code problem_stage}에 쓰는 유일한 자리다</h2>
 *
 * <p>도메인 경계 합의는 <b>Assessment가 "끝났다"는 사실을 쓰고 Reporting은 읽기만 한다</b>이다
 * (2026-08-16 합의 §1). 여기가 그 유일한 예외라서 조건을 좁게 잡는다 — 세션이 끝났고, 그 문제의
 * 전 축이 터미널이고, 전환 컷오프 이후에 시작된 세션만 찍는다.
 *
 * <h2>🔴 컷오프가 없으면 막아 둔 것이 여기서 열린다</h2>
 *
 * <p>전환 이전 세션 3,271건(LLM 호출 9,795건)의 대부분이 <b>"전 축 터미널 + {@code closed_at}
 * NULL"</b>이다. 정상 종료된 옛 세션이 정확히 그 모양이기 때문이다. 컷오프를 빼면 이 백필이
 * 그 전부에 표식을 찍어 <b>대상으로 만들어 버린다</b> — 대상 조회에서 막은 것을 안전망이
 * 우회하는 꼴이고, "과거 데이터는 그대로 둔다"는 합의(§7)가 깨진다.
 *
 * <p>Assessment가 자기 회신의 이 결함을 스스로 찾아냈다(최종합의 §3). 조건 한 줄이지만
 * 빠지면 이 전환에서 막아 둔 것이 통째로 열리는 자리다.
 *
 * <h2>{@code now()}가 아니라 {@code s.ended_at}으로 채운다</h2>
 *
 * <p>대상 조회가 {@code ORDER BY ps.problem_closed_at}이라 <b>이 값이 곧 처리 순서</b>다.
 * {@code now()}로 찍으면 뒤늦게 발견된 누락이 <b>신규 응시보다 앞줄에 선다.</b> 실제 종료 시각을
 * 넣어야 밀린 것이 오래된 순으로 나간다.
 *
 * <h2>0이 아니면 그 자체가 신호다</h2>
 *
 * <p>정상 경로가 다 돌면 이 배치는 <b>항상 0행</b>이어야 한다. 0이 아니라는 것은 Assessment의
 * 종료 처리가 놓친 경로가 있다는 뜻이므로 호출부가 {@code log.warn}으로 남긴다 —
 * {@code ReportBatchService.warnAboutUnfinishedStages()}와 짝이다.
 */
@Component
public class ReportCloseMarkerBackfiller {

	private static final Logger log = LoggerFactory.getLogger(ReportCloseMarkerBackfiller.class);

	/**
	 * 표식이 없는 문제를 세션 단위로 몇 개까지 메울지.
	 *
	 * <p>LLM 호출이 없는 UPDATE라 디스패치 상한보다 크게 잡아도 된다. 상한을 두는 이유는 비용이
	 * 아니라 <b>한 트랜잭션이 잠그는 행 수</b>다 — 한 세션이 문제 3개 × 축 4개라 세션 200건이면
	 * 2,400행 안팎이다.
	 */
	private final int backfillLimit;

	/** {@code ReportDispatchRepository} 클래스 javadoc의 "전환 컷오프" 절 참고. */
	private final Instant transitionCutoffAt;

	private final JdbcTemplate jdbc;

	public ReportCloseMarkerBackfiller(
			JdbcTemplate jdbc,
			@Value("${ai.report.backfill-limit}") int backfillLimit,
			@Value("${ai.report.transition-cutoff-at}") Instant transitionCutoffAt) {
		this.jdbc = jdbc;
		this.backfillLimit = backfillLimit;
		this.transitionCutoffAt = transitionCutoffAt;
	}

	/**
	 * 표식이 빠진 문제에 종료 시각을 찍는다.
	 *
	 * <p>{@code problem_closed_at}과 {@code problem_close_reason_code}를 <b>함께</b> 채운다 —
	 * {@code ck_problem_stage_close_reason}이 둘을 짝으로만 허용한다.
	 *
	 * <p>{@code problem_closed_at IS NULL} 조건이 멱등을 만든다. 두 번 돌려도 이미 찍힌 행은
	 * 건드리지 않으므로 첫 종료 시각이 유지된다.
	 *
	 * @return 표식을 찍은 stage 행 수. <b>0이 정상이다</b>
	 */
	@Transactional
	public int backfill() {
		OffsetDateTime cutoff = OffsetDateTime.ofInstant(transitionCutoffAt, ZoneOffset.UTC);

		int stamped = jdbc.update("""
				UPDATE problem_stage ps
				   SET problem_closed_at         = s.ended_at,
				       problem_close_reason_code = 'SESSION_ENDED',
				       updated_at                = now()
				  FROM assessment_session s
				 WHERE s.session_id = ps.session_id
				   AND ps.problem_closed_at IS NULL
				   AND s.ended_at IS NOT NULL
				   AND s.started_at >= ?
				   AND NOT EXISTS (
				       SELECT 1 FROM problem_stage x
				        WHERE x.session_id = ps.session_id
				          AND x.problem_id = ps.problem_id
				          AND x.status IN ('PREPARED', 'IN_PROGRESS')
				   )
				   AND ps.session_id IN (
				       SELECT s2.session_id
				         FROM assessment_session s2
				        WHERE s2.started_at >= ?
				          AND s2.ended_at IS NOT NULL
				          AND EXISTS (
				              SELECT 1 FROM problem_stage y
				               WHERE y.session_id = s2.session_id
				                 AND y.problem_closed_at IS NULL
				          )
				        ORDER BY s2.ended_at
				        LIMIT ?
				   )
				""", cutoff, cutoff, backfillLimit);

		if (stamped > 0) {
			log.warn("종료 표식을 사후로 찍었다: stages={} — Assessment 의 문제 종료 처리가 놓친 "
					+ "경로가 있다는 뜻이다. 정상이면 이 값은 0 이어야 한다", stamped);
		}
		return stamped;
	}
}
