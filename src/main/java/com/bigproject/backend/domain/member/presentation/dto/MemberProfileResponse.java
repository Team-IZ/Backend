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
		AccountStatus status,

		@Schema(description = """
				소속 기관의 이메일 도메인. 명단·매니저 초대가 **기관 도메인 밖 주소를 막는 데** 쓰는 값입니다(9차 Q3-④).

				같은 값이 `GET /organizations/{organizationId}`에도 있지만 그 API는 슈퍼어드민 전용이라
				오퍼레이터가 부르면 **403**입니다. 그래서 여기에 함께 내려줍니다 —
				프론트 상수로 두면 기관을 하나 더 만드는 순간 틀립니다.

				**`null`인 경우가 정상입니다**: 기관에 도메인이 설정되지 않았거나(컬럼 자체가 nullable),
				호출자가 소속 기관이 없는 SUPER_ADMIN인 경우입니다.
				`null`이면 도메인 제한을 걸지 않습니다 — 빈 문자열과 구분해야 하므로 `""`로 내려주지 않습니다.""",
				example = "example.com", nullable = true)
		String emailDomain
) {
}
