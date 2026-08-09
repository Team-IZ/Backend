package com.bigproject.backend.global.json;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * 부분 수정 요청에서 <b>"보내지 않음"과 "null을 보냄"을 구분</b>하는 필드 래퍼.
 *
 * <p>보통의 필드는 둘을 구분할 수 없다 — Jackson은 키가 없어도, 키가 있고 값이 {@code null}이어도
 * 똑같이 {@code null}을 넘긴다({@link java.util.Optional}로 받아도 양쪽 다 {@code Optional.empty()}다).
 * 그래서 <b>null 자체가 의미를 갖는 필드</b>에서만 이 래퍼를 쓴다.
 *
 * <pre>
 * 키 없음        → 필드가 null          → 지금 값을 유지한다
 * "limit": null  → PatchField(null)     → null로 바꾼다(무제한)
 * "limit": 100   → PatchField(100)      → 100으로 바꾼다
 * </pre>
 *
 * <p>운영 설정의 {@code monthlyTokenLimit}·{@code storageLimitBytes}가 그 경우다.
 * 두 필드는 {@code null}이 "값 없음"이 아니라 <b>무제한</b>이라는 값이라, 래퍼 없이 부분 수정을 하면
 * 한 번 상한을 건 기관을 다시 무제한으로 되돌릴 방법이 사라진다.
 *
 * <p>스펙에는 래퍼가 드러나지 않는다 — 필드마다
 * {@code @Schema(implementation = ..., nullable = true)}로 안의 타입을 적어 준다.
 * 생성되는 화면 타입은 {@code monthlyTokenLimit?: number | null}이 된다.
 */
@JsonDeserialize(using = PatchFieldDeserializer.class)
public record PatchField<T>(T value) {

	public static <T> PatchField<T> of(T value) {
		return new PatchField<>(value);
	}

	/**
	 * 부분 수정의 병합 규칙. 필드가 오지 않았으면({@code field == null}) 현재 값을 그대로 두고,
	 * 왔으면 그 값으로 바꾼다 — 보낸 값이 {@code null}이어도 그대로 반영한다.
	 */
	public static <T> T mergeInto(PatchField<T> field, T current) {
		return field == null ? current : field.value();
	}
}
