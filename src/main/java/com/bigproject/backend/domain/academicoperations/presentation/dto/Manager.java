package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 담당 매니저 요약. {@code ClassroomResponse.managers[]}와 {@code CohortResponse.managers[]}가
 * <b>같은 이 정의</b>를 가리킨다.
 *
 * <p>예전에는 두 응답이 각자 {@code Manager}라는 이름의 중첩 record를 들고 있었다. springdoc은 스키마를
 * <b>단순 클래스 이름</b>으로 키잉하므로 두 정의가 {@code Manager} 키 하나를 놓고 충돌했고, 먼저 등록된
 * 쪽(기수 응답의 {@code memberId: integer})이 이겨서 <b>반 응답이 실제로 내려보내는 UUID와 스펙이
 * 서로 다른 말을 하고 있었다.</b> 9차 R5가 지적한 그것이다.
 *
 * <p>{@code memberId}는 {@code app_user.user_id}(UUID)다. 매니저 ID가 나오는 세 자리
 * — {@code ManagerRosterEntry.managerId} · {@code UpdateClassroomManagersRequest.managerIds[]} ·
 * 여기 — 가 전부 같은 값·같은 타입이어야 "읽고 → 고쳐서 → 다시 보내는" 왕복이 성립한다.
 */
@Schema(name = "Manager", description = "담당 매니저 한 명")
public record Manager(
		@Schema(description = """
				매니저의 회원 ID(app_user.user_id). `ManagerRosterEntry.managerId`,
				`UpdateClassroomManagersRequest.managerIds[]`와 **같은 값·같은 타입**이라
				목록에서 읽은 담당자를 그대로 다시 보낼 수 있다.""")
		UUID memberId,

		// 초대만 받고 아직 가입하지 않은 매니저는 app_user.name이 비어 있다(이름은 수락할 때 본인이 넣는다).
		// 그 사실이 타입에 없으면 화면이 `name.trim()` 한 줄에서 런타임에 죽는다(8차 R2와 같은 종류).
		@Schema(description = "이름. 초대만 되고 아직 가입하지 않았으면 null(화면에서는 `가입 대기`)", nullable = true)
		String name,

		@Schema(description = "이메일. 반 카드가 `이도윤 · lee@…`로 담당자를 표시하는데, 이름만으로는 동명이인을 가를 수 없다",
				example = "lee@example.com")
		String email
) {
}
