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

	/**
	 * role·status는 공유 enum을 {@code $ref}로 가리킨다. 참조 속성에는 설명이 남지 않으므로
	 * (같은 enum을 쓰는 다른 DTO와 값 정의를 공유하는 대가다) 초대 경로별 고정·발송 직후 상태는
	 * 응답 스키마 자체의 설명에 적는다.
	 */
	@Test
	void documentsInvitationResponseSemantics() {
		var resolvedSchema = ModelConverters.getInstance()
				.resolveAsResolvedSchema(new AnnotatedType(InviteManagerResponse.class));
		var schema = resolvedSchema.referencedSchemas.get("InviteManagerResponse");

		assertThat(schema.getDescription())
				.contains("계정 활성화 완료를 의미하지 않습니다")
				.contains("초대 경로별로 고정")
				.contains("발송 직후라 항상 INVITED");
		assertThat(((Schema<?>) schema.getProperties().get("role")).get$ref())
				.isEqualTo("#/components/schemas/Role");
		assertThat(((Schema<?>) schema.getProperties().get("status")).get$ref())
				.isEqualTo("#/components/schemas/AccountStatus");
	}
}
