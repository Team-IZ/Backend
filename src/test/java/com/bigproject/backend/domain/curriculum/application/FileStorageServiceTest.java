package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 교안 업로드의 형식 판정은 <b>내용</b>으로만 한다. 확장자와 Content-Type은 보내는 쪽이 적는
 * 값이라 근거가 되지 못한다 — 여기서 검사하는 것이 정확히 그 원칙이다.
 */
class FileStorageServiceTest {

	private S3Client s3Client;
	private FileStorageService fileStorageService;

	@BeforeEach
	void setUp() {
		s3Client = Mockito.mock(S3Client.class);
		when(s3Client.putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
				any(software.amazon.awssdk.core.sync.RequestBody.class)))
				.thenReturn(PutObjectResponse.builder().build());
		fileStorageService = new FileStorageService(s3Client, "test-bucket", 1024L);
	}

	@Test
	void storesAFileWhoseContentActuallyStartsWithThePdfSignature() {
		MockMultipartFile file = new MockMultipartFile(
				"file", "교안.pdf", "application/pdf", pdfBytes());

		FileStorageService.StoredFile stored = fileStorageService.store(file);

		assertThat(stored.originalFileName()).isEqualTo("교안.pdf");
		assertThat(stored.fileSizeBytes()).isEqualTo(pdfBytes().length);
		assertThat(stored.contentHash()).isNotBlank();
		assertThat(stored.fileUri()).startsWith("s3://test-bucket/");
	}

	/**
	 * 이름만 {@code .pdf}로 바꾸고 Content-Type까지 맞춘, 종전에 그대로 통과하던 요청이다.
	 * 둘 다 클라이언트가 정하는 값이므로 이것이 막히지 않으면 검사 자체가 무의미하다.
	 */
	@Test
	void rejectsAFileThatOnlyClaimsToBeAPdf() {
		// 내용은 JPEG(FF D8 FF E0 ... JFIF)인데 이름과 Content-Type만 PDF다.
		byte[] jpegBytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 'J', 'F', 'I', 'F'};
		MockMultipartFile disguised = new MockMultipartFile(
				"file", "교안.pdf", "application/pdf", jpegBytes);

		assertThatThrownBy(() -> fileStorageService.store(disguised))
				.isInstanceOf(CurriculumException.class)
				.hasFieldOrPropertyWithValue("errorCode", CurriculumErrorCode.CURRICULUM_FILE_TYPE_INVALID);
	}

	/** 서명보다 짧은 파일에서 배열 범위를 넘지 않고 같은 코드로 끊어야 한다. */
	@Test
	void rejectsAFileShorterThanTheSignatureItself() {
		MockMultipartFile tiny = new MockMultipartFile(
				"file", "교안.pdf", "application/pdf", "%PD".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> fileStorageService.store(tiny))
				.isInstanceOf(CurriculumException.class)
				.hasFieldOrPropertyWithValue("errorCode", CurriculumErrorCode.CURRICULUM_FILE_TYPE_INVALID);
	}

	/**
	 * 톰캣 상한(60MB)에 닿기 전에 앱이 먼저 판정해야 도메인 코드가 붙는다.
	 * 톰캣에서 걸리면 요청이 컨트롤러에 닿지 못해 코드 없는 500이 나간다.
	 */
	@Test
	void rejectsAFileOverTheApplicationLimitBeforeTomcatCanSwallowIt() {
		byte[] oversized = new byte[2048];
		System.arraycopy(pdfBytes(), 0, oversized, 0, pdfBytes().length);
		MockMultipartFile file = new MockMultipartFile("file", "교안.pdf", "application/pdf", oversized);

		assertThatThrownBy(() -> fileStorageService.store(file))
				.isInstanceOf(CurriculumException.class)
				.hasFieldOrPropertyWithValue("errorCode", CurriculumErrorCode.CURRICULUM_FILE_TOO_LARGE);
	}

	/** 클라이언트가 보낸 Content-Type을 그대로 저장하면 위 검사를 통과한 의미가 없어진다. */
	@Test
	void recordsTheContentTypeTheServerVerifiedRatherThanTheOneTheClientSent() {
		MockMultipartFile file = new MockMultipartFile(
				"file", "교안.pdf", "application/octet-stream", pdfBytes());

		assertThat(fileStorageService.store(file).mimeType()).isEqualTo("application/pdf");
	}

	private byte[] pdfBytes() {
		return "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.UTF_8);
	}
}
