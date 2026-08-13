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

		// ③ 등록하지 않고 무엇이 걸리는지만 본다. 등록 응답과 같은 스키마라 화면이 한 컴포넌트로 그린다.
		assertThat(paths.path("/api/v0/cohorts/{cohortId}/trainees/preview").has("post")).isTrue();
		assertThat(paths.path("/api/v0/cohorts/{cohortId}/trainees/invitations/preview").has("post")).isTrue();
		assertThat(paths.path("/api/v0/cohorts/{cohortId}/trainees/preview").path("post")
				.path("responses").path("200").path("content").path("application/json")
				.path("schema").path("$ref").asString())
				.isEqualTo("#/components/schemas/RegisterTraineesResponse");

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
