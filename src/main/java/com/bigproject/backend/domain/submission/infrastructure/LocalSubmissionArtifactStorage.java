package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.application.SubmissionArtifactStorage;
import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 제출물을 로컬 파일시스템에 저장한다. 경로는 {@code <root>/<orgId>/<teamId>/<submissionId>.zip}이다.
 *
 * <p>기관 이름이나 업로드된 파일명을 경로에 쓰지 않는다. 사용자 입력이 경로에 섞이면 경로 순회
 * ({@code ../})와 동명 파일 충돌이 생기는데, 세 값이 전부 서버가 만든 UUID면 두 문제가 함께 사라진다.
 * 원본 파일명은 {@code submission_artifact.original_file_name}에 그대로 보존한다.
 */
@Component
public class LocalSubmissionArtifactStorage implements SubmissionArtifactStorage {

	private final Path storageRoot;

	public LocalSubmissionArtifactStorage(@Value("${app.submission.storage-root}") Path storageRoot) {
		this.storageRoot = storageRoot.toAbsolutePath().normalize();
	}

	@Override
	public StoredArtifact store(UUID orgId, UUID teamId, UUID submissionId, InputStream content) {
		Path destination = storageRoot
				.resolve(orgId.toString())
				.resolve(teamId.toString())
				.resolve(submissionId + ".zip");

		try {
			Files.createDirectories(destination.getParent());

			// 해시를 위해 파일을 두 번 읽지 않도록 기록하면서 같이 계산한다. 업로드가 큰 편이라 왕복이 아깝다.
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			long sizeBytes;
			try (DigestInputStream digestStream = new DigestInputStream(content, digest);
					OutputStream out = Files.newOutputStream(destination)) {
				sizeBytes = digestStream.transferTo(out);
			}

			return new StoredArtifact(
					destination.toUri().toString(),
					HexFormat.of().formatHex(digest.digest()),
					sizeBytes
			);
		} catch (IOException exception) {
			// 실패한 경로에 조각 파일이 남으면 다음 제출의 크기·해시가 어긋나므로 지운다.
			deleteQuietly(destination);
			throw new SubmissionException(SubmissionErrorCode.ARTIFACT_STORE_FAILED,
					SubmissionErrorCode.ARTIFACT_STORE_FAILED.defaultMessage(), exception);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	private void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 원인 예외를 덮지 않도록 삼킨다. 남은 조각 파일은 다음 제출이 같은 경로로 덮어쓴다.
		}
	}
}
