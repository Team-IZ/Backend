package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysisStatus;
import com.bigproject.backend.domain.curriculum.domain.CurriculumCatalogRepository;
import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import com.bigproject.backend.domain.curriculum.domain.CurriculumVersion;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumAnalysisRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumMaterialRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumSectionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumTeachesMappingRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.CurriculumVersionRepository;
import com.bigproject.backend.domain.curriculum.infrastructure.TeachesRepository;
import com.bigproject.backend.domain.projectexecution.application.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code requestAnalysis}의 "진행 중인 분석이 있는가" 체크(25차 R2)는 SELECT-then-INSERT라
 * 동시 요청 두 건이 둘 다 통과할 수 있다 — 부분 유니크 인덱스
 * ({@code docs/migration/2026-08-20_curriculum_analysis_dedupe_index.sql}, 아직 운영 미적용)가
 * 적용되면 진 쪽이 {@code analysisRepository.save}에서 {@link DataIntegrityViolationException}을
 * 받는다. 이 테스트는 그 예외가 앱 레벨 체크와 같은 {@code CURRICULUM_ANALYSIS_IN_PROGRESS}로
 * 옮겨지는지만 본다 — 인덱스 자체는 이 테스트의 대상이 아니다(DB 마이그레이션이라 유닛 테스트로
 * 확인할 수 없다).
 */
class CurriculumServiceImplRequestAnalysisTest {

	private final CurriculumMaterialRepository materialRepository = mock(CurriculumMaterialRepository.class);
	private final CurriculumVersionRepository curriculumVersionRepository = mock(CurriculumVersionRepository.class);
	private final CurriculumAnalysisRepository analysisRepository = mock(CurriculumAnalysisRepository.class);
	private final AiCurriculumClient aiCurriculumClient = mock(AiCurriculumClient.class);
	private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

	@TempDir
	Path tempDir;

	private CurriculumServiceImpl service() {
		return new CurriculumServiceImpl(
				curriculumVersionRepository,
				mock(CurriculumTeachesMappingRepository.class),
				analysisRepository,
				mock(CurriculumSectionRepository.class),
				mock(ProjectService.class),
				materialRepository,
				mock(FileStorageService.class),
				aiCurriculumClient,
				mock(CurriculumCatalogRepository.class),
				jdbcTemplate,
				mock(TeachesRepository.class),
				mock(software.amazon.awssdk.services.s3.S3Client.class));
	}

	private CurriculumVersion latestVersion(UUID versionId) throws Exception {
		Path pdf = tempDir.resolve("curriculum.pdf");
		Files.write(pdf, new byte[]{1, 2, 3});

		CurriculumVersion version = mock(CurriculumVersion.class);
		when(version.getVersionId()).thenReturn(versionId);
		when(version.getFileUri()).thenReturn(pdf.toUri().toString());
		return version;
	}

	@Test
	void savesAnalysisNormallyWhenNothingIsInProgress() throws Exception {
		UUID materialId = UUID.randomUUID();
		UUID orgId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(materialRepository.existsByMaterialIdAndOrgId(materialId, orgId)).thenReturn(true);
		CurriculumVersion version = latestVersion(versionId);
		when(curriculumVersionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of(version));
		when(analysisRepository.findAllByVersionIdAndStatusIn(eq(versionId), any())).thenReturn(List.of());
		when(analysisRepository.countByVersionId(versionId)).thenReturn(0L);
		when(aiCurriculumClient.requestAnalysis(eq(versionId), any(), any(), any()))
				.thenReturn(new AiCurriculumClient.CurriculumAccepted(UUID.randomUUID().toString(), "PENDING"));
		when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class))).thenReturn(UUID.randomUUID());

		service().requestAnalysis(materialId, orgId, actorUserId, false);

		org.mockito.Mockito.verify(analysisRepository).save(any());
	}

	/**
	 * 🔴 <b>이 테스트가 핵심이다.</b> 앱 레벨 체크(findAllByVersionIdAndStatusIn)는 통과했지만
	 * (다른 요청이 그 사이 먼저 저장을 끝내는 경합 상황을 흉내낸다) 실제 저장이
	 * DataIntegrityViolationException으로 막히면, 그걸 그대로 새 나가게 두지 않고 앱 레벨 체크와
	 * 같은 도메인 예외로 옮긴다.
	 */
	@Test
	void translatesSaveCollisionToAnalysisInProgress() throws Exception {
		UUID materialId = UUID.randomUUID();
		UUID orgId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(materialRepository.existsByMaterialIdAndOrgId(materialId, orgId)).thenReturn(true);
		CurriculumVersion version = latestVersion(versionId);
		when(curriculumVersionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of(version));
		when(analysisRepository.findAllByVersionIdAndStatusIn(eq(versionId), any())).thenReturn(List.of());
		when(analysisRepository.countByVersionId(versionId)).thenReturn(0L);
		when(aiCurriculumClient.requestAnalysis(eq(versionId), any(), any(), any()))
				.thenReturn(new AiCurriculumClient.CurriculumAccepted(UUID.randomUUID().toString(), "PENDING"));
		when(jdbcTemplate.queryForObject(any(String.class), eq(UUID.class))).thenReturn(UUID.randomUUID());
		when(analysisRepository.save(any()))
				.thenThrow(new DataIntegrityViolationException("ux_curriculum_analysis_version_active"));

		assertThatThrownBy(() -> service().requestAnalysis(materialId, orgId, actorUserId, false))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_ANALYSIS_IN_PROGRESS));
	}

	@Test
	void rejectsWithoutCallingAiWhenAppLevelCheckAlreadySeesAnActiveAnalysis() throws Exception {
		UUID materialId = UUID.randomUUID();
		UUID orgId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		when(materialRepository.existsByMaterialIdAndOrgId(materialId, orgId)).thenReturn(true);
		CurriculumVersion version = latestVersion(versionId);
		when(curriculumVersionRepository.findAllByMaterialIdAndOrgIdOrderByVersionNoDesc(materialId, orgId))
				.thenReturn(List.of(version));
		when(analysisRepository.findAllByVersionIdAndStatusIn(eq(versionId),
				eq(List.of(CurriculumAnalysisStatus.PENDING, CurriculumAnalysisStatus.RUNNING))))
				.thenReturn(List.of(mock(com.bigproject.backend.domain.curriculum.domain.CurriculumAnalysis.class)));

		assertThatThrownBy(() -> service().requestAnalysis(materialId, orgId, actorUserId, false))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode()).isEqualTo(CurriculumErrorCode.CURRICULUM_ANALYSIS_IN_PROGRESS));

		org.mockito.Mockito.verifyNoInteractions(aiCurriculumClient);
	}
}
