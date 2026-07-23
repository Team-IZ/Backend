package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record InvitationResolveRequest(
		@Schema(description = "초대 링크에 포함된 일회용 원문 토큰", example = "invitation-token")
		@NotBlank String invitationToken
) {
}
