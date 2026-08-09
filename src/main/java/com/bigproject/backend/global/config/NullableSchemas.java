package com.bigproject.backend.global.config;

import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * OpenAPI 3.1의 "null이 올 수 있다" 표기를 만든다.
 *
 * <pre>
 * 일반 타입: { "type": ["string", "null"] }
 * $ref    : { "oneOf": [ { "$ref": "...#/DisclosureScope" }, { "type": "null" } ] }
 * </pre>
 *
 * <p>{@code $ref}는 형제 키를 함께 쓸 수 없어 {@code oneOf}로 감싼다 —
 * swagger-core는 여기에 "null" 타입을 그냥 얹어 {@code {"type": "null", "$ref": ...}}을 만드는데,
 * 참조를 따라가라는 것인지 null이라는 것인지 알 수 없는 조합이라 생성기가 읽지 못한다.
 */
final class NullableSchemas {

	private NullableSchemas() {
	}

	static Schema<?> orNull(String ref, String description) {
		List<Schema> variants = new ArrayList<>();
		variants.add(new Schema<>().$ref(ref));
		variants.add(nullSchema());

		Schema<?> wrapper = new Schema<>();
		wrapper.setOneOf(variants);
		wrapper.setDescription(description);
		return wrapper;
	}

	/**
	 * {@code type}이 아니라 {@code types}에만 넣는다 — 3.1 직렬화기는 {@code types}를 읽고,
	 * {@code type}까지 채우면 스키마 자체가 JSON {@code null}로 나간다.
	 */
	static Schema<?> nullSchema() {
		Schema<?> schema = new Schema<>();
		schema.setTypes(new LinkedHashSet<>(List.of("null")));
		return schema;
	}
}
