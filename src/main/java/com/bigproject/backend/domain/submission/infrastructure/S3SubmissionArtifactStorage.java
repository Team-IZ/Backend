package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.application.SubmissionArtifactStorage;
import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 제출물을 S3에 저장한다. 키는 {@code <orgId>/<teamId>/<submissionId>.zip}이다.
 *
 * <p>기관 이름이나 업로드된 파일명을 키에 쓰지 않는다. 사용자 입력이 섞이면 동명 파일 충돌이 생기는데,
 * 세 값이 전부 서버가 만든 UUID면 그 문제가 사라진다. 원본 파일명은
 * {@code submission_artifact.original_file_name}에 그대로 보존한다 — 로컬 디스크 구현({@code
 * LocalSubmissionArtifactStorage}, 대체됨)이 쓰던 것과 같은 원칙이다.
 *
 * <p>업로드 전에 {@code SubmissionService.requireReadableZip}이 이미 {@code app.submission.max-zip-bytes}
 * (기본 50MB)로 크기를 막아 놓으므로, 전체를 메모리에 올려 해시를 계산하고 그대로 업로드해도 안전하다.
 * {@code load()}도 같은 이유로 바이트 배열째 읽어 {@link ByteArrayResource}로 돌려준다 — multipart 전송에
 * 필요한 {@code Content-Length}를 스트림 없이도 정확히 보고할 수 있다.
 */
@Component
public class S3SubmissionArtifactStorage implements SubmissionArtifactStorage {

	private static final String ZIP_CONTENT_TYPE = "application/zip";

	private final S3Client s3Client;
	private final String bucket;

	public S3SubmissionArtifactStorage(S3Client s3Client, @Value("${submission.storage.bucket}") String bucket) {
		this.s3Client = s3Client;
		this.bucket = bucket;
	}

	@Override
	public StoredArtifact store(UUID orgId, UUID teamId, UUID submissionId, InputStream content) {
		String key = orgId + "/" + teamId + "/" + submissionId + ".zip";

		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}

		byte[] bytes;
		// 해시를 위해 파일을 두 번 읽지 않도록 읽으면서 같이 계산한다.
		try (DigestInputStream digestStream = new DigestInputStream(content, digest)) {
			bytes = digestStream.readAllBytes();
		} catch (IOException exception) {
			throw new SubmissionException(SubmissionErrorCode.ARTIFACT_STORE_FAILED,
					SubmissionErrorCode.ARTIFACT_STORE_FAILED.defaultMessage(), exception);
		}

		try {
			PutObjectRequest putRequest = PutObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.contentType(ZIP_CONTENT_TYPE)
					.build();
			s3Client.putObject(putRequest, RequestBody.fromBytes(bytes));
		} catch (SdkException exception) {
			throw new SubmissionException(SubmissionErrorCode.ARTIFACT_STORE_FAILED,
					SubmissionErrorCode.ARTIFACT_STORE_FAILED.defaultMessage(), exception);
		}

		return new StoredArtifact(
				"s3://" + bucket + "/" + key,
				HexFormat.of().formatHex(digest.digest()),
				bytes.length
		);
	}

	@Override
	public Resource load(String storageUri) {
		URI uri;
		try {
			uri = URI.create(storageUri);
		} catch (RuntimeException exception) {
			throw new SubmissionException(SubmissionErrorCode.ARTIFACT_STORE_FAILED,
					"제출물 저장 위치를 해석할 수 없습니다: " + storageUri, exception);
		}
		if (!"s3".equals(uri.getScheme()) || uri.getHost() == null) {
			throw new SubmissionException(SubmissionErrorCode.ARTIFACT_STORE_FAILED,
					"제출물 저장 위치를 해석할 수 없습니다: " + storageUri);
		}

		String bucketName = uri.getHost();
		String key = uri.getPath();
		if (key != null && key.startsWith("/")) {
			key = key.substring(1);
		}

		try {
			GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucketName).key(key).build();
			byte[] bytes = s3Client.getObjectAsBytes(getRequest).asByteArray();
			return new ByteArrayResource(bytes);
		} catch (SdkException exception) {
			throw new SubmissionException(SubmissionErrorCode.ARTIFACT_STORE_FAILED,
					"제출물 파일을 읽을 수 없습니다: " + storageUri, exception);
		}
	}
}
