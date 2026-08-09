package com.bigproject.backend.domain.academicoperations.domain;

import java.util.UUID;

/**
 * 반을 지워도 되는지 판정하기 위한 조회 포트(9차 R6).
 *
 * <p>화면도 {@code rules.ts}의 {@code canEditClasses}로 개강 전에만 삭제를 열지만,
 * <b>클라이언트 검증만 있으면 우회된다.</b> 그때 끊기는 것은 학생이 실제로 한 팀 편성과 발행된 리포트라
 * 서버가 최종 판정을 한다 — R4(회차 삭제)와 같은 이유다.
 *
 * <p>"개강했는가"를 기수 상태로 묻지 않고 <b>반에 실제로 붙은 것</b>으로 묻는다. 상태는 운영자가 손으로
 * 바꾸는 값이라 개강 전으로 되돌려 두고 지우면 데이터가 끊기지만, 팀·리포트는 되돌릴 수 없는 사실이다.
 */
public interface ClassroomDependencyRepository {

	/** 이 반에 편성된 팀이 하나라도 있는지. 팀이 붙었다는 것은 회차가 돌기 시작했다는 뜻이다. */
	boolean hasTeams(UUID classId);

	/** 이 반을 대상으로 만들어진 리포트가 있는지. */
	boolean hasReports(UUID classId);
}
