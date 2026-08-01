package com.bigproject.backend.domain.organization.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * 기관 파기 요청 접수 결과. 목업 SA-02 case 8 — "그림 없음(운영자가 API로 요청하는 경로)".
 *
 * <p>보존기간이 남아 있으면 RETENTION_NOT_MET으로 막히고, 지났으면 이 응답으로 접수된다.
 */
@Schema(description = "기관 파기 요청 접수 결과")
public record PurgeOrganizationResponse(
		UUID organizationId,

		@Schema(description = "soft-delete 시각")
		Instant deletedAt,

		@Schema(description = "보존기간 종료(파기 가능) 시각")
		Instant retentionUntil,

		@Schema(description = "파기 요청 접수 시각")
		Instant requestedAt,

		@Schema(description = """
				실제 데이터 파기 진행 여부.
				⚠ 현재는 항상 false — 보존기간 검증(RETENTION_NOT_MET)까지만 구현돼 있고,
				테넌트 데이터 전체를 지우는 파기 배치는 아직 연결되지 않았습니다.""")
		boolean purged,

		@Schema(description = "운영자에게 보여줄 안내 문구")
		String message
) {
}
