package com.bigproject.backend.global.json;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;

/**
 * {@link PatchField}의 세 가지 상태를 만들어 내는 역직렬화기.
 *
 * <p>구분의 핵심은 <b>{@code getAbsentValue()}와 {@code getNullValue()}를 다르게 답하는 것</b>이다.
 * Jackson은 키가 없을 때 앞의 것을, 키는 있고 값이 {@code null}일 때 뒤의 것을 쓴다.
 * 기본 구현은 앞이 뒤를 그대로 부르기 때문에 재정의하지 않으면 둘이 같아진다 —
 * 그러면 래퍼를 씌운 의미가 없어진다.
 */
public class PatchFieldDeserializer extends ValueDeserializer<PatchField<?>> {

	/** 래퍼 안의 실제 타입({@code PatchField<Long>}의 {@code Long}). 컨텍스트가 정해지기 전에는 없다. */
	private final JavaType valueType;

	public PatchFieldDeserializer() {
		this(null);
	}

	private PatchFieldDeserializer(JavaType valueType) {
		this.valueType = valueType;
	}

	@Override
	public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
		JavaType wrapperType = property == null ? ctxt.getContextualType() : property.getType();
		return new PatchFieldDeserializer(wrapperType.containedTypeOrUnknown(0));
	}

	/** 키 자체가 없었다. 래퍼를 만들지 않는다 — 이 {@code null}이 "안 보냈다"는 표시다. */
	@Override
	public Object getAbsentValue(DeserializationContext ctxt) {
		return null;
	}

	/** 키는 있고 값이 {@code null}이었다. <b>null을 보냈다는 사실</b>을 래퍼로 남긴다. */
	@Override
	public Object getNullValue(DeserializationContext ctxt) {
		return new PatchField<>(null);
	}

	@Override
	public PatchField<?> deserialize(JsonParser parser, DeserializationContext ctxt) {
		return new PatchField<>(ctxt.readValue(parser, valueType));
	}
}
