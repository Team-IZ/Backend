package com.bigproject.backend.domain.member.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 커밋 이메일 검증 상태.
 *
 * <p><b>{@code null}이 "미등록"이다</b> — 값으로 표현하지 않는다. 등록하지 않은 것과 등록했으나
 * 검증되지 않은 것({@link #UNVERIFIED})은 다른 사건이고, 화면 배너 문구도 다르다.
 *
 * <p>배너 노출 조건은 {@code commitEmailStatus !== "VERIFIED"}라 미등록·미검증이 함께 걸린다.
 * 다만 <b>배너 강도는 {@code projectCategory}로 가른다</b> — 미니프로젝트는 팀 코드 전체로
 * 출제하므로 커밋 귀속이 필요 없지만, 빅프로젝트는 개인 커밋에서 출제해 귀속이 비면 문제가
 * 아예 생성되지 않는다.
 */
@Schema(name = "CommitEmailStatus",
		description = """
				커밋 이메일 검증 상태. `PENDING`(검증 대기) · `VERIFIED`(검증됨) · `UNVERIFIED`(검증 실패·해제).

				⚠️ **`null`은 미등록**을 뜻하며 값으로 표현하지 않는다.""",
		enumAsRef = true)
public enum CommitEmailStatus {

	/** 등록했고 검증을 기다린다. */
	PENDING,

	/** 검증됐다. 배너를 띄우지 않는 유일한 값이다. */
	VERIFIED,

	/** 검증되지 않았다. 미등록({@code null})과 구분된다. */
	UNVERIFIED
}
