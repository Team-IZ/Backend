package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record InvitationResolveRequest(
		@Schema(description = "초대 링크에 포함된 현재 일회용 원문 토큰. 서버에는 해시만 저장되며 교체·만료·사용된 토큰은 거부됩니다.", example = "invitation-token")
		@NotBlank String invitationToken
) {
}
