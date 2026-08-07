package com.bigproject.backend.global.config;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.organization.domain.OrganizationErrorCode;
import com.bigproject.backend.domain.reporting.domain.ReportErrorCode;
import com.bigproject.backend.global.exception.ApiErrorCode;
import com.bigproject.backend.global.exception.ErrorResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@OpenAPIDefinition(
		info = @Info(title = "IZ-Get", description = "IZ-Get API Specification", version = "v0.1")
)
@SecurityScheme(
		name = SwaggerConfig.BEARER_AUTH,
		description = "로그인 API에서 발급받은 access token만 입력하세요. Bearer 접두사는 Swagger UI가 자동으로 추가합니다.",
		type = SecuritySchemeType.HTTP,
		scheme = "bearer",
		bearerFormat = "JWT"
)
@Configuration
public class SwaggerConfig {

	public static final String BEARER_AUTH = "bearerAuth";

	private static final String ERROR_SCHEMA_NAME = "ErrorResponse";
	private static final String ERROR_SCHEMA_REF = Components.COMPONENTS_SCHEMAS_REF + ERROR_SCHEMA_NAME;
	private static final String JSON = org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

	/**
	 * 인증 없이 호출하는 경로. {@code SecurityConfig}의 permitAll 목록과 같은 내용이며,
	 * 여기가 어긋나면 스펙이 "토큰이 필요한가"를 잘못 알려 준다.
	 */
	private static final List<Pattern> PUBLIC_PATHS = List.of(
			Pattern.compile("/api/v0/auth/.*"),
			Pattern.compile("/api/v0/consents")
	);

	/**
	 * 응답 설명에 적힌 에러 코드를 뽑는 패턴. {@code ORG_NAME_TAKEN}처럼 대문자와 밑줄로만 이루어진 토큰만 잡는다
	 * — 밑줄을 하나 이상 요구하므로 {@code ACTIVE}·{@code SENT} 같은 일반 대문자 단어는 걸리지 않는다.
	 */
	private static final Pattern ERROR_CODE_IN_DESCRIPTION =
			Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");

	/** 도메인 에러 코드 카탈로그. 새 도메인이 {@link ApiErrorCode} enum을 만들면 여기 등록한다. */
	private static final Map<String, String> ERROR_CODE_CATALOG = Stream.<ApiErrorCode[]>of(
					OrganizationErrorCode.values(), ReportErrorCode.values(), AuthErrorCode.values(),
					AcademicOperationsErrorCode.values(), MemberErrorCode.values())
			.flatMap(Arrays::stream)
			.collect(LinkedHashMap::new, (map, code) -> map.put(code.name(), code.defaultMessage()), Map::putAll);

	/**
	 * 도메인 코드가 없는 상태에 붙는 코드. {@code GlobalExceptionHandler}가 실제로 내려보내는 값이다 —
	 * 안정 코드가 없는 예외는 HTTP 상태 이름을 코드로 쓴다.
	 */
	private static final Map<String, List<String>> FALLBACK_CODES_BY_STATUS = Map.of(
			"400", List.of("VALIDATION_FAILED", "BAD_REQUEST"),
			"404", List.of("NOT_FOUND"),
			"409", List.of("CONFLICT"),
			"410", List.of("GONE"),
			"422", List.of("UNPROCESSABLE_ENTITY"),
			"500", List.of("INTERNAL_SERVER_ERROR"),
			"502", List.of("BAD_GATEWAY"),
			"503", List.of("SERVICE_UNAVAILABLE")
	);

	/** {@code summary}에 붙은 준비 상태 마커 → 기계가 읽는 벤더 확장값. */
	private static final Map<String, String> READINESS_BY_MARKER = Map.of(
			"✅ 사용 가능", "available",
			"⚠️ 사용 보류", "hold",
			"⚠️ 사용 불가", "unavailable"
	);

	/**
	 * springdoc 기본 동작이 만드는 세 가지 거짓말을 스펙 단계에서 한 번에 걷어낸다.
	 *
	 * <ol>
	 *   <li><b>에러 응답이 성공 DTO를 가리킨다.</b> {@code @ApiResponse}에 {@code content}를 적지 않으면
	 *       핸들러 반환 타입 스키마를 그대로 물려받는다 — 409가 {@code OrganizationResponse}를 준다고
	 *       선언되어 프론트 에러 처리가 통째로 잘못된 타입 위에 서게 된다.
	 *       모든 4xx·5xx를 {@link ErrorResponse}로 바꾸고, 설명에 적힌 에러 코드를
	 *       <b>examples 키</b>로 올려 기계가 코드 목록을 뽑을 수 있게 한다.</li>
	 *   <li><b>인증 요구가 스펙에 없다.</b> 전역 {@code security}를 걸고 공개 API만 {@code security: []}로 푼다.
	 *       표기가 아예 없으면 "인증 불필요"와 구분되지 않는다.</li>
	 *   <li><b>준비 상태가 문자열 안에만 있다.</b> {@code summary}의 ✅/⚠️ 마커를 {@code x-readiness}로 함께 낸다.
	 *       프론트가 준비된 오퍼레이션만 실서버에 붙이고 나머지는 목으로 두는 전환을 스크립트로 처리한다.</li>
	 *   <li><b>null이 온다는 사실이 사라진다.</b> {@code @Schema(nullable = true)}는 3.0 전용 필드에 담기는데
	 *       스펙을 3.1로 직렬화하면 그 필드가 그냥 없어진다. 3.1 표기로 옮겨 적는다.</li>
	 * </ol>
	 *
	 * <p>컨트롤러마다 애너테이션을 99번 적지 않고 여기서 한 번에 거는 이유는, 응답 하나를 빠뜨려도
	 * 아무도 모르기 때문이다. 문서화되지 않은 4xx가 생기면 그건 스펙이 아니라 코드의 문제다.
	 */
	@Bean
	public OpenApiCustomizer izGetOpenApiCustomizer() {
		return openApi -> {
			registerErrorSchemas(openApi);
			applyNullability(openApi);
			openApi.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));

			if (openApi.getPaths() == null) {
				return;
			}
			openApi.getPaths().forEach((path, pathItem) -> {
				boolean publicPath = isPublicPath(path);
				pathItem.readOperations().forEach(operation -> {
					applyReadinessExtension(operation);
					applySecurity(operation, publicPath);
					applyErrorSchema(operation, publicPath);
				});
			});
		};
	}

	/**
	 * {@link ErrorResponse}는 어느 핸들러의 반환 타입도 아니라서 springdoc이 스스로 찾아내지 못한다.
	 * {@code $ref}가 가리킬 대상을 직접 등록한다.
	 */
	private void registerErrorSchemas(OpenAPI openApi) {
		if (openApi.getComponents() == null) {
			openApi.setComponents(new Components());
		}
		ModelConverters.getInstance()
				.readAll(new AnnotatedType(ErrorResponse.class))
				.forEach(openApi.getComponents()::addSchemas);
	}

	/** {@code nullable: true}를 3.1 표기({@link NullableSchemas})로 옮긴다. */
	private void applyNullability(OpenAPI openApi) {
		if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
			return;
		}
		// 컴포넌트 자신은 대상이 아니다. 공유 정의가 특정 필드 사정으로 nullable이 되면 안 된다.
		openApi.getComponents().getSchemas().values().forEach(schema -> {
			schema.setNullable(null);
			normalizeMembers(schema);
		});
	}

	private void normalizeMembers(Schema<?> schema) {
		if (schema.getProperties() != null) {
			Map<String, Schema> properties = schema.getProperties();
			new ArrayList<>(properties.keySet())
					.forEach(name -> properties.put(name, normalizeNullable(properties.get(name))));
		}
		if (schema.getItems() != null) {
			schema.setItems(normalizeNullable(schema.getItems()));
		}
	}

	private Schema<?> normalizeNullable(Schema<?> schema) {
		if (schema == null) {
			return null;
		}
		normalizeMembers(schema);
		return toNullableIn31(schema);
	}

	/**
	 * swagger-core는 {@code $ref} 속성에도 "null" 타입을 그냥 얹어 {@code {"type": "null", "$ref": ...}}을
	 * 만든다 — 참조를 따라가라는 것인지 null이라는 것인지 알 수 없는 조합이라 생성기가 읽지 못한다.
	 * {@code $ref}는 형제 키를 못 쓰므로 {@code oneOf}로 옮겨 준다.
	 */
	private Schema<?> toNullableIn31(Schema<?> schema) {
		boolean nullable = Boolean.TRUE.equals(schema.getNullable())
				|| (schema.getTypes() != null && schema.getTypes().contains("null"));
		if (!nullable) {
			return schema;
		}
		schema.setNullable(null);

		if (schema.get$ref() != null) {
			return NullableSchemas.orNull(schema.get$ref(), schema.getDescription());
		}
		schema.addType("null");
		return schema;
	}

	private void applyReadinessExtension(Operation operation) {
		String summary = operation.getSummary();
		if (summary == null) {
			return;
		}
		READINESS_BY_MARKER.entrySet().stream()
				.filter(entry -> summary.contains(entry.getKey()))
				.findFirst()
				.ifPresent(entry -> operation.addExtension("x-readiness", entry.getValue()));
	}

	/**
	 * 전역 요구를 걸어 둔 상태이므로 공개 API는 <b>빈 배열로 명시해</b> 풀어 준다.
	 * 필드를 지우면 전역 요구를 그대로 물려받아 "토큰이 필요하다"로 읽힌다.
	 */
	private void applySecurity(Operation operation, boolean publicPath) {
		if (publicPath) {
			operation.setSecurity(new ArrayList<>());
			return;
		}
		if (operation.getSecurity() == null || operation.getSecurity().isEmpty()) {
			operation.setSecurity(List.of(new SecurityRequirement().addList(BEARER_AUTH)));
		}
	}

	private void applyErrorSchema(Operation operation, boolean publicPath) {
		if (operation.getResponses() == null) {
			return;
		}
		operation.getResponses().forEach((statusCode, response) -> {
			if (!isError(statusCode)) {
				return;
			}
			MediaType mediaType = new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA_REF));
			errorCodesFor(statusCode, response, publicPath)
					.forEach(code -> mediaType.addExamples(code, exampleFor(code)));
			response.setContent(new Content().addMediaType(JSON, mediaType));
		});
	}

	/**
	 * 이 응답이 낼 수 있는 에러 코드. 설명에 적힌 도메인 코드를 우선하고, 없으면 런타임이 실제로
	 * 내려보내는 기본값을 쓴다(인증·인가 실패는 시큐리티 필터가 고정 코드로 쓴다).
	 */
	private List<String> errorCodesFor(String statusCode, ApiResponse response, boolean publicPath) {
		List<String> declared = extractErrorCodes(response.getDescription());
		if (!declared.isEmpty()) {
			return declared;
		}
		return switch (statusCode) {
			case "401" -> publicPath ? List.of("UNAUTHORIZED") : List.of("UNAUTHENTICATED");
			case "403" -> publicPath ? List.of("FORBIDDEN") : List.of("ACCESS_DENIED");
			default -> FALLBACK_CODES_BY_STATUS.getOrDefault(statusCode, List.of());
		};
	}

	private List<String> extractErrorCodes(String description) {
		if (description == null || description.isBlank()) {
			return List.of();
		}
		List<String> codes = new ArrayList<>();
		Matcher matcher = ERROR_CODE_IN_DESCRIPTION.matcher(description);
		while (matcher.find()) {
			String code = matcher.group();
			if (!codes.contains(code)) {
				codes.add(code);
			}
		}
		return codes;
	}

	private Example exampleFor(String code) {
		String message = ERROR_CODE_CATALOG.getOrDefault(code, "요청을 처리할 수 없습니다.");
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("code", code);
		value.put("message", message);
		return new Example().summary(code).value(value);
	}

	private boolean isError(String statusCode) {
		return statusCode.length() == 3
				&& (statusCode.charAt(0) == '4' || statusCode.charAt(0) == '5');
	}

	private boolean isPublicPath(String path) {
		return PUBLIC_PATHS.stream().anyMatch(pattern -> pattern.matcher(path).matches());
	}
}
