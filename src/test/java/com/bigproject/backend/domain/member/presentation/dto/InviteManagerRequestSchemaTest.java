package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InviteManagerRequestSchemaTest {

	/** 반 배정은 초대 시점에 하지 않으므로 요청에 반 필드가 있으면 안 된다. */
	@Test
	void exposesRoleFreeInvitationScopeWithoutClassAssignment() {
		var resolvedSchema = ModelConverters.getInstance()
				.resolveAsResolvedSchema(new AnnotatedType(InviteManagerRequest.class));
		var schema = resolvedSchema.referencedSchemas.get("InviteManagerRequest");
		var cohortIdSchema = (Schema<?>) schema.getProperties().get("cohortId");

		assertThat(schema.getProperties())
				.containsOnlyKeys("email", "cohortId");
		assertThat(cohortIdSchema.getExample()).isEqualTo("UUID");
	}

	@Test
	void documentsInvitationResponseSemantics() {
		var resolvedSchema = ModelConverters.getInstance()
				.resolveAsResolvedSchema(new AnnotatedType(InviteManagerResponse.class));
		var schema = resolvedSchema.referencedSchemas.get("InviteManagerResponse");

		assertThat(schema.getDescription()).contains("계정 활성화 완료를 의미하지 않습니다");
		assertThat(((Schema<?>) schema.getProperties().get("role")).getDescription())
				.contains("초대 경로별로 고정");
		assertThat(((Schema<?>) schema.getProperties().get("status")).getDescription())
				.contains("초대 발송 직후");
	}
}
