package com.bigproject.backend.domain.submission.application;

import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.util.UUID;

/**
 * 제출물 원본 파일 저장소.
 *
 * <p>지금 구현체는 로컬 파일시스템 하나뿐이다. 인스턴스가 여러 대가 되거나 마감 후 분석 배치가 별도
 * 프로세스로 분리되면 로컬 경로로는 파일을 찾을 수 없으므로, 그때 S3 구현체를 추가해 교체한다.
 * 그래서 저장 결과를 경로가 아니라 <b>URI</b>로 돌려준다 — {@code file:} 이든 {@code s3:} 든
 * {@code submission_artifact.storage_uri} 하나로 다룰 수 있다.
 */
public interface SubmissionArtifactStorage {

	/**
	 * 제출물을 저장하고 저장 위치와 무결성 값을 돌려준다.
	 *
	 * @return 저장 URI, SHA-256 content hash, 실제 기록된 바이트 수
	 */
	StoredArtifact store(UUID orgId, UUID teamId, UUID submissionId, InputStream content);

	/**
	 * 저장된 제출물을 읽는다. 분석 요청이 ZIP 본체를 AI로 실어 보낼 때 쓴다.
	 *
	 * <p>{@code Resource}로 돌려주는 이유는 multipart 전송 때문이다. {@code InputStream}만 주면
	 * 호출부가 길이를 모르고, 그러면 {@code RestClient}가 청크 전송으로 떨어져 서버에 따라
	 * {@code Content-Length}가 없는 파트를 거절한다. {@code Resource}는 길이를 함께 갖는다.
	 *
	 * @param storageUri {@code submission_artifact.storage_uri}에 저장된 값
	 * @throws com.bigproject.backend.domain.submission.domain.SubmissionException 파일이 없거나 읽을 수 없을 때
	 */
	Resource load(String storageUri);

	record StoredArtifact(String storageUri, String contentHash, long sizeBytes) {
	}
}
