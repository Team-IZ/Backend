package com.bigproject.backend.domain.assessment.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 기관이 허용한 제출 수단. {@code organization_policy}의 ACTIVE 최신 버전에서 읽는다.
 *
 * <p>View의 {@code available_submission_methods}를 쓰지 않는 이유는 두 가지다.
 * 선언은 {@code TEXT[]}인데 SELECT 절이 {@code ::TEXT}로 캐스팅해 배열이 문자열로 나오고(V-12),
 * {@code allow_github_integration}을 무시한 채 {@code GITHUB_URL}을 항상 포함한다(V-13).
 * 두 결함이 교정되면 이 클래스를 걷어내고 View 컬럼으로 되돌린다.
 */
public record SubmissionMethodPolicy(
		boolean allowGithubIntegration,
		boolean allowZipSubmission
) {
	/** 정책 행이 없는 기관의 기본값. GitHub 제출만 허용한다. */
	public static SubmissionMethodPolicy defaults() {
		return new SubmissionMethodPolicy(true, false);
	}

	public List<String> toMethods() {
		List<String> methods = new ArrayList<>(2);
		if (allowGithubIntegration) {
			methods.add("GITHUB_URL");
		}
		if (allowZipSubmission) {
			methods.add("ZIP_WITH_GITLOG");
		}
		return List.copyOf(methods);
	}
}
