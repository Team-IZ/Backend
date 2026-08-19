package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.domain.curriculum.domain.CurriculumMaterial;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersionStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumMaterialRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.TeachesRepository;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 42차 R1 — {@code POST /curricula/{materialId}/versions}.
 *
 * <p>기존 material에 새 버전을 잇는 서비스 메서드가 새로 생겼다. 이미 있던
 * {@link CurriculumVersion#createNextVersion}·{@link CurriculumVersion#deactivate()}·
 * {@link CurriculumVersionRepository#findAllByMaterialIdAndOrgIdOrderByVersionNoDesc}를
 * 그대로 잇기만 했는지를 이 테스트가 고정한다.
 */
class CurriculumServiceImplRegisterVersionTest {

	private final CurriculumVersionRepository versionRepository = mock(CurriculumVersionRepository.class);
	private final CurriculumTeachesMappingRepository mappingRepository = mock(CurriculumTeachesMappingRepository.class);
	private final CurriculumAnalysisRepository analysisRepository = mock(CurriculumAnalysisRepository.class);
	private final CurriculumSectionRepository sectionRepository = mock(CurriculumSectionRepository.class);
	private final ProjectService projectService = mock(ProjectService.class);
	private final CurriculumMaterialRepository materialRepository = mock(CurriculumMaterialRepository.class);
	private final FileStorageService fileStorageService = mock(FileStorageService.class);
	private final AiCurriculumClient aiCurriculumClient = mock(AiCurriculumClient.class);
	private final CurriculumCatalogRepository catalogRepository = mock(CurriculumCatalogRepository.class);
	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
	private final TeachesRepository teachesRepository = mock(TeachesRepository.class);

	private final CurriculumServiceImpl service = new CurriculumServiceImpl(
			versionRepository, mappingRepository, analysisRepository, sectionRepository,
			projectService, materialRepository, fileStorageService, aiCurriculumClient,
			catalogRepository, jdbcTemplate, teachesRepository);

	private final UUID orgId = UUID.randomUUID();
	private final UUID materialId = UUID.randomUUID();
	private final UUID actorUserId = UUID.randomUUID();
	private CurriculumMaterial material;

	@BeforeEach
	void setUpMaterial() {
		material = CurriculumMaterial.create(orgId, "AI_LLMOps", "ai_llmops", "AI", "PDF", actorUserId);
	}

	private static MockMultipartFile pdf() {
		return new MockMultipartFile("file", "curriculum.pdf", "application/pdf", "%PDF-1.7".getBytes());
	}

	private FileStorageService.StoredFile stored(String name) {
		return new FileStorageService.StoredFile("file:///tmp/" + name, name, "application/pdf", 123L, "hash-" + name);
	}

	/** 기존 최신 버전 번호 + 1로 다음 버전이 만들어지고, 기존 버전은 INACTIVE로 넘어간다. */
	@Test
	void createsTheNextVersionNumberAndDeactivatesThePreviousLatest() {
		when(materialRepository.findByMaterialIdAndOrgId(materialId, orgId)).thenReturn(Optional.of(material));
		CurriculumVersion v1 = CurriculumVersion.createFirstVersion(
				materialId, "v1.pdf", "file:///tmp/v1.pdf", 10L, "hash-v1", actorUserId);
		when(versionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of(v1));
		when(fileStorageService.store(any())).thenReturn(stored("v2.pdf"));
		when(versionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		CurriculumVersion created = service.registerCurriculumVersion(materialId, orgId, null, pdf(), actorUserId);

		assertThat(created.getVersionNo()).isEqualTo(2);
		assertThat(created.getMaterialId()).isEqualTo(materialId);
		assertThat(created.getOriginalFileName()).isEqualTo("v2.pdf");
		assertThat(v1.getStatus()).isEqualTo(CurriculumVersionStatus.INACTIVE);
	}

	/** materialId가 없거나 이미 논리 삭제된 교안이면 404 — 새 material을 만들지 않는다. */
	@Test
	void rejectsWhenTheMaterialIsMissingOrDeleted() {
		when(materialRepository.findByMaterialIdAndOrgId(materialId, orgId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.registerCurriculumVersion(materialId, orgId, null, pdf(), actorUserId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_MATERIAL_NOT_FOUND));

		verify(materialRepository, never()).save(any());
		verify(versionRepository, never()).save(any());
	}

	/** 파일이 없으면 400 — 등록 API와 같은 코드다. */
	@Test
	void rejectsWhenNoFileIsAttached() {
		assertThatThrownBy(() -> service.registerCurriculumVersion(materialId, orgId, null, null, actorUserId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_FILE_REQUIRED));
	}

	/** title을 생략하면 material 제목을 그대로 두고, 다른 교안과의 중복 검사도 돌지 않는다. */
	@Test
	void keepsTheExistingTitleWhenNoneIsGiven() {
		when(materialRepository.findByMaterialIdAndOrgId(materialId, orgId)).thenReturn(Optional.of(material));
		CurriculumVersion v1 = CurriculumVersion.createFirstVersion(
				materialId, "v1.pdf", "file:///tmp/v1.pdf", 10L, "hash-v1", actorUserId);
		when(versionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of(v1));
		when(fileStorageService.store(any())).thenReturn(stored("v2.pdf"));
		when(versionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.registerCurriculumVersion(materialId, orgId, "  ", pdf(), actorUserId);

		assertThat(material.getTitle()).isEqualTo("AI_LLMOps");
		verify(materialRepository, never())
				.existsByOrgIdAndNormalizedTitleAndDeletedAtIsNull(any(), anyString());
	}

	/** title을 바꿔 달았는데 다른 교안이 이미 그 제목을 쓰고 있으면 409 — 자기 자신과 겹치는 것은 허용한다. */
	@Test
	void rejectsRenamingToATitleAnotherLiveMaterialAlreadyUses() {
		when(materialRepository.findByMaterialIdAndOrgId(materialId, orgId)).thenReturn(Optional.of(material));
		when(materialRepository.existsByOrgIdAndNormalizedTitleAndDeletedAtIsNull(orgId, "spring 심화"))
				.thenReturn(true);

		assertThatThrownBy(() ->
				service.registerCurriculumVersion(materialId, orgId, "Spring 심화", pdf(), actorUserId))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_TITLE_DUPLICATED));

		verify(versionRepository, never()).save(any());
	}

	/** 새 제목이 지금 자기 제목과 같으면(대소문자·공백만 다름) 중복이 아니다. */
	@Test
	void allowsRenamingToTheSameNormalizedTitleAsItself() {
		when(materialRepository.findByMaterialIdAndOrgId(materialId, orgId)).thenReturn(Optional.of(material));
		CurriculumVersion v1 = CurriculumVersion.createFirstVersion(
				materialId, "v1.pdf", "file:///tmp/v1.pdf", 10L, "hash-v1", actorUserId);
		when(versionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of(v1));
		when(fileStorageService.store(any())).thenReturn(stored("v2.pdf"));
		when(versionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.registerCurriculumVersion(materialId, orgId, "AI_LLMOps", pdf(), actorUserId);

		verify(materialRepository, never())
				.existsByOrgIdAndNormalizedTitleAndDeletedAtIsNull(eq(orgId), anyString());
	}
}
