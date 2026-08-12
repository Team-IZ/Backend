package com.bigproject.backend.domain.submission.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DB CHECK: ck_submission_method — method IN ('GITHUB_URL','ZIP_WITH_GITLOG')
 *
 * <p>{@code enumAsRef}로 내보내는 이유(19차 R2): 이 값 집합이 세 곳에 인라인으로 복사돼 있었고,
 * 인라인 enum은 필드마다 <b>별개의 익명 타입</b>이 된다. 값이 하나 늘 때 한 곳만 고쳐도 생성
 * 타입은 통과하므로 "여기서는 되는데 저기서는 안 되는" 상태가 조용히 만들어진다.
 */
@Schema(name = "SubmissionMethod",
		description = "코드 제출 수단. GITHUB_URL(저장소 주소) · ZIP_WITH_GITLOG(git log 포함 ZIP)",
		enumAsRef = true)
public enum SubmissionMethod {
	GITHUB_URL,
	ZIP_WITH_GITLOG
}
