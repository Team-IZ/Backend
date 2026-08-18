package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 22차 R2 — 교안 등록이 <b>크기와 무관하게</b> 코드 없는 500으로 나가던 자리.
 *
 * <p>저장 뿌리가 상대 경로로 박혀 있어 배포 환경(읽기 전용 파일시스템)에서 첫 줄부터 실패했고,
 * 그 {@code UncheckedIOException}이 {@code ApiExceptionHandler}를 거치지 못했다.
 */
class CurriculumFileStorageTest {

	private final FileStorageService service = new FileStorageService();

	private static MockMultipartFile pdf(byte[] content) {
		return new MockMultipartFile("file", "curriculum.pdf", "application/pdf", content);
	}

	/**
	 * 요청한 크기의 <b>진짜 PDF</b>를 만든다 — 앞 5바이트가 {@code %PDF-}다.
	 *
	 * <p>전에는 {@code new byte[…]}(0으로 찬 배열)을 그대로 올렸다. 시그니처 검증이 생기면서
	 * 그 바이트는 PDF가 아니라 400으로 끊기는데, <b>이 테스트가 보려는 것은 형식이 아니라 크기</b>라
	 * 내용만 실제 PDF로 맞춘다.
	 */
	private static byte[] pdfOfSize(int size) {
		byte[] content = new byte[size];
		System.arraycopy("%PDF-1.7".getBytes(), 0, content, 0, "%PDF-1.7".getBytes().length);
		return content;
	}

	@Test
	void storesUnderTheConfiguredRootSoTheEnvironmentCanPointItSomewhereWritable(@TempDir Path root) {
		ReflectionTestUtils.setField(service, "storageRoot", root.toString());

		FileStorageService.StoredFile stored = service.store(pdf("%PDF-1.7 …".getBytes()));

		assertThat(Path.of(java.net.URI.create(stored.fileUri()))).exists().hasParent(root);
		// 파일명은 UUID로 새로 만들되 원본 이름은 화면 표시용으로 남긴다.
		assertThat(stored.originalFileName()).isEqualTo("curriculum.pdf");
		assertThat(stored.fileUri()).doesNotContain("curriculum.pdf");
	}

	/**
	 * 저장 자리가 쓸 수 없을 때 <b>코드를 가진 실패</b>로 나가야 한다. 종전에는
	 * {@code UncheckedIOException}이 그대로 올라가 화면이 재시도를 안내할 근거가 없었다.
	 */
	@Test
	void failsWithACodeInsteadOfANakedFiveHundredWhenTheRootIsNotWritable(@TempDir Path root) {
		// 뿌리 자리에 파일을 놓아 디렉터리를 만들 수 없게 한다 — 읽기 전용 파일시스템과 같은 결과다.
		Path blocked = root.resolve("blocked");
		ReflectionTestUtils.setField(service, "storageRoot", blocked.resolve("curricula").toString());
		assertThatThrownBy(() -> {
			java.nio.file.Files.writeString(blocked, "not a directory");
			service.store(pdf("%PDF-1.7".getBytes()));
		}).isInstanceOfSatisfying(CurriculumException.class, exception ->
				assertThat(exception.errorCode())
						.isEqualTo(CurriculumErrorCode.CURRICULUM_FILE_STORE_FAILED));
	}

	/** 크기는 실패 조건이 아니다 — 작은 파일도 큰 파일도 같은 경로를 탄다. */
	@Test
	void doesNotTreatSizeAsAFailureCondition(@TempDir Path root) {
		ReflectionTestUtils.setField(service, "storageRoot", root.toString());

		assertThat(service.store(pdf(pdfOfSize(50 * 1024))).fileSizeBytes()).isEqualTo(50 * 1024);
		assertThat(service.store(pdf(pdfOfSize(3 * 1024 * 1024))).fileSizeBytes()).isEqualTo(3 * 1024 * 1024);
	}
}
