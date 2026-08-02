package com.bigproject.backend.domain.organization.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 기관 삭제 확인. 목업 SA-02 case 7의 확인 모달 — <b>기관명을 직접 입력해야</b> 삭제된다.
 *
 * <p>목업 근거: "소속 오퍼레이터·매니저·교육생 전원이 못 들어오게 되는 액션이라, 버튼 한 번으로 끝나면 안 된다."
 * 이름이 다르면 ORG_DELETE_CONFIRM_MISMATCH로 거절한다.
 */
@Schema(description = "기관 삭제 확인 요청")
public record DeleteOrganizationRequest(

		@Schema(description = "삭제할 기관의 정확한 이름. 저장된 기관명과 다르면 거절된다.",
				example = "그린컴퍼니 부트캠프")
		@NotBlank
		String confirmName
) {
}
