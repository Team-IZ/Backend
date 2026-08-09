package com.bigproject.backend.global.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import org.springframework.stereotype.Component;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 응답 record의 <b>모든 컴포넌트를 {@code required}로</b> 표시한다.
 *
 * <p>표시하지 않으면 생성기가 전부 optional로 만든다 — {@code organizationId?} 처럼. 화면 코드는
 * 반드시 오는 값에도 매번 {@code ?.} 로 방어하거나 {@code !} 를 붙이게 되고, 후자는 타입 검사를
 * 스스로 끄는 것이라 더 나쁘다.
 *
 * <p><b>추측이 아니라 직렬화의 사실이다.</b> Java record를 Jackson이 그대로 직렬화하면
 * 모든 컴포넌트가 JSON 키로 나간다 — 값이 {@code null}이어도 키는 있다. 키가 빠지는 유일한 경우가
 * {@link JsonInclude}이고, 그건 아래에서 걸러 낸다.
 *
 * <p>"값이 null일 수 있다"는 별개의 문제이며 필드마다 {@code @Schema(nullable = true)}로 표시한다.
 * {@code required}(키가 있다) + {@code nullable}(값이 null일 수 있다)이 함께 붙어야
 * 생성 타입이 {@code slug: string | null}이 되어 컴파일러가 null 검사를 요구한다.
 *
 * <p>요청 DTO는 대상이 아니다. 요청에서 {@code required}는 "클라이언트가 반드시 보내야 한다"는
 * 뜻이라 record 구조가 아니라 Bean Validation(@NotNull·@NotBlank)이 정하며, springdoc이 그걸 읽어 채운다.
 */
@Component
public class ResponseRecordRequiredConverter implements ModelConverter {

	/** 이 값들이 붙으면 키 자체가 빠질 수 있어 "항상 온다"고 말할 수 없다. */
	private static final Set<JsonInclude.Include> OMITS_KEY = Set.of(
			JsonInclude.Include.NON_NULL,
			JsonInclude.Include.NON_ABSENT,
			JsonInclude.Include.NON_EMPTY,
			JsonInclude.Include.NON_DEFAULT
	);

	@Override
	public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
		Schema<?> resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
		if (resolved == null) {
			return null;
		}

		Class<?> raw = rawClass(type);
		if (raw == null || !raw.isRecord() || !isResponseType(raw) || omitsKeys(raw)) {
			return resolved;
		}

		// 스키마가 컴포넌트로 등록됐으면 resolved는 $ref만 들고 있다. 실제 스키마를 찾아 고친다.
		Schema<?> target = resolveTarget(resolved, context);
		if (target == null || target.getProperties() == null) {
			return resolved;
		}

		List<String> required = new ArrayList<>();
		for (RecordComponent component : raw.getRecordComponents()) {
			if (target.getProperties().containsKey(component.getName()) && !omitsKey(component)) {
				required.add(component.getName());
			}
		}
		required.forEach(target::addRequiredItem);
		return resolved;
	}

	private Schema<?> resolveTarget(Schema<?> resolved, ModelConverterContext context) {
		if (resolved.get$ref() == null) {
			return resolved;
		}
		String name = resolved.get$ref().substring(resolved.get$ref().lastIndexOf('/') + 1);
		return context.getDefinedModels().get(name);
	}

	private Class<?> rawClass(AnnotatedType type) {
		if (type.getType() == null) {
			return null;
		}
		try {
			return Json.mapper().constructType(type.getType()).getRawClass();
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	/**
	 * 응답 DTO 판정. 이 프로젝트의 {@code presentation/dto}는 {@code *Request} / {@code *Response}로
	 * 갈리며, 중첩 record는 바깥 타입을 따라간다({@code OrganizationResponse.Operator} 등).
	 */
	private boolean isResponseType(Class<?> raw) {
		for (Class<?> current = raw; current != null; current = current.getEnclosingClass()) {
			if (current.getSimpleName().endsWith("Request")) {
				return false;
			}
			if (current.getSimpleName().endsWith("Response")) {
				return true;
			}
		}
		return false;
	}

	private boolean omitsKeys(Class<?> raw) {
		return omits(raw.getAnnotation(JsonInclude.class));
	}

	/**
	 * record 컴포넌트에 적은 {@code @JsonInclude}는 <b>컴포넌트 자신에는 남지 않는다</b> —
	 * {@link JsonInclude}의 {@code @Target}에 {@code RECORD_COMPONENT}가 없어 필드·접근자·생성자
	 * 파라미터로만 전파된다. Jackson은 그 접근자를 보고 키를 빼므로 여기서도 같은 자리를 봐야 한다.
	 * 컴포넌트만 보면 "키가 빠지는데 required"라고 적힌 스펙이 나간다.
	 */
	private boolean omitsKey(RecordComponent component) {
		return omits(component.getAnnotation(JsonInclude.class))
				|| omits(component.getAccessor().getAnnotation(JsonInclude.class));
	}

	private boolean omits(JsonInclude include) {
		return include != null && OMITS_KEY.contains(include.value());
	}
}
