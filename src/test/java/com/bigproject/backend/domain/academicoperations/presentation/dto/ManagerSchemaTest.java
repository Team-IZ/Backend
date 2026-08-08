package com.bigproject.backend.domain.academicoperations.presentation.dto;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9차 R5 — 매니저 ID가 나오는 자리가 <b>전부 같은 타입</b>인지 못 박는다.
 *
 * <p>반 탭의 담당 매니저 모달은 "읽고 → 고쳐서 → 다시 보내는" 왕복이다. 되읽은 ID를 다시 보낼 수
 * 없으면 저장하는 순간 나머지 담당자가 전부 해제된다({@code UpdateClassroomManagersRequest}가
 * "보낸 목록이 그대로 최종 상태"라고 명시돼 있다).
 */
class ManagerSchemaTest {

	/**
	 * 예전에는 {@code ClassroomResponse}와 {@code CohortResponse}가 각자 {@code Manager}라는 이름의
	 * 중첩 record를 들고 있었다. springdoc은 스키마를 <b>단순 클래스 이름</b>으로 키잉하므로 두 정의가
	 * 한 키를 놓고 충돌했고, 먼저 등록된 쪽(integer)이 이겨서 스펙이 서버와 다른 말을 했다.
	 * 정의를 하나로 합쳐 충돌 자체를 없앤다.
	 */
	@Test
	void identifiesAManagerByUuidEverywhereItAppears() {
		Map<String, Schema> classroomSchemas = ModelConverters.getInstance()
				.readAll(new AnnotatedType(ClassroomResponse.class));
		Map<String, Schema> cohortSchemas = ModelConverters.getInstance()
				.readAll(new AnnotatedType(CohortResponse.class));

		// 두 응답이 같은 정의를 가리킨다 — 값을 복사해 두면 한쪽에만 필드가 붙어도 아무도 모른다.
		assertThat(itemRefOf(classroomSchemas.get("ClassroomResponse"), "managers"))
				.isEqualTo("#/components/schemas/Manager");
		assertThat(itemRefOf(cohortSchemas.get("CohortResponse"), "managers"))
				.isEqualTo("#/components/schemas/Manager");

		Schema<?> memberId = (Schema<?>) classroomSchemas.get("Manager").getProperties().get("memberId");
		assertThat(memberId.getType()).isEqualTo("string");
		assertThat(memberId.getFormat()).isEqualTo("uuid");
	}

	/** 이름만으로는 동명이인을 가를 수 없다. 반 카드가 `이도윤 · lee@…`를 그린다. */
	@Test
	void carriesTheEmailSoDuplicateNamesCanBeToldApart() {
		Map<String, Schema> schemas = ModelConverters.getInstance()
				.readAll(new AnnotatedType(ClassroomResponse.class));

		assertThat(schemas.get("Manager").getProperties()).containsKeys("memberId", "name", "email");
	}

	@SuppressWarnings("rawtypes")
	private String itemRefOf(Schema schema, String propertyName) {
		Schema<?> property = (Schema<?>) schema.getProperties().get(propertyName);
		return property.getItems().get$ref();
	}
}
