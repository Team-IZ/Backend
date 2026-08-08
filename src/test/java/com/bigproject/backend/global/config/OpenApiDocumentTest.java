package com.bigproject.backend.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * <b>실제로 나가는 스펙</b>을 검사한다. 단위 테스트는 변환기 하나하나를 확인하지만,
 * springdoc이 그 변환기를 붙여 주지 않으면 전부 통과해도 배포된 문서는 그대로다.
 *
 * <p>여기 걸린 항목은 프론트가 이 스펙으로 타입·API 클라이언트를 자동 생성할 때
 * 하나라도 어긋나면 생성물이 거짓말을 하게 되는 것들이다.
 */
@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:openapi-doc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
		"jwt.access-token-expiration=900000",
		"jwt.refresh-token-expiration=1209600000",
		"auth.login.allowed-origins=http://localhost:5173",
		"auth.login.swagger-origin-override-enabled=false",
		"auth.login.swagger-ui-origin=http://localhost:8080",
		"auth.refresh-cookie.name=refresh_token",
		"auth.refresh-cookie.path=/api/v0/auth",
		"auth.refresh-cookie.secure=false",
		"auth.refresh-cookie.same-site=Lax",
		"invitation.base-url=http://localhost:5173",
		"invitation.expiration=P7D"
})
@AutoConfigureMockMvc
class OpenApiDocumentTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private MockMvc mockMvc;

	private JsonNode spec;

	private JsonNode spec() throws Exception {
		if (spec == null) {
			spec = MAPPER.readTree(mockMvc.perform(get("/v3/api-docs"))
					.andReturn().getResponse().getContentAsString());
		}
		return spec;
	}

	/**
	 * springdoc 기본 동작은 {@code content}를 적지 않은 {@code @ApiResponse}에 <b>핸들러 반환 타입</b>을
	 * 물려준다 — 409가 기관 정보를 준다고 선언되는 식이다. 하나라도 남으면 프론트 에러 처리가
	 * 잘못된 타입 위에 서게 된다.
	 */
	@Test
	void everyErrorResponsePointsAtTheSharedErrorSchema() throws Exception {
		List<String> wrong = new ArrayList<>();
		forEachErrorResponse(spec(), (operationId, status, response) -> {
			JsonNode schema = response.path("content").path("application/json").path("schema");
			if (!"#/components/schemas/ErrorResponse".equals(schema.path("$ref").asString(null))) {
				wrong.add(operationId + " " + status);
			}
		});

		assertThat(wrong).isEmpty();
	}

	/** examples 키가 곧 에러 코드 목록이다. 비어 있으면 프론트가 코드를 손으로 다시 적게 된다. */
	@Test
	void everyErrorResponseListsTheCodesItCanReturn() throws Exception {
		List<String> missing = new ArrayList<>();
		forEachErrorResponse(spec(), (operationId, status, response) -> {
			JsonNode examples = response.path("content").path("application/json").path("examples");
			if (!examples.isObject() || examples.isEmpty()) {
				missing.add(operationId + " " + status);
			}
		});

		assertThat(missing).isEmpty();
	}

	@Test
	void declaresSuccessResponsesAsJsonRatherThanAnyContentType() throws Exception {
		List<String> wildcards = new ArrayList<>();
		forEachOperation(spec(), (operationId, operation) ->
				operation.path("responses").properties().forEach(response -> {
					if (response.getValue().path("content").has("*/*")) {
						wildcards.add(operationId + " " + response.getKey());
					}
				}));

		assertThat(wildcards).isEmpty();
	}

	@Test
	void givesEveryOperationAStableGloballyUniqueId() throws Exception {
		List<String> ids = new ArrayList<>();
		forEachOperation(spec(), (operationId, operation) -> ids.add(operationId));

		assertThat(ids).doesNotHaveDuplicates();
		// springdoc이 중복을 만나면 붙이는 접미사. 나중에 다른 오퍼레이션으로 옮겨가 이름이 조용히 바뀐다.
		assertThat(ids).noneMatch(id -> id.matches(".*_\\d+$"));
	}

	@Test
	void marksWhichOperationsNeedATokenAndWhichDoNot() throws Exception {
		assertThat(spec().path("security").toString()).contains("bearerAuth");

		JsonNode publicOperation = spec().path("paths").path("/api/v0/auth/login").path("post");
		assertThat(publicOperation.path("security").isArray()).isTrue();
		assertThat(publicOperation.path("security")).isEmpty();

		JsonNode securedOperation = spec().path("paths").path("/api/v0/organizations").path("get");
		assertThat(securedOperation.path("security").toString()).contains("bearerAuth");
	}

	@Test
	void tagsOperationsWithMachineReadableReadiness() throws Exception {
		Map<String, Integer> counts = new java.util.TreeMap<>();
		forEachOperation(spec(), (operationId, operation) -> {
			String readiness = operation.path("x-readiness").asString(null);
			assertThat(readiness)
					.withFailMessage("x-readiness 없음: %s", operationId)
					.isNotNull();
			counts.merge(readiness, 1, Integer::sum);
		});

		assertThat(counts.keySet()).containsAnyOf("available", "hold", "unavailable");
	}

	/**
	 * 같은 개념이 DTO마다 복사되면 생성 타입에서 서로 다른 타입이 되고, 한쪽에만 값이 추가돼도 아무도 모른다.
	 * 정의는 한 곳이어야 하며, 그 정의의 설명은 <b>enum 자신이 선언한 것</b>이어야 한다 —
	 * 특정 필드의 사정이 공유 정의로 새면 그 enum을 쓰는 다른 필드가 전부 그 설명을 달고 나간다.
	 */
	@Test
	void keepsSharedEnumsAsOneDefinitionInsteadOfCopies() throws Exception {
		JsonNode schemas = spec().path("components").path("schemas");

		assertThat(schemas.has("OrganizationStatus")).isTrue();
		assertThat(schemas.has("DisclosureScope")).isTrue();
		assertThat(schemas.has("Role")).isTrue();
		assertThat(schemas.has("AiTier")).isTrue();
		assertThat(schemas.path("OrganizationResponse").path("properties").path("status").path("$ref").asString())
				.isEqualTo("#/components/schemas/OrganizationStatus");

		assertThat(schemas.path("DisclosureScope").path("description").asString())
				.isEqualTo("리포트 공개 범위. SUMMARY(요약만) · PRIVATE(비공개) · FULL(전문)");
		assertThat(schemas.path("DisclosureScope").has("nullable")).isFalse();

		// 목록 항목도 같은 정의를 가리킨다. 값을 복사해 두면 순서만 달라도 사람 눈에는 같아 보여
		// 한쪽에 값이 추가된 것을 아무도 눈치채지 못한다.
		JsonNode item = schemas.path("ManagedReportItem").path("properties");
		assertThat(item.path("releaseStatus").path("$ref").asString())
				.isEqualTo("#/components/schemas/TraineeReleaseStatus");
		assertThat(item.path("scope").path("oneOf").toString())
				.contains("#/components/schemas/DisclosureScope");
	}

	/**
	 * 목록 항목 스키마는 <b>무엇의 항목인지</b> 이름으로 드러나야 한다. {@code Item} 같은 이름은
	 * 전역 이름 공간에서 다음 목록 응답과 부딪히고, 먼저 등록된 쪽이 조용히 덮인다.
	 */
	@Test
	void namesNestedListItemsAfterWhatTheyContain() throws Exception {
		JsonNode schemas = spec().path("components").path("schemas");

		assertThat(schemas.has("Item")).isFalse();
		assertThat(schemas.has("ManagedReportItem")).isTrue();
		// 항상 오는 필드가 required로 나가야 화면 타입이 `?`·`!` 없이 쓰인다.
		// 상태에 따라 키가 빠지는 넷은 그 반대다 — required로 적으면 스펙이 거짓말을 한다.
		assertThat(schemas.path("ManagedReportItem").path("required").toString())
				.contains("reportId", "assessmentRoundId", "traineeUserId", "className",
						"releaseStatus", "bodyVisible")
				.doesNotContain("roundName", "publishedAt", "scope", "releasedAt");
	}

	@Test
	void marksAlwaysPresentResponseFieldsRequired() throws Exception {
		JsonNode organization = spec().path("components").path("schemas").path("OrganizationResponse");

		assertThat(organization.path("required").toString())
				.contains("organizationId", "name", "status", "slug");
	}

	/** 3.1에서 null 가능은 타입 배열로 쓴다. 설명문에만 있으면 타입이 거짓말을 하고 런타임에 터진다. */
	@Test
	void writesNullabilityIntoTheTypeNotJustTheDescription() throws Exception {
		JsonNode properties = spec().path("components").path("schemas")
				.path("OrganizationResponse").path("properties");

		assertThat(properties.path("slug").path("type").toString()).isEqualTo("[\"string\",\"null\"]");
		assertThat(properties.path("defaultDisclosureScope").path("oneOf").toString())
				.contains("#/components/schemas/DisclosureScope")
				.contains("\"null\"");
	}

	private interface ResponseVisitor {
		void visit(String operationId, String status, JsonNode response);
	}

	private interface OperationVisitor {
		void visit(String operationId, JsonNode operation);
	}

	private void forEachOperation(JsonNode spec, OperationVisitor visitor) {
		spec.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
			JsonNode operation = method.getValue();
			if (operation.has("responses")) {
				visitor.visit(operation.path("operationId").asString(method.getKey()), operation);
			}
		}));
	}

	private void forEachErrorResponse(JsonNode spec, ResponseVisitor visitor) {
		forEachOperation(spec, (operationId, operation) ->
				operation.path("responses").properties().forEach(response -> {
					String status = response.getKey();
					if (status.length() == 3 && (status.charAt(0) == '4' || status.charAt(0) == '5')) {
						visitor.visit(operationId, status, response.getValue());
					}
				}));
	}
}
