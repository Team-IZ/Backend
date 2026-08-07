package com.bigproject.backend.global.config;

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
