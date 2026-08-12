package com.bigproject.backend.domain.submission.presentation.dto;

/**
 * 화면이 보여줄 분석 단계. {@code analysis_job.status} 5종에 {@link #NOT_STARTED} 하나를 더한 것이다.
 *
 * <p>{@code analysis_job} 행이 아예 없는 상태를 별도 값으로 두지 않으면 "분석 대기 중"과 "분석 정보 없음"이
 * 같은 응답으로 보인다. 분석 실행이 마감 후 배치라서 마감 전에는 이 값이 정상 상태다.
 */
public enum SubmissionAnalysisPhase {
	NOT_STARTED,
	QUEUED,
	RUNNING,
	SUCCEEDED,
	PARTIAL,
	FAILED
}
