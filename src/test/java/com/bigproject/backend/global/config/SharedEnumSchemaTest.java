package com.bigproject.backend.global.config;

import com.bigproject.backend.domain.organization.presentation.dto.OrganizationResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 여러 DTO가 같은 enum을 쓰는데 스펙에 값 목록이 <b>복사</b>되면, 생성된 프론트 타입에서 같은 개념이
 * 서로 다른 타입이 된다(기관 상태를 다루는 함수를 목록·상세에 같이 못 쓴다). 더 나쁜 것은
 * 한쪽에만 값이 추가돼도 아무도 모른다는 점이다. 공유 enum은 {@code $ref}여야 한다.
 */
class SharedEnumSchemaTest {

	@Test
	void sharedEnumsAreReferencedInsteadOfCopiedIntoEachDto() {
		Map<String, Schema> schemas = ModelConverters.getInstance()
				.readAll(new AnnotatedType(OrganizationResponse.class));

		assertThat(schemas).containsKeys("OrganizationStatus", "DisclosureScope");

		Map<String, Schema> properties = schemas.get("OrganizationResponse").getProperties();
		assertThat(properties.get("status").get$ref()).isEqualTo("#/components/schemas/OrganizationStatus");
		assertThat(properties.get("defaultDisclosureScope").get$ref())
				.isEqualTo("#/components/schemas/DisclosureScope");

		// 값 목록은 공유 스키마 한 곳에만 있다.
		assertThat(properties.get("status").getEnum()).isNull();
		assertThat(schemas.get("OrganizationStatus").getEnum())
				.containsExactly("ACTIVE", "SUSPENDED", "DELETION_PENDING", "DELETED");
	}
}
