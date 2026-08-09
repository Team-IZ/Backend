package com.bigproject.backend.domain.auth.presentation.dto;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationOpenApiSchemaTest {
	@Test
	void documentsResolveAndActivationResponses() {
		var resolveSchema = ModelConverters.getInstance()
				.resolveAsResolvedSchema(new AnnotatedType(InvitationResolveResponse.class))
				.referencedSchemas.get("InvitationResolveResponse");
		var activationSchema = ModelConverters.getInstance()
				.resolveAsResolvedSchema(new AnnotatedType(ActivateAccountResponse.class))
				.referencedSchemas.get("ActivateAccountResponse");

		assertThat(resolveSchema.getDescription()).contains("가입/활성화 대상");
		assertThat(((Schema<?>) resolveSchema.getProperties().get("email")).getDescription())
				.contains("읽기 전용 이메일");
		assertThat(activationSchema.getDescription()).contains("초대 수락");
		assertThat(((Schema<?>) activationSchema.getProperties().get("activated")).getDescription())
				.contains("모두 완료");
	}
}
