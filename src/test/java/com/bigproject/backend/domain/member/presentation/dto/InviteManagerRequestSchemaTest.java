package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InviteManagerRequestSchemaTest {

	@Test
	void exposesRoleFreeInvitationScope() {
		var resolvedSchema = ModelConverters.getInstance()
				.resolveAsResolvedSchema(new AnnotatedType(InviteManagerRequest.class));
		var schema = resolvedSchema.referencedSchemas.get("InviteManagerRequest");
		var cohortIdSchema = (Schema<?>) schema.getProperties().get("cohortId");
		var targetClassIdSchema = (Schema<?>) schema.getProperties().get("targetClassId");

		assertThat(schema.getProperties())
				.containsOnlyKeys("email", "cohortId", "targetClassId");
		assertThat(cohortIdSchema.getExample()).isEqualTo("UUID");
		assertThat(targetClassIdSchema.getExample()).isEqualTo("UUID");
	}

	@Test
	void documentsInvitationResponseSemantics() {
		var resolvedSchema = ModelConverters.getInstance()
				.resolveAsResolvedSchema(new AnnotatedType(InviteManagerResponse.class));
		var schema = resolvedSchema.referencedSchemas.get("InviteManagerResponse");

		assertThat(schema.getDescription()).contains("계정 활성화 완료를 의미하지 않습니다");
		assertThat(((Schema<?>) schema.getProperties().get("role")).getDescription())
				.contains("서버가 호출자 역할로 결정");
		assertThat(((Schema<?>) schema.getProperties().get("status")).getDescription())
				.contains("초대 발송 직후");
	}
}
