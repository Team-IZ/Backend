package com.bigproject.backend.global.config;

import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
				.startsWith("기관의 기수 결과 공개 범위 기본값");
		assertThat(schemas.path("DisclosureScope").has("nullable")).isFalse();

		// 여러 곳이 같은 정의를 가리킨다. 값을 복사해 두면 순서만 달라도 사람 눈에는 같아 보여
		// 한쪽에 값이 추가된 것을 아무도 눈치채지 못한다.
		assertThat(schemas.path("OperationSettingResponse").path("properties")
				.path("defaultDisclosureScope").toString())
				.contains("#/components/schemas/DisclosureScope");

		// 🔴 TraineeReleaseStatus는 공개/비공개 폐지(2026-08-19)로 없어진 enum이다.
		// 되살아나면 폐기한 축이 계약에 다시 나타난 것이라 여기서 잡는다.
		assertThat(schemas.has("TraineeReleaseStatus")).isFalse();
	}

	/**
	 * 목록 항목 스키마는 <b>무엇의 항목인지</b> 이름으로 드러나야 한다. {@code Item} 같은 이름은
	 * 전역 이름 공간에서 다음 목록 응답과 부딪히고, 먼저 등록된 쪽이 조용히 덮인다.
	 */
	@Test
	void namesNestedListItemsAfterWhatTheyContain() throws Exception {
		JsonNode schemas = spec().path("components").path("schemas");

		assertThat(schemas.has("Item")).isFalse();
		assertThat(schemas.has("RoundReportResponse")).isTrue();
		// 항상 오는 필드가 required로 나가야 화면 타입이 `?`·`!` 없이 쓰인다.
		// 상태에 따라 키가 빠지는 것들은 그 반대다 — required로 적으면 스펙이 거짓말을 한다.
		assertThat(schemas.path("RoundReportResponse").path("required").toString())
				.contains("id", "label", "status")
				.doesNotContain("reportId", "publishAfter", "publishedAt", "curriculum",
						"completionStatus", "retryState");
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

	/**
	 * 부분 수정 오퍼레이션은 <b>메서드와 스키마가 함께</b> 부분 수정이어야 한다. 필드만 선택으로 바꾸고
	 * {@code PUT}으로 두면 "전체 치환"이라는 규약과 실제 동작이 어긋난 채 남는다.
	 *
	 * <p>{@code required}가 하나라도 남으면 항목별 모달이 그 필드를 매번 실어 보내야 하고,
	 * 그 순간 다른 사람이 바꾼 값을 되돌리는 경로가 다시 열린다.
	 */
	@Test
	void exposesTheOperationSettingsUpdateAsAPartialUpdate() throws Exception {
		JsonNode settings = spec().path("paths")
				.path("/api/v0/organizations/{organizationId}/operations/settings");

		assertThat(settings.has("patch")).isTrue();
		assertThat(settings.has("put")).isFalse();

		JsonNode request = spec().path("components").path("schemas").path("UpdateOperationSettingRequest");
		assertThat(request.has("required")).isFalse();

		// 상한 두 개는 null이 "무제한"이라는 값이라 타입에 null이 들어가야 한다.
		// 래퍼로 받고 있지만 그 래퍼가 스펙으로 새면 생성기가 객체를 만들어 낸다.
		assertThat(request.path("properties").path("monthlyTokenLimit").path("type").toString())
				.isEqualTo("[\"integer\",\"null\"]");
		assertThat(spec().path("components").path("schemas").has("PatchField")).isFalse();

		// @AssertTrue 검사 메서드가 요청 필드로 새 나가면 화면이 보내야 할 값으로 읽는다.
		assertThat(request.path("properties").propertyNames())
				.doesNotContain("notEmpty", "mutableOrganizationStatus",
						"positiveMonthlyTokenLimit", "positiveOrZeroStorageLimit");
	}

	/**
	 * 스키마 이름은 <b>중첩 record의 홑이름</b>으로 정해진다. 두 응답이 같은 이름을 쓰면 한쪽이 다른
	 * 쪽을 덮고, <b>덮인 쪽은 스펙에서 그냥 사라진다</b> — 서버는 필드를 다 내려주는데 스펙에는 없어서
	 * 스펙으로 타입을 만드는 화면은 그 값을 쓸 수 없다. 실제로 오퍼레이터 목록이 3필드로 잘려 나갔다.
	 */
	@Test
	void keepsListItemsAndSummariesAsSeparateSchemas() throws Exception {
		JsonNode schemas = spec().path("components").path("schemas");

		// 요약(기관 상세의 operators[])은 이름·이메일이면 충분하다.
		assertThat(schemas.path("Operator").path("properties").propertyNames())
				.containsExactlyInAnyOrder("memberId", "name", "email");

		// 목록(오퍼레이터 탭)은 행별 버튼·배지를 그릴 값이 전부 필요하다.
		assertThat(schemas.path("OperatorListItem").path("properties").propertyNames())
				.containsExactlyInAnyOrder("memberId", "name", "email", "status", "invitedAt",
						"lastLoginAt", "pendingInvitationTokenId", "suspendable", "invitationDeliveryFailed");

		// 상태는 공용 정의를 가리킨다(인라인으로 값을 다시 적으면 같은 개념이 두 타입으로 갈린다).
		assertThat(schemas.path("OperatorListItem").path("properties").path("status").path("$ref").asString())
				.isEqualTo("#/components/schemas/OperatorAccountStatus");
	}

	/** 값을 넣어야 하는 쿼리 파라미터는 <b>파라미터 자체에</b> 형식이 적혀 있어야 한다. */
	@Test
	void describesTheFormatOfFreeFormQueryParameters() throws Exception {
		JsonNode parameters = spec().path("paths")
				.path("/api/v0/organizations/{organizationId}/operations/usage")
				.path("get").path("parameters");

		JsonNode period = null;
		for (JsonNode parameter : parameters) {
			if ("period".equals(parameter.path("name").asString(null))) {
				period = parameter;
			}
		}

		assertThat(period).isNotNull();
		// 설명 본문에만 적어 두면 파라미터 스키마는 그냥 string이라 화면이 무엇을 넣을지 알 수 없다.
		assertThat(period.path("description").asString("")).contains("yyyy-MM");
		assertThat(period.path("example").asString(null)).isEqualTo("2026-07");
	}

	/** 기획에서 빠진 기능은 스펙에도 남으면 안 된다 — 화면이 쓰지 않는 값이 생성 타입에 계속 남는다. */
	@Test
	void dropsTheSettingThatLeftThePlan() throws Exception {
		JsonNode schemas = spec().path("components").path("schemas");

		assertThat(schemas.path("UpdateOperationSettingRequest").path("properties").propertyNames())
				.doesNotContain("enableBigProjectContributionAnalysis");
		assertThat(schemas.path("OperationSettingResponse").path("properties").propertyNames())
				.doesNotContain("enableBigProjectContributionAnalysis");
	}

	/**
	 * 9차 R5 — 매니저 ID가 나오는 세 자리가 <b>실제로 나가는 스펙</b>에서도 같은 타입인지 본다.
	 * 단위 테스트는 변환기 하나만 보므로, 두 응답이 같은 키를 놓고 충돌하는 문제는 여기서만 잡힌다.
	 */
	@Test
	void usesOneManagerIdentifierTypeAcrossTheRoundTrip() throws Exception {
		JsonNode schemas = spec().path("components").path("schemas");

		JsonNode memberId = schemas.path("Manager").path("properties").path("memberId");
		assertThat(memberId.path("type").asString()).isEqualTo("string");
		assertThat(memberId.path("format").asString()).isEqualTo("uuid");

		// 읽는 자리(목록)와 보내는 자리(전체 교체)도 같은 타입이어야 왕복이 성립한다.
		JsonNode rosterId = schemas.path("ManagerRosterEntry").path("properties").path("managerId");
		assertThat(rosterId.path("format").asString()).isEqualTo("uuid");
		assertThat(schemas.path("UpdateClassroomManagersRequest").path("properties").path("managerIds")
				.path("items").path("format").asString()).isEqualTo("uuid");

		// 이름만으로는 동명이인을 가를 수 없다.
		assertThat(schemas.path("Manager").path("properties").propertyNames())
				.containsExactlyInAnyOrder("memberId", "name", "email");
	}

	/** 9차 R7 — 목록이 상태를 보여만 주고 아무것도 못 하던 상태를 끝낸다. 조작 셋이 스펙에 있어야 한다. */
	@Test
	void offersTheSameAccountActionsForManagersAsForOperators() throws Exception {
		JsonNode paths = spec().path("paths");

		assertThat(paths.path("/api/v0/members/organizations/{organizationId}/managers/{managerId}/status")
				.has("patch")).isTrue();
		assertThat(paths.path("/api/v0/members/organizations/{organizationId}/manager-invitations/{tokenId}/resend")
				.has("post")).isTrue();
		assertThat(paths.path("/api/v0/members/organizations/{organizationId}/manager-invitations/{tokenId}")
				.has("delete")).isTrue();

		// 버튼을 켜고 끄는 두 값이 목록에 없으면 화면이 조작을 걸 대상을 모른다.
		assertThat(spec().path("components").path("schemas").path("ManagerRosterEntry").path("properties")
				.propertyNames()).contains("pendingInvitationTokenId", "suspendable");
	}

	/** 9차 R1 — 저장한 것을 되읽는 자리가 상세 응답 하나에 모여 있어야 화면 진입 조회가 1건으로 끝난다. */
	@Test
	void readsBackWhatWasSavedOnTheProjectItself() throws Exception {
		JsonNode detailRef = spec().path("paths").path("/api/v0/projects/{projectId}").path("get")
				.path("responses").path("200").path("content").path("application/json").path("schema").path("$ref");
		assertThat(detailRef.asString()).isEqualTo("#/components/schemas/ProjectDetailResponse");

		JsonNode detail = spec().path("components").path("schemas").path("ProjectDetailResponse").path("properties");
		assertThat(detail.propertyNames()).contains("curricula", "concepts", "requirementTitles");

		// 목록은 회차마다 조회를 부를 수 없으므로 셀에 그릴 숫자 셋을 항목에 싣는다.
		assertThat(spec().path("components").path("schemas").path("ProjectResponse").path("properties")
				.propertyNames()).contains("curriculumCount", "conceptCount", "conceptCandidateCount");
	}

	/** 9차 R6 — 만들 때 필수로 받는 값은 되읽을 수 있어야 하고, 만든 것은 고치고 지울 수 있어야 한다. */
	@Test
	void letsAClassroomBeEditedDeletedAndReadBackWithItsCapacity() throws Exception {
		JsonNode classroom = spec().path("paths")
				.path("/api/v0/cohorts/{cohortId}/classrooms/{classroomId}");

		assertThat(classroom.has("patch")).isTrue();
		assertThat(classroom.has("delete")).isTrue();

		// 정원을 넣으라고 해 놓고 돌려주지 않으면 화면이 보낸 값을 기억하는 수밖에 없다.
		assertThat(spec().path("components").path("schemas").path("ClassroomResponse").path("properties")
				.propertyNames()).contains("capacity");

		// 부분 수정이라 required가 있으면 안 된다 — 안 바꾸는 값을 매번 실어 보내게 된다.
		assertThat(spec().path("components").path("schemas").path("UpdateClassroomRequest").has("required")).isFalse();
	}

	/** 9차 R3 — 상태별 개수는 필터와 무관한 모집단이라 걸러진 배열에서는 만들 수 없다. */
	@Test
	void countsProjectsByStatusOutsideTheFilteredList() throws Exception {
		JsonNode operation = spec().path("paths").path("/api/v0/cohorts/{cohortId}/projects").path("get");

		List<String> parameterNames = new ArrayList<>();
		operation.path("parameters").forEach(parameter -> parameterNames.add(parameter.path("name").asString(null)));
		assertThat(parameterNames).contains("search", "curriculumId", "status", "sort");

		assertThat(operation.path("responses").path("200").path("content").path("application/json")
				.path("schema").path("$ref").asString())
				.isEqualTo("#/components/schemas/ProjectListResponse");
		assertThat(spec().path("components").path("schemas").path("ProjectListResponse").path("properties")
				.propertyNames()).contains("projects", "total", "counts");
	}

	/** 9차 R4 — 붙이기만 되고 뗄 수 없으면 한 번 잘못 붙인 것을 되돌릴 수 없다. */
	@Test
	void allowsUndoingWhatCanBeCreated() throws Exception {
		assertThat(spec().path("paths").path("/api/v0/projects/{projectId}").has("delete")).isTrue();
		assertThat(spec().path("paths")
				.path("/api/v0/projects/{projectId}/curricula/{projectCurriculumId}").has("delete")).isTrue();
	}

	/** 9차 R8 — 올리는 것은 되는데 올린 목록을 볼 수 없던 상태를 끝낸다. */
	@Test
	void listsAndOpensCurriculaAtTheOrganizationLevel() throws Exception {
		assertThat(spec().path("paths")
				.path("/api/v0/organizations/{organizationId}/curricula").has("get")).isTrue();
		assertThat(spec().path("paths").path("/api/v0/curricula/{materialId}").has("get")).isTrue();

		JsonNode item = spec().path("components").path("schemas").path("CurriculumCatalogItem").path("properties");
		assertThat(item.propertyNames()).contains(
				"materialId", "originalFileName", "versionNo", "analysisStatus",
				"sectionCount", "conceptCount", "usedProjectCount", "uploadedAt", "uploadedByName");

		// 한 번도 분석하지 않은 교안은 상태가 null이다 — 실패와 구분해야 해서 타입에 null이 들어가야 한다.
		assertThat(item.path("analysisStatus").path("oneOf").toString())
				.contains("#/components/schemas/CurriculumAnalysisStatus")
				.contains("\"null\"");
	}

	/**
	 * 9차 Q1 ⓐ — 준비 상태를 서버가 판정한다. {@code status}에 값을 더하지 않고 <b>축을 나눴다</b>:
	 * 시간이 정하는 것과 구성이 정하는 것을 한 필드에 합치면 "진행 중인데 교안이 비었다"를 표현할 수 없다.
	 */
	@Test
	void judgesReadinessOnTheServerWithoutOverloadingStatus() throws Exception {
		JsonNode schemas = spec().path("components").path("schemas");

		assertThat(schemas.path("ProjectResponse").path("properties").path("readiness").path("$ref").asString())
				.isEqualTo("#/components/schemas/ProjectReadiness");
		assertThat(schemas.path("ProjectDetailResponse").path("properties").path("readiness").path("$ref").asString())
				.isEqualTo("#/components/schemas/ProjectReadiness");

		assertThat(schemas.path("ProjectReadiness").path("enum").toString()).contains("PREP", "READY");
		// status는 시간 축 그대로다 — 준비 값이 섞이면 두 질문에 한 필드가 답하게 된다.
		assertThat(schemas.path("ProjectStatus").path("enum").toString())
				.contains("PLANNED", "RUNNING", "CLOSED")
				.doesNotContain("PREP", "READY");
	}

	/** 9차 Q3-② — 안 오는 값이 타입에 있으면 화면이 도달할 수 없는 분기를 들고 있게 된다. */
	@Test
	void dropsTheAccountStatusThatTheDatabaseCannotProduce() throws Exception {
		assertThat(spec().path("components").path("schemas").path("AccountStatus").path("enum").toString())
				.contains("INVITED", "ACTIVE", "INACTIVE")
				.doesNotContain("LOCKED");
	}

	/** 9차 Q3-① · Q3-③ · Q3-④ — 확인 항목에서 나온 세 경로가 스펙에 있는지. */
	@Test
	void answersTheRemainingOperationalGaps() throws Exception {
		JsonNode paths = spec().path("paths");

		// ① 매니저 기준으로 담당 반을 한 번에 저장한다(반마다 나눠 부르면 절반만 반영될 수 있다).
		assertThat(paths.path("/api/v0/members/organizations/{organizationId}/managers/{managerId}/classrooms")
				.has("put")).isTrue();

		// ③ 등록하지 않고 무엇이 걸리는지만 본다.
		assertThat(paths.path("/api/v0/cohorts/{cohortId}/trainees/preview").has("post")).isTrue();
		assertThat(paths.path("/api/v0/cohorts/{cohortId}/trainees/invitations/preview").has("post")).isTrue();

		// ④ GET /organizations/{id}는 슈퍼어드민 전용이라 오퍼레이터가 기관 도메인을 읽을 곳이 없었다.
		assertThat(spec().path("components").path("schemas").path("MemberProfileResponse")
				.path("properties").path("emailDomain").path("type").toString())
				.isEqualTo("[\"string\",\"null\"]");
	}

	/**
	 * 23차 R4 — 오류가 <b>설명문에만</b> 있으면 프론트 생성기가 에러 코드 상수를 만들지 못한다.
	 *
	 * <p>실제로 {@code AI_SERVER_UNAVAILABLE}(503)이 그랬다. 재시도 안내를 띄우려면 그 코드로 갈라야
	 * 하는데 {@code responses}에 없어 다른 503과 구분되지 않았다. 스펙을 받은 시점에 119개 중 37개가
	 * 같은 상태였다 — 오퍼레이션마다 애너테이션을 적어 막을 일이 아니라 여기서 한 번에 잡는다.
	 */
	@Test
	void everyOperationDeclaresAtLeastOneErrorResponse() throws Exception {
		List<String> missing = new ArrayList<>();
		forEachOperation(spec(), (operationId, operation) -> {
			boolean hasError = operation.path("responses").propertyNames().stream()
					.anyMatch(status -> status.startsWith("4") || status.startsWith("5"));
			if (!hasError) {
				missing.add(operationId);
			}
		});

		assertThat(missing)
				.as("오류를 하나도 선언하지 않은 오퍼레이션은 화면이 실패를 갈라낼 근거를 주지 못한다")
				.isEmpty();
	}

	/** 인증이 필요한 경로는 토큰이 없거나 역할이 다르면 컨트롤러에 닿지도 못한다. */
	@Test
	void securedOperationsDeclareTheAuthenticationFailures() throws Exception {
		JsonNode responses = spec().path("paths").path("/api/v0/reports").path("get").path("responses");

		assertThat(responses.has("401")).isTrue();
		assertThat(responses.has("403")).isTrue();
		assertThat(responses.path("401").path("content").path("application/json").path("examples")
				.toString()).contains("UNAUTHENTICATED");
	}

	/**
	 * 23차 R1 — 제출 내용은 <b>수단별로 배타적</b>이라 한 스키마에 담으면 다섯 필드가 전부 선택이 된다.
	 * 그러면 "둘 다 없을 수도 있다"가 타입이 되어 서버가 한쪽을 빠뜨려도 화면이 컴파일된다.
	 *
	 * <p>부모로 쓴 sealed 인터페이스가 빈 스키마로 남지 않는 것도 함께 본다 — 남으면 속성도
	 * {@code required}도 없는 객체가 생겨, {@code required}를 채우려다 같은 위반을 하나 만들게 된다.
	 */
	@Test
	void splitsSubmissionContentIntoTwoRequiredShapes() throws Exception {
		JsonNode schemas = spec().path("components").path("schemas");

		assertThat(schemas.path("GithubSubmissionContent").path("required").toString())
				.contains("repoUrl", "branch");
		assertThat(schemas.path("ZipSubmissionContent").path("required").toString())
				.contains("fileName", "fileSize");
		assertThat(schemas.path("MySubmissionResponse").path("properties").path("content")
				.path("oneOf").toString())
				.contains("GithubSubmissionContent", "ZipSubmissionContent");

		assertThat(schemas.has("SubmissionContent"))
				.as("빈 부모 스키마가 남으면 required 없는 객체가 하나 생긴다")
				.isFalse();
		assertThat(schemas.path("GithubSubmissionContent").has("allOf"))
				.as("빈 부모를 걷어낸 뒤에는 allOf 껍데기를 유지할 이유가 없다")
				.isFalse();
	}

	/**
	 * 22차 R1 — {@code required} + {@code $ref}는 "키도 있고 값도 반드시 객체다"라는 뜻이다.
	 * 그런데 이 둘은 <b>미제출·미분석을 null로 판정하라</b>고 설명에 적혀 있다 — 설명과 타입이
	 * 정반대를 말한다. 생성 타입이 {@code Submission}(non-null)으로 나와 컴파일러가 null 검사를
	 * 요구하지 않고, 미제출 팀에서 {@code .} 접근이 그대로 터진다.
	 *
	 * <p>{@code required}는 그대로 둔다. record를 Jackson이 직렬화하면 값이 null이어도 <b>키는 나가므로</b>
	 * "키는 항상 있다"는 사실이다. 고칠 것은 값 쪽이다 — {@code oneOf: [$ref, null]}.
	 */
	@Test
	void letsTheTeamRowSayThereIsNoSubmissionOrAnalysisYet() throws Exception {
		// 30차 R2① — 이 스키마는 `Team`이었다. 히트맵의 같은 이름 record와 부딪혀 있던 것을
		// `SubmissionStatusTeam`으로 갈랐다(schemaNamesAreUniqueAcrossPresentationDtos).
		JsonNode team = spec().path("components").path("schemas").path("SubmissionStatusTeam");

		// 키는 항상 나간다. required에서 빼면 화면이 "키가 없을 수도 있다"로 읽어 옵셔널 체이닝이 는다.
		assertThat(team.path("required").toString()).contains("submission", "analysis");

		assertThat(team.path("properties").path("submission").path("oneOf").toString())
				.contains("#/components/schemas/Submission")
				.contains("\"null\"");
		assertThat(team.path("properties").path("analysis").path("oneOf").toString())
				.contains("#/components/schemas/Analysis")
				.contains("\"null\"");

		// $ref는 형제 키를 못 쓴다. 참조가 감싸이지 않고 남으면 "null이면서 참조"라는 읽을 수 없는 조합이 된다.
		assertThat(team.path("properties").path("submission").has("$ref")).isFalse();
		assertThat(team.path("properties").path("analysis").has("$ref")).isFalse();
	}

	/**
	 * 22차 R4 — 명단 행의 열 개 필드가 {@code required} + non-nullable로 나가는데 실제로는 null이 온다.
	 *
	 * <p>{@code joinedAt}은 초대 대기 행에 등록일이 없어서, 회차 지표 아홉은
	 * {@code manager_trainee_roster_view} LEFT JOIN이 붙지 않아서 null이다 — <b>둘 다 정상 상태</b>다.
	 * 그런데 선언이 non-null이라 화면이 {@code .slice(0, 10)}을 바로 불렀고 미배정 필터에서 명단이
	 * 통째로 사라졌다. 같은 사고가 아홉 자리 더 남아 있던 것을 함께 닫는다.
	 */
	@Test
	void admitsTheRosterFieldsThatAreNullUntilTheRoundMetricsAttach() throws Exception {
		JsonNode properties = spec().path("components").path("schemas")
				.path("TraineeRosterEntry").path("properties");

		List<String> notNullable = new ArrayList<>();
		for (String field : List.of("joinedAt", "assessmentRoundId", "attemptId", "roundResultStatus",
				"rowAggregationStatus", "matchedRiskTypeCodes", "conceptResultItems",
				"lowStageConceptCount", "expectedConceptCount", "excellentOccurrenceCount")) {
			if (!properties.path(field).path("type").toString().contains("\"null\"")) {
				notNullable.add(field);
			}
		}

		assertThat(notNullable)
				.as("required는 그대로 두되 타입이 null을 허용해야 화면이 「아직 없음」을 그릴 수 있다")
				.isEmpty();

		// 키는 항상 나간다 — 빠지는 것이 아니라 값이 null이다.
		assertThat(spec().path("components").path("schemas").path("TraineeRosterEntry")
				.path("required").toString())
				.contains("joinedAt", "assessmentRoundId", "rowAggregationStatus");

		// 근거가 없으면 빈 배열로 통일하는 값이라 여기만 null이 아니다(JdbcTraineeRosterRepository#intArray).
		assertThat(properties.path("excellentAssessmentSequenceNos").path("type").toString())
				.doesNotContain("\"null\"");
	}

	/**
	 * 22차 R5 — 제출 마감 시각의 <b>입구와 출구</b>가 둘 다 반쪽이었다.
	 *
	 * <p>생성에는 받을 자리가 없었고(일정 수정에만 있었다), 목록·상세는 읽을 자리가 없었다.
	 * 그래서 화면이 {@code endDate}를 마감이라고 계속 그렸고 9기 5차가 <b>12일 어긋난</b> 값을
	 * 보여주고 있었다. 한 곳만 열면 다른 화면이 여전히 거짓말을 하므로 세 자리를 함께 본다.
	 */
	@Test
	void letsTheSubmissionDeadlineBeSetOnCreateAndReadBackEverywhereItIsDrawn() throws Exception {
		JsonNode schemas = spec().path("components").path("schemas");

		// 입구 — 생성 모달이 시작·마감을 한 번에 정한다. 선택 필드라 required는 아니다.
		assertThat(schemas.path("CreateProjectRequest").path("properties").propertyNames())
				.contains("submissionDueAt");
		assertThat(schemas.path("CreateProjectRequest").path("required").toString())
				.doesNotContain("submissionDueAt");

		// 출구 — 목록(기간 열·대시보드 이번 회차)과 상세(타임라인·일정 수정 초기값) 둘 다.
		for (String schema : List.of("ProjectResponse", "ProjectDetailResponse")) {
			assertThat(schemas.path(schema).path("properties").propertyNames())
					.as("%s가 마감을 못 읽으면 화면이 endDate를 마감이라고 그린다", schema)
					.contains("submissionDueAt");
			// 22차 이전에 만들어져 회차 레코드가 없는 프로젝트는 null이다.
			assertThat(schemas.path(schema).path("properties").path("submissionDueAt")
					.path("type").toString()).contains("\"null\"");
		}
	}

	/**
	 * 22차 R7 — 없는 기수를 물어도 200이 나가고 있었다.
	 *
	 * <p>「이 기수엔 없다」와 「그런 기수가 없다」가 구분되지 않아, 남이 보낸 링크나 그 사이 지워진
	 * 기수를 열어도 운영자에게는 <b>아직 아무것도 안 만든 기수</b>로 보였다. 교안은 더 나빴다 —
	 * 기관 단위 목록이라 <b>없는 기수인데 다른 기수와 똑같은 7건</b>이 그대로 나갔다.
	 */
	@Test
	void tellsAMissingCohortApartFromAnEmptyOne() throws Exception {
		List<String> missing = new ArrayList<>();
		for (String path : List.of("/api/v0/cohorts/{cohortId}/projects",
				"/api/v0/cohorts/{cohortId}/curricula",
				"/api/v0/cohorts/{cohortId}/classrooms")) {
			JsonNode responses = spec().path("paths").path(path).path("get").path("responses");
			if (!responses.path("404").path("content").path("application/json").path("examples")
					.toString().contains("COHORT_NOT_FOUND")) {
				missing.add(path);
			}
		}

		assertThat(missing)
				.as("없는 기수에 200을 주면 화면이 「빈 기수」로 읽는다")
				.isEmpty();
	}

	/**
	 * 22차 R9 — 서버는 응시 창·리포트 발행 시각을 이미 계산해 두고 <b>교육생에게만</b> 주고 있었다.
	 *
	 * <p>운영자 회차 상세에는 {@code date-time}이 하나도 없어서 개요 타임라인이
	 * "코드 분석 완료 시점부터 24시간" 같은 규칙 문장만 그렸다. 그 문의를 받는 사람이 정작
	 * 시각을 모르는 상태였다.
	 */
	@Test
	void givesTheOperatorTheRoundWindowsTheTraineeAlreadySees() throws Exception {
		JsonNode properties = spec().path("components").path("schemas")
				.path("ProjectDetailResponse").path("properties");

		for (String field : List.of("submissionDueAt", "roundAssessmentOpenAt",
				"roundAssessmentDueAt", "reportPublishNotBeforeAt")) {
			assertThat(properties.path(field).path("format").asString(null))
					.as("%s는 날짜가 아니라 시각이어야 한다", field)
					.isEqualTo("date-time");
			// 회차가 열리기 전에는 정해지지 않는 값이라 null이 온다.
			assertThat(properties.path(field).path("type").toString()).contains("\"null\"");
		}

		// 개인별 값은 회차 단위가 아니라서 싣지 않는다 — 운영자에게 필요한 것은 회차의 창이다.
		assertThat(properties.propertyNames())
				.doesNotContain("assessmentOpenAt", "assessmentCloseAt");
	}

	/**
	 * 22차 R10 ⓐ — 필드 하나가 없어서 15차 R1로 만든 엔드포인트를 아무도 쓰지 못했다.
	 *
	 * <p>{@code sequenceNo}(분자)는 있는데 `3차 / 6회`의 분모가 없어, 대시보드가 그것 하나 때문에
	 * 목록 조회를 계속 부르고 있었다 — 그러면 {@code current}를 부를 이유가 사라진다.
	 */
	@Test
	void givesTheDashboardTheDenominatorItWasCallingTheListFor() throws Exception {
		JsonNode properties = spec().path("components").path("schemas")
				.path("ProjectResponse").path("properties");

		assertThat(properties.propertyNames()).contains("sequenceNo", "totalRounds");
		// 세는 값이라 언제나 온다 — 회차가 없으면 0이지 null이 아니다.
		assertThat(properties.path("totalRounds").path("type").toString())
				.contains("integer").doesNotContain("\"null\"");

		assertThat(spec().path("paths").path("/api/v0/cohorts/{cohortId}/projects/current")
				.path("get").path("responses").path("200").path("content").path("application/json")
				.path("schema").path("$ref").asString())
				.isEqualTo("#/components/schemas/ProjectResponse");
	}

	/**
	 * 22차 R8 — 오퍼레이터 화면이 부르는 조회에서 <b>「없음」과 「실패」를 가를 코드</b>가 없었다.
	 *
	 * <p>{@code UNAUTHENTICATED}·{@code ACCESS_DENIED}만으로는 화면이 갈 곳을 못 정한다.
	 * 프론트가 지목한 자리 중 기수를 받는 것부터 {@code COHORT_NOT_FOUND}를 채운다 —
	 * 「이 기수엔 아직 없다」와 「그런 기수가 없다」가 갈리면 화면이 「기수를 다시 고르세요」를
	 * 말할 수 있다.
	 */
	@Test
	void letsTheOperatorScreensTellNothingYetApartFromNoSuchCohort() throws Exception {
		List<String> missing = new ArrayList<>();
		for (String path : List.of("/api/v0/cohorts/{cohortId}/projects/current",
				"/api/v0/curricula/comparable-cohorts")) {
			if (!spec().path("paths").path(path).path("get").path("responses").path("404")
					.path("content").path("application/json").path("examples")
					.toString().contains("COHORT_NOT_FOUND")) {
				missing.add(path);
			}
		}

		assertThat(missing).isEmpty();

		// 204(회차가 없다)는 그대로다 — 404(그런 기수가 없다)와 뜻이 다르다.
		assertThat(spec().path("paths").path("/api/v0/cohorts/{cohortId}/projects/current")
				.path("get").path("responses").has("204")).isTrue();
	}

	/**
	 * 29차 R2 ① — <b>속성이 있는 객체 스키마는 {@code required}를 가져야 한다.</b>
	 *
	 * <p>전 필드가 optional이면 응답 타입은 화면에서 {@code ?}·{@code !}를 남발하게 만들고,
	 * 요청 타입은 <b>필수 누락이 컴파일에서 안 걸린다.</b>
	 *
	 * <h2>왜 스키마를 지목하지 않고 전부 쓸어 담나</h2>
	 *
	 * <p>이 파일의 다른 검사들은 스키마를 <b>하나씩 이름으로</b> 확인한다. 그러면 새로 생긴 스키마는
	 * 아무도 보지 않는다 — 실제로 22차·25차에 이어 29차까지 프론트가 같은 종류의 위반을 세 번
	 * 보고했고, 그때마다 지목 검사를 한 줄씩 늘려 왔다. 도메인이 늘 때마다 사람이 검사를 추가해야
	 * 하는 구조가 원인이므로, 여기서는 <b>모집단 전체</b>를 본다.
	 *
	 * <p>{@link #PARTIAL_UPDATE_SCHEMAS}만 예외다 — 부분 수정 요청은 required가 없는 것이 계약이다.
	 */
	@Test
	void everyObjectSchemaSaysWhichFieldsAlwaysArrive() throws Exception {
		List<String> withoutRequired = new ArrayList<>();
		spec().path("components").path("schemas").properties().forEach(schema -> {
			String name = schema.getKey();
			JsonNode definition = schema.getValue();
			if (PARTIAL_UPDATE_SCHEMAS.contains(name)) {
				return;
			}
			// 속성이 없는 스키마(enum·별칭)는 required를 말할 대상이 없다.
			if (!definition.path("properties").isObject() || definition.path("properties").isEmpty()) {
				return;
			}
			boolean saysWhatAlwaysArrives = definition.path("required").isArray()
					&& !definition.path("required").isEmpty();
			// 전 필드가 선택인 것이 사실인 요청도 있다(어느 값을 보낼지는 그때 상황이 정한다).
			// 그때는 하나를 골라 required로 올리는 대신 "빈 객체는 안 된다"를 minProperties로 적는다 —
			// 그것이 서버가 실제로 거절하는 것이고, 스펙이 거짓말을 하지 않는 유일한 표현이다.
			if (!saysWhatAlwaysArrives && definition.path("minProperties").asInt(0) < 1) {
				withoutRequired.add(name);
			}
		});

		assertThat(withoutRequired)
				.as("전 필드가 optional이면 응답은 화면에서 ?·!를 부르고 요청은 필수 누락이 컴파일에서 안 걸린다")
				.isEmpty();
	}

	/**
	 * 29차 R2 ② — <b>설명이 {@code null}을 말하면 타입도 {@code null}을 받아야 한다.</b>
	 *
	 * <p>이쪽이 optional보다 나쁘다. 키가 빠지는 것은 생성 타입이 {@code ?}로 드러내 주지만,
	 * 이 경우는 <b>컴파일러가 null 검사를 요구하지 않는데 런타임에 null이 온다.</b>
	 * 22차 {@code Team.submission} · 25차 {@code TraineeTimelineEvent.type}과 같은 모양이다.
	 *
	 * <p>설명문을 읽어 판정하므로 <b>부정문은 걸러낸다</b> — "null이 아니다"는 null이 오지 않는다는
	 * 뜻이라 위반이 아니다. 그 구분이 없으면 정확히 쓴 설명이 검사에 걸려, 사람이 설명을 흐리게
	 * 고치는 쪽으로 움직인다.
	 */
	@Test
	void writesNullabilityIntoTheTypeWhereverTheDescriptionMentionsIt() throws Exception {
		List<String> lying = new ArrayList<>();
		spec().path("components").path("schemas").properties().forEach(schema ->
				schema.getValue().path("properties").properties().forEach(property -> {
					String description = property.getValue().path("description").asString("");
					if (!mentionsNull(description) || allowsNull(property.getValue())) {
						return;
					}
					lying.add(schema.getKey() + "." + property.getKey());
				}));

		assertThat(lying)
				.as("설명은 null이라는데 타입이 안 받으면 컴파일러가 null 검사를 요구하지 않는다")
				.isEmpty();
	}

	/**
	 * 31차 R2 — <b>드라이런의 결과는 등록의 결과와 같은 이름을 쓰면 안 된다.</b>
	 *
	 * <p>9차 Q3-③ 이래 미리보기는 등록 응답을 그대로 돌려줬고, 그래서 <b>아무것도 만들지 않은 호출이
	 * {@code registeredCount: 1}로 답했다.</b> 프론트는 그것을 보고 미리보기가 진짜 등록을 했는지
	 * 기수 명단을 뒤졌다. 한 컴포넌트로 그리게 하려던 이득보다, 수의 이름이 거짓말을 하는 값이 컸다.
	 *
	 * <p>{@code Failure}만은 계속 공유한다 — 행별 판정을 등록과 같은 메서드가 내리므로 뜻이 같고,
	 * 여기서 형을 가르면 실패 목록을 그리는 컴포넌트가 두 벌이 된다.
	 */
	@Test
	void namesTheDryRunResultAfterWhatItActuallyCounted() throws Exception {
		for (String path : List.of("/api/v0/cohorts/{cohortId}/trainees/preview",
				"/api/v0/cohorts/{cohortId}/trainees/invitations/preview")) {
			assertThat(spec().path("paths").path(path).path("post")
					.path("responses").path("200").path("content").path("application/json")
					.path("schema").path("$ref").asString())
					.as("드라이런이 등록 응답을 돌려주면 registeredCount가 「등록됐다」로 읽힌다")
					.isEqualTo("#/components/schemas/PreviewTraineesResponse");
		}

		JsonNode preview = spec().path("components").path("schemas").path("PreviewTraineesResponse");

		// 항상 0·null이던 두 필드는 남겨 두면 화면이 null 여부로 무엇을 갈라야 하는지 묻게 된다.
		assertThat(preview.path("properties").propertyNames())
				.containsExactlyInAnyOrder("requestedCount", "registrableCount", "failures");
		assertThat(preview.path("properties").path("failures").path("items").path("$ref").asString())
				.isEqualTo("#/components/schemas/Failure");
	}

	/**
	 * 31차 R1 — <b>boolean은 true가 무슨 뜻인지 말해야 한다.</b> 다른 타입과 달리 이름과 타입만으로는
	 * 읽는 쪽이 아무것도 알 수 없다 — {@code passed}가 힌트를 받고 통과한 경우를 포함하는지,
	 * {@code resolved}가 {@code reminderEligible}의 반대인지는 설명에만 있다.
	 *
	 * <h2>설명이 빈 boolean은 둘 중 하나다</h2>
	 *
	 * <p><b>① 샌 검사 메서드.</b> {@code isEmpty()}·{@code isMutableStatus()} 같은 {@code @AssertTrue}
	 * 메서드는 자바에서 boolean 게터의 모양을 하고 있어, 막지 않으면 {@code empty}·{@code mutableStatus}
	 * 라는 <b>요청 필드로 스펙에 나간다.</b> 화면은 서버가 보지도 않는 값을 무엇으로 채울지 묻게 되고
	 * 답이 없다 — 그 값은 애초에 요청의 일부가 아니다. 고치는 법은 {@code @JsonIgnore}다.
	 *
	 * <p><b>② 설명이 스펙에 닿지 않은 진짜 필드.</b> javadoc {@code @param}에 잘 적어 두어도
	 * springdoc은 그것을 읽지 않는다 — <b>{@code @Schema}로 옮겨야</b> 생성된 타입에 주석으로 따라간다.
	 * 실제로 여기 걸린 12개가 그랬다.
	 *
	 * <p>둘 다 화면에서는 같은 모양이다 — <b>정체를 알 수 없는 boolean</b>. 그래서 함께 본다.
	 *
	 * <p>{@link #exposesTheOperationSettingsUpdateAsAPartialUpdate}가 ①을 이미 보고 있지만
	 * <b>스키마 하나를 이름으로</b> 지목한다. 그래서 옆 도메인의 {@code SessionActivityRequest}가
	 * 그대로 통과했고, 프론트가 31차에 그것을 찾아 왔다. 검사를 한 줄 더 늘리는 대신 모집단 전체를 본다
	 * (실제로 지목 검사가 못 보던 위반이 18개 더 있었다 — ① 6개 · ② 12개).
	 */
	@Test
	void everyBooleanFieldSaysWhatItMeans() throws Exception {
		List<String> undescribed = new ArrayList<>();
		spec().path("components").path("schemas").properties().forEach(schema ->
				schema.getValue().path("properties").properties().forEach(property -> {
					if (!property.getValue().path("type").toString().contains("\"boolean\"")) {
						return;
					}
					if (!property.getValue().path("description").asString("").isBlank()) {
						return;
					}
					undescribed.add(schema.getKey() + "." + property.getKey());
				}));

		assertThat(undescribed)
				.as("설명 없는 boolean은 샌 검사 메서드(@JsonIgnore 누락)이거나 설명이 @Schema에 닿지 않은 필드다")
				.isEmpty();
	}

	/**
	 * 부분 수정 요청은 {@code required}가 없는 것이 계약이다 — 안 바꾸는 값을 매번 실어 보내게 되면
	 * 그 순간 다른 사람이 바꾼 값을 되돌리는 경로가 열린다({@link #exposesTheOperationSettingsUpdateAsAPartialUpdate}).
	 *
	 * <p>여기에 이름을 더할 때는 <b>그 스키마가 PATCH 본문인지</b> 확인할 것. 응답 스키마를 넣으면
	 * 검사만 조용해지고 화면은 그대로 {@code ?}를 달게 된다.
	 */
	private static final java.util.Set<String> PARTIAL_UPDATE_SCHEMAS = java.util.Set.of(
			"UpdateOperationSettingRequest",
			"UpdateClassroomRequest",
			"UpdateCohortRequest"
	);

	/**
	 * <b>"null이 온다"고 말하는 문장</b>이 있는지. 부정문은 반대를 말하므로 걸러낸다.
	 *
	 * <p>이 구분이 없으면 정확히 쓴 설명이 검사에 걸린다 — "항상 값이 있다(DB도 NOT NULL이다)"나
	 * "null은 허용하지 않는다"는 <b>null이 오지 않는다</b>는 뜻인데 걸리면, 사람이 설명을 흐리게
	 * 고치는 쪽으로 움직인다. 검사가 문서를 나쁘게 만들면 안 된다.
	 *
	 * <p>비교 전에 마크다운 강조(<code>`</code>·{@code *})를 벗기고 소문자로 맞춘다.
	 * 설명은 대부분 {@code `null`은 허용하지 않는다}처럼 적혀 있어, 그대로 두면 부정문 목록이
	 * 한 글자 차이로 빗나간다.
	 */
	private static boolean mentionsNull(String description) {
		String text = description.toLowerCase(java.util.Locale.ROOT)
				.replace("`", "")
				.replace("*", "");
		if (!text.contains("null")) {
			return false;
		}
		for (String negation : NULL_NEGATIONS) {
			text = text.replace(negation, "");
		}
		return text.contains("null");
	}

	/** 전부 소문자·마크다운 제거 후의 형태로 적는다({@link #mentionsNull}이 그렇게 맞춰 놓고 비교한다). */
	private static final List<String> NULL_NEGATIONS = List.of(
			"not null",
			"null이 아니다",
			"null이 아니라",
			"null이 아닌",
			"null이 아님",
			"null은 아니다",
			"null은 허용하지 않는다",
			"null을 허용하지 않는다",
			"null은 허용되지 않는다",
			"null을 보내지"
	);

	/**
	 * 3.1에서 null 가능은 <b>타입 배열</b>이거나 {@code oneOf}/{@code anyOf}의 한 갈래다.
	 *
	 * <p>3.0의 {@code nullable: true} 키는 <b>인정하지 않는다.</b> 3.1에서 그 키는 의미가 없어
	 * 생성기가 읽지 않는다 — 인정해 버리면 검사만 통과하고 화면 타입은 그대로 non-null이 된다.
	 * (소스에는 {@code @Schema(nullable = true)}로 적는다. springdoc이 그것을 3.1 형태로 옮겨 주며,
	 * 이 검사는 <b>옮겨진 결과</b>를 본다.)
	 */
	private static boolean allowsNull(JsonNode property) {
		if (property.path("type").toString().contains("\"null\"")) {
			return true;
		}
		for (String combinator : List.of("oneOf", "anyOf", "allOf")) {
			if (property.path(combinator).toString().contains("\"null\"")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * <b>스키마 이름은 스펙 전체에서 유일해야 한다</b>(30차 R2①②).
	 *
	 * <p>springdoc은 중첩 record를 <b>단순 이름</b>으로 컴포넌트에 등록한다. 같은 이름이 둘이면
	 * 예외도 경고도 없이 <b>나중 것이 앞의 것을 덮어쓰고</b>, 밀려난 쪽을 참조하던 응답은 남의 정의를
	 * 가리키게 된다. 스펙은 문법적으로 멀쩡하고 CI도 통과하므로 <b>읽어서는 보이지 않는다</b> —
	 * 30차에는 {@code Summary}(3중복)·{@code Concept}·{@code Classroom}·{@code Team}·
	 * {@code RequirementResult}·{@code Trainee} 여섯 이름이 겹쳐 있었고, 프론트가 히트맵을 화면에
	 * 붙이다가 열 이름이 생성 타입에 없다는 것을 발견하고서야 드러났다.
	 *
	 * <p>스펙 결과물만 봐서는 검사할 수 없다 — 밀려난 스키마는 <b>흔적 없이 사라지므로</b> 스펙에
	 * 남는 것은 정상적인 스키마 하나뿐이다. 그래서 등록 <b>전</b>인 타입 쪽을 센다.
	 *
	 * <p>{@code ..presentation..}만 보는 이유는 이 프로젝트에서 응답·요청 DTO가 그 아래에만 있기
	 * 때문이다. 그 밖(application·domain·infrastructure)의 record는 컴포넌트로 등록되지 않으므로
	 * 같은 이름을 써도 부딪히지 않는다.
	 */
	@Test
	void schemaNamesAreUniqueAcrossPresentationDtos() {
		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter((reader, factory) -> true);

		Map<String, List<String>> bySchemaName = new LinkedHashMap<>();
		for (BeanDefinition definition : scanner.findCandidateComponents("com.bigproject.backend")) {
			String className = definition.getBeanClassName();
			if (className == null || !className.contains(".presentation.")) {
				continue;
			}
			Class<?> type;
			try {
				type = Class.forName(className);
			} catch (ClassNotFoundException | NoClassDefFoundError ignored) {
				continue;
			}
			if (!type.isRecord() && !type.isEnum()) {
				continue;
			}
			bySchemaName.computeIfAbsent(schemaNameOf(type), name -> new ArrayList<>())
					.add(type.getName());
		}

		// 스캐너가 아무것도 못 찾으면 위 단언은 공허하게 통과한다. 실제로 세고 있는지부터 확인한다.
		assertThat(bySchemaName).hasSizeGreaterThan(100);

		List<String> collisions = bySchemaName.entrySet().stream()
				.filter(entry -> entry.getValue().size() > 1)
				.map(entry -> entry.getKey() + " ← " + String.join(" · ", entry.getValue()))
				.toList();

		assertThat(collisions)
				.as("스키마 이름이 겹치면 한쪽이 스펙에서 조용히 사라진다. @Schema(name = \"...\")로 갈라라")
				.isEmpty();
	}

	/** springdoc이 이 타입을 등록할 이름. {@code @Schema(name)}을 적었으면 그것이 이긴다. */
	private static String schemaNameOf(Class<?> type) {
		Schema schema = type.getAnnotation(Schema.class);
		return schema == null || schema.name().isEmpty() ? type.getSimpleName() : schema.name();
	}

	/**
	 * 33차 R1 — <b>스펙이 요구하는 요청 헤더는 CORS 허용 목록에도 있어야 한다.</b>
	 *
	 * <h2>이 자리는 서버 쪽 테스트로 잡히지 않는다</h2>
	 *
	 * <p>사전 확인(preflight)은 <b>브라우저만</b> 보낸다. curl·스웨거·Postman에서는 그 단계가 없어
	 * 요청이 그대로 도착하고 정상 응답이 온다 — 그래서 목록에서 헤더가 빠져도 서버 쪽에서는
	 * 아무 증상이 없고, 브라우저에서만 <b>본 요청이 아예 나가지 않는다.</b> 로그도 남지 않는다.
	 *
	 * <p>실제로 {@code Idempotency-Key}가 그 상태였다. 스펙은 필수로 요구하는데 목록에 없어서,
	 * 헤더를 빼면 400이고 넣으면 브라우저가 막는 <b>프론트에 선택지가 없는</b> 상태로 제출 API가
	 * 통째로 잠겨 있었다. 제출이 막히면 분석·응시·리포트가 전부 막힌다.
	 *
	 * <p>헤더를 새로 읽는 오퍼레이션이 생겨도 CORS 목록은 사람이 따로 고쳐야 한다. 그 둘을
	 * 이어 두지 않으면 같은 사고가 반복되므로, <b>스펙에 선언된 헤더</b>를 모아 대조한다.
	 * {@link SecurityConfig#ALLOWED_HEADERS}를 직접 읽는다 — 목록을 여기 한 벌 더 적으면
	 * 두 벌이 갈라져, 정작 서버가 쓰는 쪽이 틀린 채로 검사만 통과한다.
	 */
	@Test
	void letsTheBrowserSendEveryHeaderTheSpecAsksFor() throws Exception {
		List<String> allowed = SecurityConfig.ALLOWED_HEADERS.stream()
				.map(header -> header.toLowerCase(java.util.Locale.ROOT))
				.toList();

		Map<String, String> blockedByCors = new LinkedHashMap<>();
		List<String> declared = new ArrayList<>();
		forEachOperation(spec(), (operationId, operation) ->
				operation.path("parameters").forEach(parameter -> {
					if (!"header".equals(parameter.path("in").asString(null))) {
						return;
					}
					String name = parameter.path("name").asString("");
					if (name.isEmpty()) {
						return;
					}
					declared.add(name);
					if (allowed.contains(name.toLowerCase(java.util.Locale.ROOT))) {
						return;
					}
					// 같은 헤더가 여러 오퍼레이션에 있으면 처음 하나만 남긴다 — 목록이 아니라
					// "어느 헤더가 빠졌나"가 읽어야 할 정보다.
					blockedByCors.putIfAbsent(name, operationId);
				}));

		// 스펙에서 헤더 파라미터를 하나도 못 읽으면 아래 단언은 공허하게 통과한다.
		// 33차의 그 헤더가 실제로 걸리는지부터 확인한다.
		assertThat(declared)
				.as("헤더 파라미터를 읽지 못하고 있다 — 검사가 아무것도 보지 않는다")
				.contains("Idempotency-Key");

		assertThat(blockedByCors)
				.as("CORS 허용 목록에 없는 헤더는 브라우저가 본 요청을 보내기 전에 막는다 "
						+ "— curl·스웨거로는 재현되지 않으므로 여기서 잡아야 한다. "
						+ "SecurityConfig.ALLOWED_HEADERS에 추가할 것")
				.isEmpty();
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
