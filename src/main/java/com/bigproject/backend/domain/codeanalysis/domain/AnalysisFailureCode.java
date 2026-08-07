package com.bigproject.backend.domain.codeanalysis.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * DB CHECK: {@code ck_analysis_job_failure_code_2} — 15종.
 *
 * <p>세 계열이 한 컬럼에 모여 있다. 저장소 접근 주체가 AI 서버로 확정되면서 저장소 실패를 별도 필드로
 * 받지 않기로 했고(S-03), ZIP 검증 실패도 같은 이유로 합쳤다(S-15).
 *
 * <p>문자열 대신 enum으로 받는 이유는 <b>실패를 빨리 드러내기 위해서다.</b> AI가 값 집합 밖의 코드를
 * 보내면 문자열은 INSERT 시점의 CHECK 위반으로 터지는데, 그때는 이미 트랜잭션 한복판이라 원인이
 * 스택트레이스에 묻힌다. {@link #parse}는 응답을 읽는 자리에서 바로 걸러낸다.
 */
public enum AnalysisFailureCode {

	// ── 분석 실행 실패 ──
	SOURCE_UNREACHABLE,
	UNSUPPORTED_LANGUAGE,
	ANALYSIS_TIMEOUT,
	MODEL_ERROR,
	TEMPORARY_ERROR,

	// ── 저장소 접근 실패. repository_verification.failure_code 와 문자열이 같다 ──
	INVALID_REPOSITORY_URL,
	REPO_NOT_FOUND,
	REPOSITORY_ACCESS_DENIED,
	BRANCH_NOT_FOUND,
	UNSUPPORTED_HOST,

	// ── ZIP 검증 실패. submission_artifact.validation_failure_code 와 문자열이 같다 ──
	FILE_TOO_LARGE,
	ARCHIVE_INVALID,
	/**
	 * ZIP에 분석할 코드가 없다는 뜻이다. <b>분석이 근거를 못 찾은 경우에도 이 값을 쓴다.</b>
	 * 종전에 그 용도로 있던 {@code EMPTY_CODE_EVIDENCE}는 이름이 한 단어 차이라 바꿔 써도 CHECK가
	 * 둘 다 통과시켜 조용히 틀린 사유가 쌓이므로 2026-08-07에 값 집합에서 뺐다. 개념별로 근거를
	 * 찾지 못한 것은 {@code assessment_problem}의 NOT_GENERATED 슬롯이 따로 기록한다.
	 */
	EMPTY_CODE,
	PROHIBITED_FILE,
	GIT_LOG_MISSING;

	/** 값 집합 밖이면 비어 있는 결과를 준다. 호출부가 "모르는 코드"를 어떻게 다룰지 정하게 한다. */
	public static Optional<AnalysisFailureCode> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String trimmed = raw.trim();
		return Arrays.stream(values()).filter(code -> code.name().equals(trimmed)).findFirst();
	}
}
