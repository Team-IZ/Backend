package com.bigproject.backend.domain.submission.infrastructure;

import com.bigproject.backend.domain.submission.application.SubmissionArtifactStorage;
import com.bigproject.backend.domain.submission.domain.SubmissionErrorCode;
import com.bigproject.backend.domain.submission.domain.SubmissionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
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

	/**
	 * <b>기동 시점에 루트를 만들고 쓸 수 있는지 확인한다.</b> 못 쓰면 여기서 기동을 실패시킨다.
	 *
	 * <p>이 검사가 없어서 실제로 사고가 났다(35차 R1 · 37차 R1b). 컨테이너의 {@code /app}이 root 소유라
	 * 실행 유저가 {@code var/submissions}를 만들 수 없었는데, 그 사실이 <b>학생이 ZIP을 올린 순간에야</b>
	 * {@code ARTIFACT_STORE_FAILED}(500)로 드러났다 — 제출이 며칠 동안 막혔고 원인을 찾는 데 사람이 붙었다.
	 *
	 * <p>기동이 안 뜨는 편이 낫다. 배포가 실패하면 그 자리에서 알지만, 학생마다 500을 맞으면
	 * "가끔 제출이 안 된다"는 신고로 흘러 들어와 원인에 닿기까지 오래 걸린다.
	 */
	public LocalSubmissionArtifactStorage(@Value("${app.submission.storage-root}") Path storageRoot) {
		this.storageRoot = storageRoot.toAbsolutePath().normalize();
		try {
			Files.createDirectories(this.storageRoot);
		} catch (IOException exception) {
			throw new IllegalStateException(
					"제출물 저장 루트를 만들 수 없습니다: " + this.storageRoot
							+ " — 컨테이너라면 실행 유저의 쓰기 권한을, 그 밖이면 SUBMISSION_STORAGE_ROOT를 확인하세요.",
					exception);
		}
		if (!Files.isWritable(this.storageRoot)) {
			throw new IllegalStateException(
					"제출물 저장 루트에 쓸 수 없습니다: " + this.storageRoot
							+ " — 실행 유저에게 쓰기 권한이 없습니다.");
		}
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

	@Override
	public Resource load(String storageUri) {
		Path path;
		try {
			path = Path.of(URI.create(storageUri)).toAbsolutePath().normalize();
		} catch (RuntimeException exception) {
			throw new SubmissionException(SubmissionErrorCode.ARTIFACT_STORE_FAILED,
					"제출물 저장 위치를 해석할 수 없습니다: " + storageUri, exception);
		}

		// storage_uri 는 우리가 만든 값이지만, DB 를 거쳐 돌아오는 값을 그대로 파일 경로로 쓰는 자리라
		// 루트 밖을 가리키면 거절한다. 값이 오염되는 경로가 생겨도 파일시스템 전체가 열리지는 않는다.
		if (!path.startsWith(storageRoot)) {
			throw new SubmissionException(SubmissionErrorCode.ARTIFACT_STORE_FAILED,
					"제출물 저장 위치가 저장소 루트 밖을 가리킵니다: " + storageUri);
		}
		if (!Files.isReadable(path)) {
			throw new SubmissionException(SubmissionErrorCode.ARTIFACT_STORE_FAILED,
					"제출물 파일을 읽을 수 없습니다: " + storageUri);
		}
		return new FileSystemResource(path);
	}

	private void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// 원인 예외를 덮지 않도록 삼킨다. 남은 조각 파일은 다음 제출이 같은 경로로 덮어쓴다.
		}
	}
}
