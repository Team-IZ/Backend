package com.bigproject.backend.domain.curriculum.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "curriculum_analysis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurriculumAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "analysis_id", nullable = false, updatable = false)
    private UUID analysisId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "model_id", nullable = false, updatable = false)
    private UUID modelId;

    @Column(name = "retry_of_analysis_id", updatable = false)
    private UUID retryOfAnalysisId;

    @Column(name = "analysis_version", nullable = false, updatable = false)
    private Integer analysisVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CurriculumAnalysisStatus status;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "request_reason", length = 100)
    private String requestReason;

    @Column(name = "impact_acknowledged", nullable = false)
    private boolean impactAcknowledged;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "failed_at")
    private OffsetDateTime failedAt;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_stage", length = 100)
    private String failureStage;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "is_retryable")
    private Boolean isRetryable;

    @Column(name = "recovery_action", length = 50)
    private String recoveryAction;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private CurriculumAnalysis(UUID versionId, UUID modelId, UUID retryOfAnalysisId, Integer analysisVersion,
                               UUID idempotencyKey, String requestFingerprint, String requestReason,
                               boolean impactAcknowledged, UUID requestedBy) {
        OffsetDateTime now = OffsetDateTime.now();
        this.versionId = versionId;
        this.modelId = modelId;
        this.retryOfAnalysisId = retryOfAnalysisId;
        this.analysisVersion = analysisVersion;
        this.status = CurriculumAnalysisStatus.PENDING;
        this.fallbackUsed = false;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.requestReason = requestReason;
        this.impactAcknowledged = impactAcknowledged;
        this.requestedBy = requestedBy;
        this.requestedAt = now;
        this.createdAt = now;
    }

    public static CurriculumAnalysis createInitial(UUID versionId, UUID modelId, int analysisVersion,
                                                   UUID idempotencyKey, String requestFingerprint, UUID requestedBy) {
        return CurriculumAnalysis.builder()
                .versionId(versionId).modelId(modelId).analysisVersion(analysisVersion)
                .idempotencyKey(idempotencyKey).requestFingerprint(requestFingerprint)
                .impactAcknowledged(false).requestedBy(requestedBy)
                .build();
    }

    public static CurriculumAnalysis createReanalysis(UUID versionId, UUID modelId, UUID retryOfAnalysisId,
                                                      int analysisVersion, UUID idempotencyKey,
                                                      String requestFingerprint, String requestReason,
                                                      boolean impactAcknowledged, UUID requestedBy) {
        if (requestReason == null || requestReason.isBlank()) {
            throw new IllegalArgumentException("재분석은 사유가 필수입니다.");
        }
        return CurriculumAnalysis.builder()
                .versionId(versionId).modelId(modelId).retryOfAnalysisId(retryOfAnalysisId)
                .analysisVersion(analysisVersion).idempotencyKey(idempotencyKey)
                .requestFingerprint(requestFingerprint).requestReason(requestReason)
                .impactAcknowledged(impactAcknowledged).requestedBy(requestedBy)
                .build();
    }

    public void start() {
        if (this.status != CurriculumAnalysisStatus.PENDING) {
            throw new IllegalStateException("대기 상태의 분석만 시작할 수 있습니다.");
        }
        this.status = CurriculumAnalysisStatus.RUNNING;
        this.startedAt = OffsetDateTime.now();
    }

    public void succeed() {
        if (this.status != CurriculumAnalysisStatus.RUNNING) {
            throw new IllegalStateException("실행 중인 분석만 성공 처리할 수 있습니다.");
        }
        this.status = CurriculumAnalysisStatus.SUCCEEDED;
        this.completedAt = OffsetDateTime.now();
    }

    public void fail(String failureCode, String failureStage, String failureReason,
                     boolean retryable, String recoveryAction) {
        if (this.status == CurriculumAnalysisStatus.SUCCEEDED) {
            throw new IllegalStateException("이미 성공한 분석은 실패로 전이할 수 없습니다.");
        }
        this.status = CurriculumAnalysisStatus.FAILED;
        this.failedAt = OffsetDateTime.now();
        this.failureCode = failureCode;
        this.failureStage = failureStage;
        this.failureReason = failureReason;
        this.isRetryable = retryable;
        this.recoveryAction = recoveryAction;
    }
}