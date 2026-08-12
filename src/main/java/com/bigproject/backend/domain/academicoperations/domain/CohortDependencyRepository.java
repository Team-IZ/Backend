package com.bigproject.backend.domain.academicoperations.domain;

import java.util.UUID;

/**
 * 기수를 지워도 되는지 판정하기 위한 조회 포트(11차 Q2).
 *
 * <p>반 삭제({@link ClassroomDependencyRepository})와 같은 방식이다 — 화면도 개강 전에만 삭제를 열지만
 * <b>클라이언트 검증만 있으면 우회된다.</b> 잘못 만든 기수를 되돌리는 것과, 이미 사람이 들어간 기수를
 * 통째로 날리는 것은 전혀 다른 일이라 서버가 최종 판정을 한다.
 *
 * <p>상태({@code PLANNED})만 보고 열어 주지 않는 이유도 같다 — 상태는 운영자가 손으로 바꾸는 값이라
 * 개강 전으로 되돌려 두고 지울 수 있다. 명단·반·회차는 되돌릴 수 없는 사실이다.
 */
public interface CohortDependencyRepository {

	/** 이 기수에 등록 또는 초대된 교육생이 하나라도 있는지(이탈·취소 이력도 사실이다). */
	boolean hasMembers(UUID cohortId);

	/** 이 기수에 만들어진 반이 하나라도 있는지. 반이 남으면 지운 기수를 가리키는 행이 생긴다. */
	boolean hasClassrooms(UUID cohortId);

	/** 이 기수에 만들어진 회차가 하나라도 있는지. */
	boolean hasProjects(UUID cohortId);
}
