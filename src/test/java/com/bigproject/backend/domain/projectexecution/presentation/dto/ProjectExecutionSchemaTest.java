package com.bigproject.backend.domain.projectexecution.presentation.dto;

import com.bigproject.backend.domain.curriculum.presentation.dto.SectionResponse;
import com.bigproject.backend.global.config.SwaggerConfig;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 8차 요청(R1·R2·§4)이 지적한 세 가지가 <b>스펙에 실제로 반영됐는지</b> 확인한다.
 * 전부 "생성된 프론트 타입이 서버가 보내는 것과 다른 말을 한다"는 한 종류의 문제다.
 */
class ProjectExecutionSchemaTest {

    /**
     * {@code definitionMissing}이 true면 {@code description}은 {@code null}이다. 그 사실이 설명문에만 있으면
     * 생성 타입은 {@code description: string}이 되어 컴파일러가 null 검사를 요구하지 않고,
     * {@code description.trim()} 한 줄에서 런타임에 죽는다. 3.1 타입 배열로 나가야 한다.
     */
    @Test
    void saysInTheTypeThatASectionItemDefinitionCanBeNull() {
        OpenAPI openApi = specOf(SectionResponse.class);

        Schema<?> description = (Schema<?>) openApi.getComponents().getSchemas()
                .get("SectionItemResponse").getProperties().get("description");

        assertThat(description.getTypes()).containsExactlyInAnyOrder("string", "null");
        // 키는 항상 온다. required에서 빼면 이번엔 "없을 수도 있다"는 다른 거짓말이 된다.
        assertThat(openApi.getComponents().getSchemas().get("SectionItemResponse").getRequired())
                .contains("description");
    }

    /** 같은 원본 컬럼을 쓰는 검증개념 후보도 마찬가지다. */
    @Test
    void saysTheSameForConceptCandidates() {
        OpenAPI openApi = specOf(ConceptCandidateResponse.class);

        assertThat(((Schema<?>) openApi.getComponents().getSchemas()
                .get("ConceptCandidateResponse").getProperties().get("description")).getTypes())
                .containsExactlyInAnyOrder("string", "null");
    }

    /**
     * {@code PUT} 전체 교체의 본문은 필수다. 선택이면 빈 요청과 "전부 지우기"를 구분할 수 없고,
     * 필드를 빠뜨린 호출이 컴파일을 통과한 뒤 런타임 400으로만 걸린다.
     */
    @Test
    void requiresTheRequirementListOnAFullReplace() {
        Schema<?> request = ModelConverters.getInstance()
                .readAll(new AnnotatedType(ReplaceRequirementsRequest.class))
                .get("ReplaceRequirementsRequest");

        assertThat(request.getRequired()).containsExactly("requirementTitles");
        // 빈 배열이 "전부 지우기"라는 것은 화면이 확인 모달을 띄울지 가르는 정보라 설명에 있어야 한다.
        assertThat(request.getProperties().get("requirementTitles").getDescription()).contains("빈 배열");
    }

    /**
     * 값 목록이 요청·응답에 복사돼 있으면 같은 개념이 서로 다른 타입이 되고, 한쪽에만 값이 늘어도
     * 아무도 눈치채지 못한다. 공유 정의 하나를 {@code $ref}로 가리켜야 한다.
     */
    @Test
    void keepsProjectEnumsAsOneDefinition() {
        Map<String, Schema> response = ModelConverters.getInstance()
                .readAll(new AnnotatedType(ProjectResponse.class));
        Map<String, Schema> request = ModelConverters.getInstance()
                .readAll(new AnnotatedType(CreateProjectRequest.class));

        assertThat(response).containsKeys("ProjectCategory", "ProjectStatus");
        assertThat(((Schema<?>) response.get("ProjectResponse").getProperties().get("category")).get$ref())
                .isEqualTo("#/components/schemas/ProjectCategory");
        assertThat(((Schema<?>) response.get("ProjectResponse").getProperties().get("status")).get$ref())
                .isEqualTo("#/components/schemas/ProjectStatus");

        // 요청도 같은 정의를 가리킨다 — 폼에서 고른 값을 응답 타입 함수에 그대로 넘길 수 있어야 한다.
        assertThat(((Schema<?>) request.get("CreateProjectRequest").getProperties().get("category")).get$ref())
                .isEqualTo("#/components/schemas/ProjectCategory");

        // 값 목록은 공유 스키마 한 곳에만 있다.
        assertThat(((Schema<?>) response.get("ProjectResponse").getProperties().get("status")).getEnum()).isNull();
        assertThat(response.get("ProjectStatus").getEnum()).containsExactly("PLANNED", "RUNNING", "CLOSED");
    }

    /**
     * {@code ProjectStatus}는 {@code CohortStatus}와 값이 같지만 별도 정의로 둔다 — 기수 상태와
     * 프로젝트 상태는 독립적으로 움직이고, 한쪽에만 값이 붙는 날 공유 스키마였다면 다른 쪽 화면의
     * 분기가 조용히 넓어진다. 이 테스트는 그 판단을 문서가 아니라 코드로 못 박아 둔다.
     */
    @Test
    void doesNotFoldProjectStatusIntoCohortStatus() {
        Map<String, Schema> schemas = ModelConverters.getInstance()
                .readAll(new AnnotatedType(ProjectResponse.class));

        assertThat(((Schema<?>) schemas.get("ProjectResponse").getProperties().get("status")).get$ref())
                .isNotEqualTo("#/components/schemas/CohortStatus");
    }

    /**
     * 9차 R2 — 후보 항목이 섹션 항목과 <b>같은 값들을 담는지</b> 못 박는다. 같은 원장에서 나오는데
     * 한쪽만 좁으면 후보를 교안·섹션별로 묶는 화면이 묶을 기준을 잃는다.
     */
    @Test
    void givesConceptCandidatesTheSameShapeAsSectionItems() {
        Map<String, Schema> candidate = ModelConverters.getInstance()
                .readAll(new AnnotatedType(ConceptCandidateResponse.class));
        Map<String, Schema> sectionItem = ModelConverters.getInstance()
                .readAll(new AnnotatedType(SectionResponse.class));

        Map<String, Schema> candidateProperties = candidate.get("ConceptCandidateResponse").getProperties();
        assertThat(candidateProperties).containsKeys(
                "curriculumVersionId", "sectionId", "sectionTitle", "pageStart", "pageEnd", "definitionMissing");

        // 겹치는 필드는 타입까지 같아야 화면이 두 응답을 한 함수로 다룰 수 있다.
        Map<String, Schema> sectionItemProperties = sectionItem.get("SectionItemResponse").getProperties();
        for (String shared : List.of("extractedName", "description", "definitionMissing", "pageStart", "pageEnd")) {
            assertThat(((Schema<?>) candidateProperties.get(shared)).getType())
                    .as("공유 필드 %s의 타입", shared)
                    .isEqualTo(((Schema<?>) sectionItemProperties.get(shared)).getType());
        }
    }

    /**
     * 9차 R1 — 저장한 것을 되읽는 자리가 상세 응답 하나에 모여 있는지 본다.
     * 셋으로 나누면 상세 화면 진입에 조회가 4건이 된다.
     */
    @Test
    void letsTheDetailResponseReadBackWhatWasSaved() {
        Map<String, Schema> schemas = ModelConverters.getInstance()
                .readAll(new AnnotatedType(ProjectDetailResponse.class));

        Map<String, Schema> properties = schemas.get("ProjectDetailResponse").getProperties();
        assertThat(properties).containsKeys("curricula", "concepts", "requirementTitles",
                "curriculumCount", "conceptCount", "conceptCandidateCount");

        // 확정 개념은 출처를 달고 다녀야 한다 — 리포트·면담 브리프가 이 값으로 교안 위치를 가리킨다.
        assertThat(schemas.get("ProjectConfirmedConcept").getProperties())
                .containsKeys("curriculumVersionId", "pageStart", "pageEnd");

        // 목록 항목 스키마는 전역 이름 공간에서 부딪히지 않게 무엇의 항목인지 이름으로 드러난다.
        assertThat(schemas).containsKeys("ProjectLinkedCurriculum", "ProjectConfirmedConcept");
    }

    /** 목록은 회차마다 조회를 부를 수 없으므로 셀에 그릴 숫자 셋을 항목에 싣는다. */
    @Test
    void countsWhatTheListCannotCountForItself() {
        Map<String, Schema> schemas = ModelConverters.getInstance()
                .readAll(new AnnotatedType(ProjectResponse.class));

        assertThat(schemas.get("ProjectResponse").getProperties())
                .containsKeys("curriculumCount", "conceptCount", "conceptCandidateCount");
    }

    /** {@code @Schema(nullable = true)}는 3.0 필드라 스펙 후처리를 거쳐야 3.1 타입 배열이 된다. */
    private OpenAPI specOf(Class<?> type) {
        OpenAPI openApi = new OpenAPI().paths(new Paths()).components(new Components());
        withRequiredConverter().readAll(new AnnotatedType(type))
                .forEach(openApi.getComponents()::addSchemas);
        new SwaggerConfig().izGetOpenApiCustomizer().customise(openApi);
        return openApi;
    }

    private ModelConverters withRequiredConverter() {
        ModelConverters instance = new ModelConverters();
        instance.addConverter(new com.bigproject.backend.global.config.ResponseRecordRequiredConverter());
        return instance;
    }
}