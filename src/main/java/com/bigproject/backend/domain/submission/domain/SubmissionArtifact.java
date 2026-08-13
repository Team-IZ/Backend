package com.bigproject.backend.domain.submission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * submission_artifact 테이블 매핑 엔티티. ZIP 제출물 1건의 저장·검증 상태를 갖는다.
 *
 * <p>{@code submissionId}를 처음부터 연결한다. 이 테이블에는 {@code team_id}도 {@code assessment_round_id}도 없어
 * 연결되지 않은 artifact를 팀·회차로 찾을 방법이 없고, {@code assessment_round_attendance} 조인도
 * {@code submission_id} 기준이라 고아 행은 조회에 아예 보이지 않는다.
 */
@Getter
@Entity
@Table(name = "submission_artifact")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubmissionArtifact {

	@Id
	@UuidGenerator
	@Column(name = "artifact_id", updatable = false, nullable = false)
	private UUID artifactId;

	@Column(name = "submission_id", updatable = false)
	private UUID submissionId;

	@Column(name = "artifact_type", nullable = false, updatable = false, length = 100)
	private String artifactType;

	@Column(name = "original_file_name", nullable = false, updatable = false, length = 255)
	private String originalFileName;

	@Column(name = "content_type", nullable = false, updatable = false, length = 100)
	private String contentType;

	@Column(name = "storage_uri", nullable = false, columnDefinition = "text")
	private String storageUri;

	/** 안전 추출 경로. VERIFIED로 전이할 때 NOT NULL이 되며, 추출 전에는 NULL이다. */
	@Column(name = "safe_extract_uri", columnDefinition = "text")
	private String safeExtractUri;

	@Column(name = "content_hash", nullable = false, updatable = false, length = 128)
	private String contentHash;

	@Column(name = "file_size_bytes", nullable = false, updatable = false)
	private Long fileSizeBytes;

	/**
	 * 적용된 파일당 상한. 정의서에 원천 컬럼이 없어 애플리케이션 상수를 그대로 기록한다(결정 ③).
	 * {@code organization_policy.storage_limit_bytes}는 기관 총 저장 용량이라 여기 쓸 수 없다.
	 */
	@Column(name = "applied_max_file_bytes", nullable = false, updatable = false)
	private Long appliedMaxFileBytes;

	@Enumerated(EnumType.STRING)
	@Column(name = "validation_status", nullable = false, length = 100)
	private ArtifactValidationStatus validationStatus;

	@Column(name = "validation_failure_code", length = 100)
	private String validationFailureCode;

	@Column(name = "validated_at")
	private Instant validatedAt;

	@Column(name = "extraction_policy_version")
	private Integer extractionPolicyVersion;

	private SubmissionArtifact(
			UUID submissionId,
			String artifactType,
			String originalFileName,
			String contentType,
			String storageUri,
			String contentHash,
			long fileSizeBytes,
			long appliedMaxFileBytes
	) {
		this.submissionId = submissionId;
		this.artifactType = artifactType;
		this.originalFileName = originalFileName;
		this.contentType = contentType;
		this.storageUri = storageUri;
		this.contentHash = contentHash;
		this.fileSizeBytes = fileSizeBytes;
		this.appliedMaxFileBytes = appliedMaxFileBytes;
		this.validationStatus = ArtifactValidationStatus.VALIDATING;
	}

	/**
	 * 업로드 접수 직후 상태로 만든다.
	 *
	 * <p>{@code ck_submission_artifact_validation_status_2}가 VALIDATING 분기에서
	 * {@code validatedAt}·{@code validationFailureCode}·{@code safeExtractUri} 전부 NULL을 요구하므로
	 * 이 생성자는 셋 다 건드리지 않는다.
	 */
	public static SubmissionArtifact validating(
			UUID submissionId,
			String artifactType,
			String originalFileName,
			String contentType,
			String storageUri,
			String contentHash,
			long fileSizeBytes,
			long appliedMaxFileBytes
	) {
		return new SubmissionArtifact(
				submissionId,
				artifactType,
				originalFileName,
				contentType,
				storageUri,
				contentHash,
				fileSizeBytes,
				appliedMaxFileBytes
		);
	}
}
