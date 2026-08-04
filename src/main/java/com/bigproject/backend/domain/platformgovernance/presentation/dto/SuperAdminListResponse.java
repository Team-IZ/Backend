package com.bigproject.backend.domain.platformgovernance.presentation.dto;

import com.bigproject.backend.domain.organization.domain.OperatorAccountStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 슈퍼어드민 계정 목록. 목업 SA-03 ② `슈퍼어드민 계정` 탭 — "목록 · 초대 · 정지".
 *
 * <p>슈퍼어드민은 기관에 속하지 않으므로({@code app_user.org_id IS NULL}) 기관 컬럼이 없다.
 */
@Schema(description = "슈퍼어드민 계정 목록 (SA-03 ② 슈퍼어드민 계정 탭)")
public record SuperAdminListResponse(
		List<SuperAdmin> content,

		@Schema(description = """
				활성 슈퍼어드민 수. 1이면 그 계정은 정지할 수 없다 —
				정지하면 플랫폼에 들어갈 사람이 아무도 없어지고, 풀어 줄 상위 권한이 존재하지 않는다.""")
		int activeCount
) {

	@Schema(description = "슈퍼어드민 계정")
	public record SuperAdmin(
			UUID memberId,

			@Schema(description = "이름. 초대만 되고 활성화 전이면 null")
			String name,

			String email,

			@Schema(description = "계정 상태. ACTIVE(활성) / PENDING(초대됨) / INACTIVE(정지)")
			OperatorAccountStatus status,

			@Schema(description = "최근 로그인 시각. 한 번도 로그인하지 않았으면 null")
			Instant lastLoginAt,

			Instant createdAt,

			@Schema(description = """
					이 계정을 정지할 수 있는지. 활성 슈퍼어드민이 1명뿐이면 그 계정은 false다
					(목업: "마지막 한 명은 정지할 수 없다").""")
			boolean deactivatable
	) {
	}
}
