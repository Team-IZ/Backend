package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.AccountStatus;
import com.bigproject.backend.domain.member.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = """
		지금 이 토큰의 주인. 로그인 응답과 같은 값들이며 <b>서버가 매번 다시 읽어</b> 내려줍니다.

		새로고침하면 브라우저 메모리가 비워지는데 재발급 응답(`RefreshTokenResponse`)에는 토큰만 있어
		역할을 알 수 없습니다. 이 API가 그 자리를 메우므로 역할·상태를 브라우저 저장소에 남길 필요가
		없고, 정지·역할 변경이 다음 호출에 바로 반영됩니다.""")
public record MemberProfileResponse(
		@Schema(description = "계정 식별자. 로그인 응답의 memberId와 같은 값", type = "string", example = "UUID")
		UUID memberId,
		@Schema(description = "로그인 이메일", example = "manager@example.com") String email,
		@Schema(description = "표시 이름", example = "홍길동") String name,
		Role role,
		@Schema(description = "소속 기관. SUPER_ADMIN은 null", type = "string", example = "UUID", nullable = true)
		UUID organizationId,
		AccountStatus status
) {
}
