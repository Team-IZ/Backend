package com.bigproject.backend.domain.auth.domain;

import com.bigproject.backend.domain.member.domain.Role;

import java.util.UUID;

/**
 * @param role 초대 <b>대상자</b>의 역할이다. 가입 화면이 동의 항목 집합과 후속 활성화 API를
 *             고르는 근거이며, 활성화 검증이 쓰는 {@code app_user}의 실제 역할과 같은 값이다.
 */
public record InvitationRecipient(
		UUID userId,
		String email,
		Role role
) {
}
