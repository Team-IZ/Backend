package com.bigproject.backend.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스펙 후처리가 프론트 자동 생성이 요구하는 모양을 실제로 만들어 내는지 확인한다.
 * 앱을 띄우지 않고 {@link OpenAPI} 모델에 직접 적용해 검증한다.
 */
class SwaggerConfigTest {

	private static final String SUCCESS_REF = "#/components/schemas/OrganizationResponse";
	private static final String ERROR_REF = "#/components/schemas/ErrorResponse";

	@Test
	void replacesSuccessSchemaOnErrorResponsesAndListsErrorCodesAsExampleKeys() {
		OpenAPI openApi = specWith("/api/v0/organizations", operation(
				"기관 생성 및 기본 운영 정책 초기화 | ✅ 사용 가능",
				Map.of(
						"201", response("생성 성공", SUCCESS_REF),
						"409", response("ORG_NAME_TAKEN · ORG_IDEMPOTENCY_CONFLICT", SUCCESS_REF)
				)
		));

		customise(openApi);

		ApiResponse conflict = operationOf(openApi, "/api/v0/organizations").getResponses().get("409");
		assertThat(conflict.getContent()).containsOnlyKeys("application/json");
		assertThat(conflict.getContent().get("application/json").getSchema().get$ref()).isEqualTo(ERROR_REF);

		// examples의 키가 곧 에러 코드 목록이다 — 프론트가 여기서 상수를 기계적으로 뽑는다.
		assertThat(conflict.getContent().get("application/json").getExamples())
				.containsOnlyKeys("ORG_NAME_TAKEN", "ORG_IDEMPOTENCY_CONFLICT");
		assertThat(conflict.getContent().get("application/json").getExamples().get("ORG_NAME_TAKEN").getValue())
				.isEqualTo(Map.of("code", "ORG_NAME_TAKEN", "message", "이미 있는 기관명입니다."));

		// 성공 응답은 건드리지 않는다.
		ApiResponse created = operationOf(openApi, "/api/v0/organizations").getResponses().get("201");
		assertThat(created.getContent().get("application/json").getSchema().get$ref()).isEqualTo(SUCCESS_REF);
	}

	@Test
	void fallsBackToTheCodeTheRuntimeActuallySendsWhenNoneIsDocumented() {
		OpenAPI openApi = specWith("/api/v0/organizations", operation(
				"기관 목록 조회 | ✅ 사용 가능",
				Map.of(
						"401", response("액세스 토큰이 없거나 유효하지 않음", SUCCESS_REF),
						"403", response("오퍼레이터 권한이 없음", SUCCESS_REF),
						"400", response("page·size 범위가 올바르지 않음", SUCCESS_REF)
				)
		));

		customise(openApi);

		ApiResponses responses = operationOf(openApi, "/api/v0/organizations").getResponses();
		assertThat(examplesOf(responses.get("401"))).containsOnlyKeys("UNAUTHENTICATED");
		assertThat(examplesOf(responses.get("403"))).containsOnlyKeys("ACCESS_DENIED");
		assertThat(examplesOf(responses.get("400"))).containsOnlyKeys("VALIDATION_FAILED", "BAD_REQUEST");
	}

	@Test
	void requiresBearerTokenGloballyAndClearsItOnPublicPaths() {
		OpenAPI openApi = specWith("/api/v0/auth/login", operation("로그인 | ✅ 사용 가능", Map.of()));
		openApi.getPaths().addPathItem("/api/v0/organizations",
				new PathItem().get(operation("기관 목록 조회 | ✅ 사용 가능", Map.of())));

		customise(openApi);

		assertThat(openApi.getSecurity()).hasSize(1);
		assertThat(openApi.getSecurity().get(0)).containsKey("bearerAuth");

		// 공개 API는 비워서 "토큰 불필요"임을 명시한다. 필드를 지우면 전역 요구를 물려받는다.
		assertThat(operationOf(openApi, "/api/v0/auth/login").getSecurity()).isEmpty();
		assertThat(openApi.getPaths().get("/api/v0/organizations").getGet().getSecurity())
				.singleElement()
				.satisfies(requirement -> assertThat(requirement).containsKey("bearerAuth"));
	}

	@Test
	void exposesReadinessMarkerAsVendorExtension() {
		OpenAPI openApi = specWith("/api/v0/reports", operation("리포트 조회 | ⚠️ 사용 불가", Map.of()));
		openApi.getPaths().addPathItem("/api/v0/organizations/{id}/purge",
				new PathItem().post(operation("기관 파기 요청 | ⚠️ 사용 보류", Map.of())));

		customise(openApi);

		assertThat(operationOf(openApi, "/api/v0/reports").getExtensions())
				.containsEntry("x-readiness", "unavailable");
		assertThat(openApi.getPaths().get("/api/v0/organizations/{id}/purge").getPost().getExtensions())
				.containsEntry("x-readiness", "hold");
	}

	@Test
	void registersTheErrorSchemaThatEveryErrorResponsePointsAt() {
		OpenAPI openApi = specWith("/api/v0/organizations", operation("기관 목록 조회 | ✅ 사용 가능", Map.of()));

		customise(openApi);

		assertThat(openApi.getComponents().getSchemas()).containsKeys("ErrorResponse", "FieldError");
		Schema<?> errorSchema = openApi.getComponents().getSchemas().get("ErrorResponse");
		assertThat(errorSchema.getProperties()).containsKeys("timestamp", "status", "error", "code", "message");
		assertThat(errorSchema.getRequired()).contains("code", "message", "status");
	}

	/**
	 * 숫자 필드의 값 목록이 문자열로 나가면 생성 타입이 {@code '90' | '180' | '365'}가 되어
	 * 숫자를 보내는 호출부마다 캐스팅이 한 줄씩 붙는다({@code dataRetentionDays}가 그랬다).
	 * {@code allowableValues}가 String[]밖에 될 수 없어서 생기는 일이라 스펙 단계에서 되돌린다.
	 */
	@Test
	void writesNumericAllowableValuesAsNumbersNotStrings() {
		Schema<Object> retentionDays = new Schema<>();
		retentionDays.setType("integer");
		retentionDays.setEnum(new ArrayList<Object>(List.of("90", "180", "365")));
		Schema<?> request = new Schema<>().type("object").addProperty("dataRetentionDays", retentionDays);

		OpenAPI openApi = specWith("/api/v0/organizations", operation("기관 생성 | ✅ 사용 가능", Map.of()));
		openApi.components(new Components().addSchemas("CreateOrganizationRequest", request));

		customise(openApi);

		assertThat(((Schema<Object>) openApi.getComponents().getSchemas().get("CreateOrganizationRequest")
				.getProperties().get("dataRetentionDays")).getEnum())
				.containsExactly(90L, 180L, 365L);
	}

	/** 숫자가 아닌 값 목록은 건드리지 않는다 — enum 문자열까지 숫자로 바꾸면 그쪽이 깨진다. */
	@Test
	void leavesStringEnumsAlone() {
		Schema<Object> status = new Schema<>();
		status.setType("string");
		status.setEnum(new ArrayList<Object>(List.of("ACTIVE", "SUSPENDED")));
		Schema<?> response = new Schema<>().type("object").addProperty("status", status);

		OpenAPI openApi = specWith("/api/v0/organizations", operation("기관 조회 | ✅ 사용 가능", Map.of()));
		openApi.components(new Components().addSchemas("OrganizationResponse", response));

		customise(openApi);

		assertThat(((Schema<Object>) openApi.getComponents().getSchemas().get("OrganizationResponse")
				.getProperties().get("status")).getEnum())
				.containsExactly("ACTIVE", "SUSPENDED");
	}

	/**
	 * 프로젝트 실행·교안 도메인의 코드가 카탈로그에 등록되어 있어야 예시 본문에 도메인 기본 메시지가 실린다.
	 * 등록을 빠뜨리면 조용히 "요청을 처리할 수 없습니다."로 나가고, 코드가 없는 것과 구분되지 않는다.
	 */
	@Test
	void knowsTheProjectExecutionAndCurriculumCodes() {
		OpenAPI openApi = specWith("/api/v0/projects/{projectId}/curricula", operation(
				"프로젝트 교안 연결 | ✅ 사용 가능",
				Map.of(
						"404", response("PROJECT_NOT_FOUND 프로젝트가 없음 · CURRICULUM_VERSION_NOT_FOUND 교안 버전이 없음", SUCCESS_REF),
						"409", response("CURRICULUM_ALREADY_LINKED 이미 연결된 교안 버전", SUCCESS_REF)
				)
		));

		customise(openApi);

		ApiResponses responses = operationOf(openApi, "/api/v0/projects/{projectId}/curricula").getResponses();
		assertThat(examplesOf(responses.get("404")))
				.containsOnlyKeys("PROJECT_NOT_FOUND", "CURRICULUM_VERSION_NOT_FOUND");
		assertThat(examplesOf(responses.get("409")).get("CURRICULUM_ALREADY_LINKED"))
				.extracting("value")
				.isEqualTo(Map.of("code", "CURRICULUM_ALREADY_LINKED", "message", "이미 연결된 교안 버전입니다."));
	}

	private void customise(OpenAPI openApi) {
		new SwaggerConfig().izGetOpenApiCustomizer().customise(openApi);
	}

	private OpenAPI specWith(String path, Operation operation) {
		return new OpenAPI().paths(new Paths().addPathItem(path, new PathItem().post(operation)));
	}

	private Operation operation(String summary, Map<String, ApiResponse> responses) {
		ApiResponses apiResponses = new ApiResponses();
		responses.forEach(apiResponses::addApiResponse);
		return new Operation().summary(summary).responses(apiResponses);
	}

	private ApiResponse response(String description, String schemaRef) {
		return new ApiResponse()
				.description(description)
				.content(new Content().addMediaType(
						"application/json",
						new MediaType().schema(new Schema<>().$ref(schemaRef))
				));
	}

	private Operation operationOf(OpenAPI openApi, String path) {
		PathItem pathItem = openApi.getPaths().get(path);
		return pathItem.getPost() != null ? pathItem.getPost() : pathItem.getGet();
	}

	private Map<String, ?> examplesOf(ApiResponse response) {
		return response.getContent().get("application/json").getExamples();
	}
}