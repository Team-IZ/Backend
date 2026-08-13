package com.bigproject.backend.domain.submission.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DB CHECK: ck_submission_status — status IN ('VALIDATING','ACCEPTED','FETCH_FAILED','INVALID')
 *
 * <p>{@code FETCH_FAILED}는 이 도메인이 직접 쓰지 않는다. 저장소 접근 실패는 분석 단계의 사건으로
 * {@code analysis_job.failure_code}에 기록하고 제출 상태를 내리지 않기로 확정했다(2026-08-06).
 */
@Schema(name = "SubmissionStatus",
		description = "제출 접수 상태. VALIDATING(검증 중) · ACCEPTED(접수됨) · FETCH_FAILED(가져오기 실패) · INVALID(무효)",
		enumAsRef = true)
public enum SubmissionStatus {
	VALIDATING,
	ACCEPTED,
	FETCH_FAILED,
	INVALID
}
