package com.bigproject.backend.global.config;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import com.bigproject.backend.domain.assessment.domain.AssessmentValidityErrorCode;
import com.bigproject.backend.domain.analytics.domain.AnalyticsErrorCode;
import com.bigproject.backend.domain.assessment.domain.SessionErrorCode;
import com.bigproject.backend.domain.auth.domain.AuthErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.member.domain.MemberErrorCode;
import com.bigproject.backend.domain.notification.domain.NotificationErrorCode;
import com.bigproject.backend.domain.organization.domain.OrganizationErrorCode;
import com.bigproject.backend.domain.projectexecution.domain.ProjectExecutionErrorCode;
import com.bigproject.backend.domain.reporting.domain.ReportErrorCode;
import com.bigproject.backend.global.exception.ApiErrorCode;
import com.bigproject.backend.global.exception.ErrorResponse;
import com.bigproject.backend.global.security.ManagerViewAccessErrorCode;
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
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

	/**
	 * 도메인 에러 코드 카탈로그. 새 도메인이 {@link ApiErrorCode} enum을 만들면 여기 등록한다.
	 *
	 * <p>값이 메시지 문자열이 아니라 코드 자체인 이유는 예시 본문에 <b>상태까지</b> 넣기 때문이다.
	 * 코드가 자기 상태를 들고 있으므로({@link ApiErrorCode#status()}) 응답 키의 상태와 어긋나는
	 * 코드가 섞이면 예시가 그 사실을 드러낸다.
	 */
	private static final Map<String, ApiErrorCode> ERROR_CODE_CATALOG = Stream.<ApiErrorCode[]>of(
					OrganizationErrorCode.values(), ReportErrorCode.values(), AuthErrorCode.values(),
					AcademicOperationsErrorCode.values(), MemberErrorCode.values(), AnalyticsErrorCode.values(),
					ProjectExecutionErrorCode.values(), CurriculumErrorCode.values(),
					ManagerViewAccessErrorCode.values(), NotificationErrorCode.values(),
					AssessmentValidityErrorCode.values(), SessionErrorCode.values(),
					SubmissionErrorCode.values())
			.flatMap(Arrays::stream)
			.collect(LinkedHashMap::new, (map, code) -> map.put(code.name(), code), Map::putAll);

	/** 예시 본문의 고정 시각. 실제 값은 요청 시각이며 여기서는 모양만 보인다. */
	private static final String EXAMPLE_TIMESTAMP = "2026-08-15T04:21:33.512Z";

	/** 유일하게 {@code fieldErrors}를 함께 싣는 코드({@code GlobalExceptionHandler}). */
	private static final String VALIDATION_FAILED_CODE = "VALIDATION_FAILED";

	/**
	 * 시큐리티 필터가 쓰는 {@code error} 문구. 상태 이름({@code UNAUTHORIZED}·{@code FORBIDDEN})이
	 * 아니라 {@code SecurityConfig.writeSecurityError}에 박힌 값이라 따로 적어 둔다.
	 */
	private static final Map<String, String> SECURITY_ERROR_LABELS = Map.of(
			"UNAUTHENTICATED", "Unauthenticated",
			"ACCESS_DENIED", "Access Denied");

	/** 카탈로그에 없는 코드(시큐리티 필터·GlobalExceptionHandler가 직접 만드는 값)의 기본 문구. */
	private static final Map<String, String> FALLBACK_MESSAGES = Map.of(
			"UNAUTHENTICATED", "로그인이 필요합니다.",
			"ACCESS_DENIED", "접근 권한이 없습니다.",
			"VALIDATION_FAILED", "요청 값이 올바르지 않습니다.",
			"NOT_FOUND", "요청한 경로를 찾을 수 없습니다.",
			"DATA_INTEGRITY_VIOLATION", "요청을 처리할 수 없습니다. 데이터 제약 조건에 맞지 않습니다.");

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

	/**
	 * 설명에 적힌 오류 표의 한 행. {@code | `CODE` | 404 | 언제 |} 처럼 <b>코드와 상태가 나란히 있는
	 * 표 행</b>만 잡는다 — 문장 속에서 코드 이름이 지나가는 것은 그 오퍼레이션의 오류가 아닐 수 있다.
	 */
	private static final Pattern ERROR_TABLE_ROW =
			Pattern.compile("^\\|\\s*`?([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)`?\\s*\\|\\s*(\\d{3})\\s*\\|",
					Pattern.MULTILINE);

	/**
	 * 토큰이 필요한 경로가 <b>언제나</b> 낼 수 있는 응답. 시큐리티 필터가 컨트롤러보다 먼저 답하므로
	 * 어느 오퍼레이션의 오류 표에도 적혀 있지 않지만, 실제로는 가장 자주 나가는 실패다.
	 */
	private static final Map<String, String> SECURITY_FAILURES = new LinkedHashMap<>(Map.of(
			"401", "UNAUTHENTICATED — 토큰이 없거나 만료됐다",
			"403", "ACCESS_DENIED — 이 역할로는 부를 수 없다"));

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
	 *   <li><b>숫자 필드의 값 목록이 문자열로 나간다.</b> {@code @Schema(allowableValues)}는 String[]이라
	 *       {@code int} 필드에 붙여도 {@code "type": "integer"} 옆에 {@code "enum": ["90","180","365"]}이
	 *       실린다 — 타입과 값이 서로 다른 말을 하고, 생성기는 값 쪽을 믿어
	 *       {@code '90' | '180' | '365'}를 만들어 호출부마다 캐스팅을 강요한다. 숫자로 되돌린다.</li>
	 * </ol>
	 *
	 * <p>컨트롤러마다 애너테이션을 99번 적지 않고 여기서 한 번에 거는 이유는, 응답 하나를 빠뜨려도
	 * 아무도 모르기 때문이다. 문서화되지 않은 4xx가 생기면 그건 스펙이 아니라 코드의 문제다.
	 */
	@Bean
	public OpenApiCustomizer izGetOpenApiCustomizer() {
		return openApi -> {
			registerErrorSchemas(openApi);
			flattenEmptyParents(openApi);
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
					ensureErrorResponses(operation, publicPath);
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

	/**
	 * <b>내용이 없는 부모 스키마를 {@code allOf}에서 걷어낸다.</b>
	 *
	 * <p>{@code oneOf}로 두 갈래를 나누려면 Java 쪽에 공통 상위 타입이 하나 필요하다
	 * ({@code MySubmissionResponse.SubmissionContent}가 그렇다). 그런데 swagger-core는 그 상위 타입을
	 * <b>부모 스키마로 등록하고</b> 자식마다 {@code allOf: [$ref(부모), {실제 속성}]}을 만든다. 상위 타입이
	 * 값을 하나도 갖지 않는 마커라면 이 부모는 {@code {}} — 아무 말도 하지 않는 스키마다.
	 *
	 * <p>그 빈 스키마가 남으면 두 가지가 나빠진다. 생성기가 <b>쓸모없는 타입을 하나 더 만들고</b>,
	 * 무엇보다 "속성도 {@code required}도 없는 객체"라 스펙 검사에서 <b>새 위반으로 잡힌다</b> —
	 * {@code required}를 채우려고 타입을 나눴는데 그 과정에서 같은 위반을 하나 만드는 셈이다(23차 R1).
	 *
	 * <p>{@code @Schema(hidden = true)}로는 지워지지 않는다. 자식의 합성은 부모 애너테이션이 아니라
	 * <b>타입 계층</b>에서 나오기 때문이다. 그래서 스펙 단계에서 정리한다 — 병합 대상은
	 * <b>정말로 비어 있는</b>(속성·required·enum·타입·조합이 모두 없는) 부모뿐이라, 값을 가진 상속
	 * 구조는 건드리지 않는다.
	 */
	private void flattenEmptyParents(OpenAPI openApi) {
		if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
			return;
		}
		Map<String, Schema> schemas = openApi.getComponents().getSchemas();

		Set<String> emptyParents = schemas.entrySet().stream()
				.filter(entry -> isEmptySchema(entry.getValue()))
				.map(Map.Entry::getKey)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (emptyParents.isEmpty()) {
			return;
		}

		schemas.values().forEach(schema -> dropEmptyParents(schema, emptyParents));
		// 참조가 모두 사라진 뒤에만 지운다. 다른 곳에서 아직 가리키고 있으면 깨진 $ref가 된다.
		emptyParents.stream()
				.filter(name -> !isReferenced(schemas, name))
				.forEach(schemas::remove);
	}

	private boolean isEmptySchema(Schema<?> schema) {
		return schema != null
				&& schema.getProperties() == null
				&& schema.getRequired() == null
				&& schema.getEnum() == null
				&& schema.getType() == null
				&& schema.getTypes() == null
				&& schema.getAllOf() == null
				&& schema.getOneOf() == null
				&& schema.getAnyOf() == null
				&& schema.get$ref() == null
				&& schema.getItems() == null
				&& schema.getAdditionalProperties() == null;
	}

	/**
	 * 빈 부모를 뺀 뒤 {@code allOf}에 하나만 남으면 그것을 자기 자신에 펼친다. 남은 하나가 곧 그 타입의
	 * 실제 정의이므로, {@code allOf} 껍데기를 유지할 이유가 없다.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void dropEmptyParents(Schema schema, Set<String> emptyParents) {
		List<Schema> members = schema.getAllOf();
		if (members == null) {
			return;
		}
		List<Schema> kept = new ArrayList<>(members.stream()
				.filter(member -> member.get$ref() == null
						|| !emptyParents.contains(refName(member.get$ref())))
				.toList());
		if (kept.size() == members.size()) {
			return;
		}
		if (kept.size() != 1) {
			schema.setAllOf(kept.isEmpty() ? null : kept);
			return;
		}

		Schema<?> only = kept.get(0);
		schema.setAllOf(null);
		if (only.get$ref() != null) {
			schema.set$ref(only.get$ref());
			return;
		}
		schema.setType(only.getType());
		schema.setTypes(only.getTypes());
		schema.setProperties(only.getProperties());
		if (only.getRequired() != null) {
			only.getRequired().forEach(schema::addRequiredItem);
		}
	}

	private boolean isReferenced(Map<String, Schema> schemas, String name) {
		String ref = Components.COMPONENTS_SCHEMAS_REF + name;
		return schemas.values().stream().anyMatch(schema -> containsRef(schema, ref));
	}

	private boolean containsRef(Schema<?> schema, String ref) {
		if (schema == null) {
			return false;
		}
		if (ref.equals(schema.get$ref())) {
			return true;
		}
		return Stream.of(schema.getAllOf(), schema.getOneOf(), schema.getAnyOf())
				.filter(Objects::nonNull)
				.flatMap(List::stream)
				.anyMatch(member -> containsRef(member, ref))
				|| (schema.getProperties() != null
						&& schema.getProperties().values().stream()
								.anyMatch(property -> containsRef((Schema<?>) property, ref)))
				|| containsRef(schema.getItems(), ref);
	}

	private String refName(String ref) {
		return ref.substring(ref.lastIndexOf('/') + 1);
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
		coerceNumericEnum(schema);
		return toNullableIn31(schema);
	}

	/**
	 * 숫자 타입 스키마의 {@code enum} 값을 숫자로 되돌린다.
	 *
	 * <p>{@code @Schema(allowableValues = {"90", "180", "365"})}는 애너테이션 문법상 String[]밖에 될 수 없어,
	 * {@code int dataRetentionDays}에 붙여도 문자열 세 개가 그대로 스펙에 실린다. 그러면 한 스키마 안에서
	 * {@code type}은 정수라 하고 {@code enum}은 문자열이라 하는 상태가 되고, 생성기는 {@code enum} 쪽을 믿는다 —
	 * 프론트 타입이 {@code '90' | '180' | '365'}로 나와 숫자를 보내는 호출부마다 캐스팅이 한 줄씩 붙었다.
	 *
	 * <p>고치는 자리를 여기로 잡은 이유는, DTO에 손을 대면 <b>와이어 포맷이 바뀌기 때문</b>이다.
	 * 필드를 enum으로 올리면 JSON이 문자열로 바뀌어 역직렬화가 깨진다({@code AllowedRetentionDays} 주석 참고).
	 * 서버가 받는 형식은 그대로 두고 <b>문서만</b> 사실에 맞춘다. 특정 필드를 이름으로 집지 않으므로
	 * 같은 실수가 다음 DTO에서 반복돼도 자동으로 걸린다.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private void coerceNumericEnum(Schema schema) {
		List<?> values = schema.getEnum();
		if (values == null || values.isEmpty() || !isNumericType(schema)) {
			return;
		}
		boolean integral = hasType(schema, "integer");
		List<Object> converted = new ArrayList<>();
		for (Object value : values) {
			converted.add(value instanceof String text ? parseNumber(text, integral) : value);
		}
		schema.setEnum(converted);
	}

	/** 파싱에 실패하면 원문을 그대로 둔다 — 문서를 고치려다 값을 잃는 것이 더 나쁘다. */
	private Object parseNumber(String text, boolean integral) {
		try {
			return integral ? Long.valueOf(text.trim()) : new BigDecimal(text.trim());
		} catch (NumberFormatException exception) {
			return text;
		}
	}

	private boolean isNumericType(Schema schema) {
		return hasType(schema, "integer") || hasType(schema, "number");
	}

	private boolean hasType(Schema schema, String type) {
		return type.equals(schema.getType())
				|| (schema.getTypes() != null && schema.getTypes().contains(type));
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

	/**
	 * <b>설명에만 적혀 있던 오류를 실제 응답 정의로 올린다.</b>
	 *
	 * <p>{@code @ApiResponse}를 적지 않은 오퍼레이션은 성공 하나만 선언된 채로 나간다. 사람이 읽는
	 * 설명에는 오류 표가 있어도 <b>기계는 그것을 읽지 않으므로</b>, 프론트 생성기가 화면에서 쓸 에러
	 * 코드 상수를 만들지 못한다. 실제로 그래서 재시도 안내를 붙일 근거가 없다는 요청이 왔다
	 * (23차 R4 — {@code AI_SERVER_UNAVAILABLE}이 설명문에만 있었다).
	 *
	 * <p>두 가지를 채운다.
	 * <ol>
	 *   <li><b>설명의 오류 표</b> — {@code | `CODE` | 404 | 언제 |} 모양의 행만 읽는다. 표 밖에서
	 *       코드 이름이 지나가는 문장은 대상이 아니다. 상태별로 묶어 응답 하나를 만든다</li>
	 *   <li><b>인증·인가</b> — 토큰이 필요한 경로는 <b>언제나</b> 401·403을 낼 수 있다. 컨트롤러에
	 *       닿기도 전에 시큐리티 필터가 답하는 것이라 어느 오퍼레이션에도 표로 적혀 있지 않다</li>
	 * </ol>
	 *
	 * <p>이미 선언된 상태는 건드리지 않는다 — 손으로 적은 설명이 더 정확하다.
	 */
	private void ensureErrorResponses(Operation operation, boolean publicPath) {
		ApiResponses responses = operation.getResponses();
		if (responses == null) {
			return;
		}
		errorTableCodes(operation.getDescription()).forEach((status, codes) -> {
			if (responses.get(status) == null) {
				responses.addApiResponse(status, new ApiResponse().description(String.join(" · ", codes)));
			}
		});
		if (!publicPath) {
			SECURITY_FAILURES.forEach((status, description) -> {
				if (responses.get(status) == null) {
					responses.addApiResponse(status, new ApiResponse().description(description));
				}
			});
		}
	}

	/**
	 * 설명에 적힌 오류 표를 상태별 코드 목록으로 바꾼다.
	 *
	 * <p>표 행만 읽는 이유는 본문이 코드 이름을 <b>설명하려고</b> 언급하는 일이 잦기 때문이다 —
	 * "그 실패는 {@code REPO_NOT_FOUND}로 드러난다" 같은 문장까지 응답으로 만들면, 그 오퍼레이션이
	 * 내지 않는 오류가 스펙에 생긴다.
	 */
	private Map<String, List<String>> errorTableCodes(String description) {
		if (description == null || description.isBlank()) {
			return Map.of();
		}
		Map<String, List<String>> byStatus = new LinkedHashMap<>();
		Matcher matcher = ERROR_TABLE_ROW.matcher(description);
		while (matcher.find()) {
			String status = matcher.group(2);
			if (!isError(status)) {
				continue;
			}
			List<String> codes = byStatus.computeIfAbsent(status, key -> new ArrayList<>());
			if (!codes.contains(matcher.group(1))) {
				codes.add(matcher.group(1));
			}
		}
		return byStatus;
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
					.forEach(code -> mediaType.addExamples(code, exampleFor(statusCode, code)));
			response.setContent(new Content().addMediaType(JSON, mediaType));
		});
	}

	/**
	 * 이 응답이 낼 수 있는 에러 코드. 설명에 적힌 도메인 코드를 우선하고, 없으면 런타임이 실제로
	 * 내려보내는 기본값을 쓴다(인증·인가 실패는 시큐리티 필터가 고정 코드로 쓴다).
	 */
	private List<String> errorCodesFor(String statusCode, ApiResponse response, boolean publicPath) {
		List<String> declared = new ArrayList<>(extractErrorCodes(response.getDescription()));

		// 인증·인가 실패는 컨트롤러에 닿기도 전에 시큐리티 필터가 고정 코드로 답한다. 그 코드는
		// 밑줄이 없어(UNAUTHENTICATED) 설명에서 뽑히지 않으므로, 도메인 코드를 하나라도 적어 두면
		// 조용히 사라진다 — 실제로는 그쪽이 훨씬 자주 나가는 응답인데도 그렇다. 그래서 더한다.
		// 공개 경로는 대상이 아니다. 필터가 막지 않으므로 401·403은 전부 도메인이 내는 것이다.
		if (!publicPath) {
			String securityCode = switch (statusCode) {
				case "401" -> "UNAUTHENTICATED";
				case "403" -> "ACCESS_DENIED";
				default -> null;
			};
			if (securityCode != null && !declared.contains(securityCode)) {
				declared.add(securityCode);
			}
		}

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

	/**
	 * 오류 코드 하나의 <b>응답 본문 전체</b>를 예시로 만든다.
	 *
	 * <p>종전에는 {@code code}·{@code message} 둘만 실었다. 스키마가 다섯 필드를 필수로 선언하고
	 * 있는데 예시가 둘뿐이면, 화면 개발자가 예시를 그대로 목 응답으로 복사했을 때 {@code status}로
	 * 분기하는 공통 처리(재시도·토큰 갱신)가 조용히 빗나간다. 실제 서버가 내려보내는 모양 그대로 적는다.
	 *
	 * <p>{@code status}는 응답 키에서 온다 — 카탈로그의 코드가 들고 있는 상태와 응답 키가 어긋나면
	 * 사실인 쪽은 <b>키</b>다(그 자리에 그 코드가 실제로 실려 나간다). 다만 그런 조합은 설명의 오류
	 * 표가 틀렸다는 뜻이므로 문서를 고쳐야 한다.
	 */
	private Example exampleFor(String statusCode, String code) {
		ApiErrorCode catalogEntry = ERROR_CODE_CATALOG.get(code);
		int status = Integer.parseInt(statusCode);
		HttpStatus resolved = HttpStatus.resolve(status);

		Map<String, Object> value = new LinkedHashMap<>();
		value.put("timestamp", EXAMPLE_TIMESTAMP);
		value.put("status", status);
		// 시큐리티 필터의 401·403만 상태 이름이 아닌 고정 문구를 쓴다(SecurityConfig.writeSecurityError).
		value.put("error", SECURITY_ERROR_LABELS.getOrDefault(code,
				resolved == null ? "ERROR" : resolved.name()));
		value.put("code", code);
		value.put("message", catalogEntry == null
				? FALLBACK_MESSAGES.getOrDefault(code, "요청을 처리할 수 없습니다.")
				: catalogEntry.defaultMessage());
		// VALIDATION_FAILED만 몸이 한 겹 더 있다. 이 키가 있고 없고가 화면 처리를 가른다 —
		// 입력칸 옆에 사유를 붙일 수 있는 유일한 400이라, 예시에서 빠지면 그 사실이 보이지 않는다.
		if (VALIDATION_FAILED_CODE.equals(code)) {
			value.put("fieldErrors", List.of(Map.of(
					"field", "answerText", "code", "NOT_BLANK", "message", "must not be blank")));
		}
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
