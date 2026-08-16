package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.domain.ReviewModels.ExistingReview;
import com.bigproject.backend.domain.assessment.domain.ReviewModels.ReviewSource;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionException;
import com.bigproject.backend.domain.assessment.domain.SessionModels.SessionHead;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionRepository;
import com.bigproject.backend.domain.assessment.infrastructure.JdbcSessionReviewRepository;
import com.bigproject.backend.domain.assessment.presentation.dto.SessionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 다시 보기(REVIEW) 응시를 리포트에서 파생시킨다.
 *
 * <h2>왜 새 경로가 필요했나</h2>
 *
 * <p>다시 보기를 <b>푸는</b> 경로는 이미 있었다 — 세션 다섯 경로가 {@code mode=REVIEW}를 그대로
 * 지원하고 {@code GET /current}는 다시 보기를 1차보다 먼저 고른다. 없던 것은 <b>만드는</b> 쪽이다.
 * {@code measurement_attempt}를 INSERT하는 두 자리가 모두 {@code attempt_type='INITIAL'} 고정이라,
 * DB에 직접 넣지 않는 한 다시 보기는 생길 수 없었다.
 *
 * <h2>리포트에서 파생시키는 것은 선택이 아니다</h2>
 *
 * <p>{@code ck_measurement_attempt_attempt_type_2}가 REVIEW에 {@code review_source_report_id}·
 * {@code review_source_report_snapshot_id}·{@code review_due_at}·{@code source_attempt_id}·
 * {@code assigned_at}·{@code assigned_by}를 <b>전부</b> NOT NULL로 요구한다. 스키마가 "다시 보기는
 * 리포트에서 나온다"를 이미 못박아 둔 것이고, 이 서비스는 그 요구를 그대로 따른다.
 *
 * <h2>만든 뒤에는 기존 흐름에 얹힌다</h2>
 *
 * <p>커서를 세우지 않고 세션을 {@code READY}로 둔다. 그다음은 {@code POST /{sessionId}/start}가
 * 1차와 똑같이 처리한다 — 인트로 동의를 남기고 첫 문제 L1에 커서를 세우며 문제별 20분 시계를 건다.
 * 그래서 이 경로가 하는 일은 <b>행을 만드는 것</b>뿐이고 진행 규칙은 한 벌로 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentReviewService {

	/**
	 * 다시 볼 기준선. <b>2단(설계 논리)이 합격선</b>이라 그 미만만 다시 본다.
	 *
	 * <p>리포트 쪽 {@code TraineeReportServiceImpl.RETRY_TARGET_BELOW_LEVEL}과 <b>같은 값</b>이다.
	 * 화면이 "다시 볼 개념"이라 표시한 것과 서버가 실제로 깔아 주는 문제가 어긋나면, 학생은 리포트에서
	 * 본 개념과 다른 문제를 풀게 된다. 값을 옮길 일이 생기면 두 곳을 함께 옮긴다.
	 */
	static final int REVIEW_TARGET_BELOW_LEVEL = 2;

	private final JdbcSessionReviewRepository reviewRepository;
	private final JdbcSessionRepository sessionRepository;

	/** 다시 보기 마감까지의 기간(일). {@code review_due_at}은 DDL상 NOT NULL이라 기본값이 반드시 있어야 한다. */
	@Value("${session.review-window-days:7}")
	private int reviewWindowDays;

	/**
	 * 리포트를 근거로 다시 보기를 연다. <b>이미 있으면 그것을 그대로 돌려준다.</b>
	 *
	 * <p>멱등하게 만든 이유는 화면의 `다시 보기 시작`이 새로고침 뒤 다시 눌릴 수 있기 때문이다.
	 * 두 번 눌러 응시가 둘 생기면 도달 단계 비교가 어느 쪽을 봐야 하는지 알 수 없게 된다.
	 *
	 * <p>응답은 {@code GET /current}와 <b>같은 구조</b>다. 화면은 받은 {@code sessionId}로 곧바로
	 * {@code POST /{sessionId}/start}를 부르면 되고, 새 경로를 배울 필요가 없다.
	 */
	@Transactional
	public SessionResponse openReview(UUID userId, UUID reportId) {
		ReviewSource source = reviewRepository.findReviewSource(reportId, userId)
				.orElseThrow(() -> new SessionException(SessionErrorCode.REVIEW_REPORT_NOT_ACCESSIBLE));

		// 이미 있으면 새로 만들지 않는다. 끝난 것은 되살리지 않는다 — 회차당 한 번이다.
		ExistingReview existing = reviewRepository
				.findExistingReview(source.assessmentRoundId(), userId).orElse(null);
		if (existing != null) {
			if (existing.isFinished()) {
				throw new SessionException(SessionErrorCode.REVIEW_ALREADY_COMPLETED);
			}
			if (existing.isResumable()) {
				return sessionResponse(existing.sessionId(), userId);
			}
			if (existing.hasSession()) {
				// 세션은 있는데 살아 있지 않다. 응시 상태가 따라오지 않았을 뿐 다시 보기는 이미 끝난
				// 것이므로 여기서 끊는다 — 세션을 하나 더 넣으면 응시당 1건 제약을 깬다.
				log.warn("끝난 세션이 붙은 다시 보기 응시: attemptId={}, sessionStatus={}",
						existing.attemptId(), existing.sessionStatus());
				throw new SessionException(SessionErrorCode.REVIEW_ALREADY_COMPLETED);
			}
			// 응시는 있는데 세션이 없다. 종전 시도가 중간에 끊긴 흔적이므로 세션만 채워 이어 간다.
			log.warn("세션 없는 다시 보기 응시를 발견해 세션을 채운다: attemptId={}", existing.attemptId());
			if (source.sourceSessionId() == null) {
				// 채울 세션의 질문을 1차에서 가져오는데 그 1차 세션이 없다. 복사가 0건이 되어 어차피
				// 되돌아가므로, 빈 세션을 만들었다 지우기 전에 여기서 끊는다.
				throw new SessionException(SessionErrorCode.REVIEW_SOURCE_NOT_READY);
			}
			return buildSession(existing.attemptId(), source, userId);
		}

		if (!source.isSourceCompleted() || source.sourceSessionId() == null) {
			throw new SessionException(SessionErrorCode.REVIEW_SOURCE_NOT_READY);
		}
		if (reviewRepository.countReviewTargets(source.sourceSessionId(), REVIEW_TARGET_BELOW_LEVEL) == 0) {
			throw new SessionException(SessionErrorCode.REVIEW_NOT_ELIGIBLE);
		}

		UUID attemptId = reviewRepository.insertReviewAttempt(source.sourceAttemptId(), userId,
				source.reportId(), source.snapshotId(),
				Instant.now().plus(Duration.ofDays(reviewWindowDays)));
		return buildSession(attemptId, source, userId);
	}

	/**
	 * 세션을 열고 1차의 질문을 복사한다.
	 *
	 * <p>복사가 0건이면 <b>물어볼 것이 없는 세션</b>이 남는다. 대상 수를 미리 세어 막지만, 그 사이
	 * 원본 단계가 사라지는 경우까지 통과시키면 화면은 전체화면으로 들어간 뒤 시작에서 막힌다 —
	 * 여기서 끊어 트랜잭션을 통째로 되돌린다.
	 */
	private SessionResponse buildSession(UUID attemptId, ReviewSource source, UUID userId) {
		UUID sessionId = reviewRepository.insertReviewSession(attemptId);
		int copied = reviewRepository.copyStagesForReview(sessionId, source.sourceSessionId(),
				REVIEW_TARGET_BELOW_LEVEL);
		if (copied == 0) {
			throw new SessionException(SessionErrorCode.REVIEW_NOT_ELIGIBLE);
		}
		log.info("다시 보기 개설: attemptId={}, sessionId={}, 복사한 단계={}", attemptId, sessionId, copied);
		return sessionResponse(sessionId, userId);
	}

	/** 만든 직후의 세션을 {@code GET /current}와 같은 모양으로 읽어 돌려준다. */
	private SessionResponse sessionResponse(UUID sessionId, UUID userId) {
		SessionHead head = sessionRepository.findOwned(sessionId, userId)
				.orElseThrow(() -> new SessionException(SessionErrorCode.SESSION_NOT_ACCESSIBLE));
		return SessionResponse.of(head, sessionRepository.findStages(sessionId));
	}
}
