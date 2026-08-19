package com.bigproject.backend.domain.curriculum.application;

import com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode;
import com.bigproject.backend.domain.curriculum.domain.CurriculumException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 22차 R2 — 교안 등록이 <b>크기와 무관하게</b> 코드 없는 500으로 나가던 자리.
 *
 * <p>당시는 저장 뿌리가 상대 경로로 박혀 있어 배포 환경(읽기 전용 파일시스템)에서 첫 줄부터 실패했고,
 * 그 {@code UncheckedIOException}이 {@code ApiExceptionHandler}를 거치지 못했다. S3로 옮긴 지금은
 * "쓸 수 없는 로컬 경로" 대신 "S3 업로드 실패"가 같은 자리를 대신한다 — 여전히 코드 있는 실패로
 * 나가야 한다는 요구사항 자체는 그대로다.
 */
class CurriculumFileStorageTest {

	private S3Client s3Client;
	private FileStorageService service;

	@BeforeEach
	void setUp() {
		s3Client = Mockito.mock(S3Client.class);
		service = new FileStorageService(s3Client, "test-bucket", 52_428_800L);
	}

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
	void storesUnderTheConfiguredBucketSoTheEnvironmentCanPointItSomewhereWritable() {
		when(s3Client.putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
				any(software.amazon.awssdk.core.sync.RequestBody.class)))
				.thenReturn(PutObjectResponse.builder().build());

		FileStorageService.StoredFile stored = service.store(pdf("%PDF-1.7 …".getBytes()));

		assertThat(stored.fileUri()).startsWith("s3://test-bucket/");
		// 파일명은 UUID로 새로 만들되 원본 이름은 화면 표시용으로 남긴다.
		assertThat(stored.originalFileName()).isEqualTo("curriculum.pdf");
		assertThat(stored.fileUri()).doesNotContain("curriculum.pdf");
	}

	/**
	 * 업로드가 실패했을 때 <b>코드를 가진 실패</b>로 나가야 한다. 종전에는
	 * {@code UncheckedIOException}이 그대로 올라가 화면이 재시도를 안내할 근거가 없었다.
	 */
	@Test
	void failsWithACodeInsteadOfANakedFiveHundredWhenTheUploadFails() {
		when(s3Client.putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
				any(software.amazon.awssdk.core.sync.RequestBody.class)))
				.thenThrow(SdkException.builder().message("bucket unreachable").build());

		assertThatThrownBy(() -> service.store(pdf("%PDF-1.7".getBytes())))
				.isInstanceOfSatisfying(CurriculumException.class, exception ->
						assertThat(exception.errorCode())
								.isEqualTo(CurriculumErrorCode.CURRICULUM_FILE_STORE_FAILED));
	}

	/** 크기는 실패 조건이 아니다 — 작은 파일도 큰 파일도 같은 경로를 탄다. */
	@Test
	void doesNotTreatSizeAsAFailureCondition() {
		when(s3Client.putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
				any(software.amazon.awssdk.core.sync.RequestBody.class)))
				.thenReturn(PutObjectResponse.builder().build());

		assertThat(service.store(pdf(pdfOfSize(50 * 1024))).fileSizeBytes()).isEqualTo(50 * 1024);
		assertThat(service.store(pdf(pdfOfSize(3 * 1024 * 1024))).fileSizeBytes()).isEqualTo(3 * 1024 * 1024);
	}
}
