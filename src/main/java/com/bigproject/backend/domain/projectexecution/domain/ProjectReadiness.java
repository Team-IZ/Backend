package com.bigproject.backend.domain.projectexecution.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회차의 <b>준비 상태</b>. 9차 Q1에 대한 답으로 ⓐ(서버가 판정)를 택하면서 만든 값이다.
 *
 * <p><b>왜 {@code ProjectStatus}에 값을 더하지 않았는가.</b> 화면은 {@code PREP · READY · RUNNING · DONE}
 * 네 값을 한 축으로 쓰지만, 그 축은 성격이 다른 둘을 겹쳐 놓은 것이다 —
 * {@code RUNNING}·{@code DONE}은 <b>시간</b>이 정하고({@code ProjectStatus}), {@code PREP}·{@code READY}는
 * <b>구성이 찼는지</b>가 정한다. {@code ProjectStatus}에 두 값을 더하면 한 필드가 두 질문에 답하게 되고,
 * "진행 중인데 교안이 비어 있다"처럼 두 축이 어긋나는 상태를 표현할 수 없어진다.
 *
 * <p>그래서 축을 나눠 내려준다. 화면이 쓰는 네 값은 두 필드를 겹쳐 만들면 된다 —
 * {@code status === 'PLANNED' ? readiness : status} 한 줄이다.
 *
 * <p>판정 규칙은 {@link ProjectListSort#READINESS} 정렬과 <b>같은 자리</b>에 있다
 * ({@code ProjectServiceImpl.unreadyScore}). 정렬과 표시가 다른 규칙을 쓰면 목록의 순서와 배지가
 * 서로 다른 말을 하게 된다.
 */
@Schema(
		name = "ProjectReadiness",
		description = """
				회차 준비 상태. **`ProjectStatus`(시간 축)와 별개인 구성 축**이다.
				`PREP`(준비 중 — 교안·검증개념·마감 중 빈 것이 있다) · `READY`(준비됨 — 셋 다 찼다).

				화면의 4값(`준비 중`·`준비됨`·`진행 중`·`종료`)은 두 필드를 겹쳐 만든다:
				`status === 'PLANNED' ? readiness : status`.

				`RUNNING`·`CLOSED` 회차에도 이 값은 계산되어 온다 — 개강 후 교안이 비어 있는 것은
				실제로 일어나는 상태라 감추지 않는다.""",
		enumAsRef = true
)
public enum ProjectReadiness {

	/** 교안·확정 개념·마감일 중 하나라도 비어 있다. 화면의 `준비 중`. */
	PREP,

	/** 셋 다 찼다. 화면의 `준비됨`. */
	READY
}
