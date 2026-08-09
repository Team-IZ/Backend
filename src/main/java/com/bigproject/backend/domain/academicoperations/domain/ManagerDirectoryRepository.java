package com.bigproject.backend.domain.academicoperations.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 담당 매니저의 이름·이메일을 읽는 조회 포트.
 *
 * <p>반 응답의 {@code managers[]}는 예전에 {@code memberId}만 채우고 {@code name}은 항상 빈 문자열이었다 —
 * 이름이 {@code app_user}에 있어 member 도메인 의존이 생긴다는 이유였다. 그런데 반 카드가
 * {@code 이도윤 · lee@…}로 담당자를 그리므로 그 상태로는 화면을 만들 수 없고, 이름만 채워도
 * <b>기관에 동명이인이 있으면 누구인지 가릴 수 없다</b>(9차 R5).
 *
 * <p>그래서 member 도메인의 서비스를 부르는 대신 <b>읽기 전용 조회 포트</b>를 여기에 둔다.
 * 자바 코드 사이의 의존은 생기지 않고, 이 도메인이 필요한 세 값만 읽는다.
 */
public interface ManagerDirectoryRepository {

	/**
	 * 기관 안에서 주어진 사용자 ID들의 이름·이메일을 한 번에 읽는다.
	 *
	 * @return 찾은 것만 담는다. 계정이 지워졌으면 그 항목은 빠진다 — 담당으로 남아 있을 수 없는 사람이라
	 *         빈 칸을 그리는 것보다 목록에서 빼는 편이 화면의 `담당 매니저 없음` 판정과 일치한다
	 */
	List<ManagerProfile> findProfiles(UUID orgId, Collection<UUID> managerUserIds);

	/** {@code name}은 초대만 받고 아직 가입하지 않은 계정에서 null이다. */
	record ManagerProfile(UUID memberId, String name, String email) {
	}
}
