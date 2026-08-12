package com.bigproject.backend.domain.submission.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * TR-02 `제출` 화면의 단일 조회. 프로젝트 하나에 대한 <b>내 팀의 현재 제출</b> 상태를 한 행으로 읽는다.
 *
 * <p>집계 조회라 JPA가 아니라 네이티브 SQL이다 — 회차·팀·제출·분석·세션이 다섯 테이블에 흩어져 있고
 * 그중 셋은 LEFT JOIN이라 엔티티 그래프로 표현하면 읽기 전용 매핑만 늘어난다.
 */
public interface MySubmissionQueryRepository {

	/**
	 * @param projectId 프로젝트 식별자. <b>회차 ID가 아니다</b> — 화면이 홈에서 `projectId`를 들고 넘어온다
	 * @param userId    호출자. 제출은 팀 단위지만 <b>어느 팀인지는 사람으로 정해진다</b>
	 * @return 호출자가 그 프로젝트의 유효 팀 구성원이 아니거나 활성 회차가 없으면 비어 있다
	 */
	Optional<MySubmissionRow> findMySubmission(UUID projectId, UUID userId);

	/**
	 * 한 행. <b>상태 판정은 여기서 하지 않는다</b> — 원시 값만 담고 6종 판정은 서비스가 한 곳에서 한다.
	 *
	 * @param sessionStarted 이 교육생이 세션을 시작했는가. {@code READY}와 {@code LOCKED}를 가르는 유일한 축이다
	 * @param analysisFailureCode {@code analysis_job.failure_code}. 15종이며 사용자 문구로 옮겨 내보낸다
	 * @param verifyClosesAt 개인 응시 창 종료({@code measurement_attempt.assessment_close_at})
	 */
	record MySubmissionRow(
			UUID assessmentRoundId,
			String roundName,
			String roundStatus,
			Instant submissionDueAt,

			UUID submissionId,
			String submissionMethod,
			String submissionStatus,
			Instant submittedAt,
			String submissionFailureReason,

			String repoUrl,
			String requestedBranch,
			String resolvedBranch,
			String defaultBranch,
			String commitSha,
			String commitMessage,
			Instant commitCommittedAt,

			String analysisJobStatus,
			String analysisFailureCode,
			Instant analyzedAt,

			boolean sessionStarted,
			Instant verifyClosesAt
	) {
	}
}
