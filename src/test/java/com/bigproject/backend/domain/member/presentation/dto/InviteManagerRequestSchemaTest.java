package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InviteManagerRequestSchemaTest {

	@Test
	void exposesOnlyRequestFieldsWithRestrictedRoleAndUuidExample() {
		var resolvedSchema = ModelConverters.getInstance()
				.resolveAsResolvedSchema(new AnnotatedType(InviteManagerRequest.class));
		var schema = resolvedSchema.referencedSchemas.get("InviteManagerRequest");
		var roleSchema = (Schema<?>) schema.getProperties().get("role");
		var cohortIdSchema = (Schema<?>) schema.getProperties().get("cohortId");

		assertThat(schema.getProperties())
				.containsOnlyKeys("email", "role", "cohortId", "classroomIds");
		assertThat(roleSchema.getEnum().toString()).isEqualTo("[LEAD_MANAGER, MANAGER]");
		assertThat(cohortIdSchema.getExample()).isEqualTo("UUID");
	}
}
