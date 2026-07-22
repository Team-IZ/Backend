package com.bigproject.backend.global.security;

import java.util.UUID;

/**
 * TODO(auth): auth/member 도메인이 완성되면 제거한다.
 * 현재는 SecurityContext에서 인증된 사용자의 UUID(app_user.user_id)를 꺼낼 방법이 없어(JWT에 사용자 UUID claim이 없고
 * app_user 엔티티도 아직 없음), organization/operations 도메인의 감사 컬럼(created_by, configured_by 등)에
 * 임시로 채워 넣는 고정 UUID다. organization.created_by와 organization_policy.configured_by는 app_user(user_id)를
 * 참조하는 FK이므로, 이 값으로 기관을 생성/수정하려면 같은 UUID를 가진 app_user 행이 미리 존재해야 한다(그렇지 않으면
 * FK 위반으로 실패한다).
 */
public final class SystemRequester {

	public static final UUID SYSTEM_REQUESTER_ID = new UUID(0L, 0L);

	private SystemRequester() {
	}
}
