package com.bigproject.backend.domain.curriculum.domain;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "curriculum_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurriculumVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private Integer versionNo;

    @Column(name = "original_file_name", nullable = false, updatable = false)
    private String originalFileName;

    @Column(name = "file_uri", nullable = false, updatable = false)
    private String fileUri;

    @Column(name = "mime_type", nullable = false, updatable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes", nullable = false, updatable = false)
    private Long fileSizeBytes;

    @Column(name = "content_hash", nullable = false, updatable = false, length = 128)
    private String contentHash;

    @Column(name = "page_count")
    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CurriculumVersionStatus status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private CurriculumVersion(UUID materialId, Integer versionNo, String originalFileName,
                              String fileUri, Long fileSizeBytes, String contentHash, UUID createdBy) {
        this.materialId = materialId;
        this.versionNo = versionNo;
        this.originalFileName = originalFileName;
        this.fileUri = fileUri;
        this.mimeType = "application/pdf";
        this.fileSizeBytes = fileSizeBytes;
        this.contentHash = contentHash;
        this.status = CurriculumVersionStatus.ACTIVE;
        this.createdBy = createdBy;
        this.createdAt = OffsetDateTime.now();
    }

    public static CurriculumVersion createFirstVersion(UUID materialId, String originalFileName,
                                                       String fileUri, Long fileSizeBytes,
                                                       String contentHash, UUID createdBy) {
        return new CurriculumVersion(materialId, 1, originalFileName, fileUri, fileSizeBytes, contentHash, createdBy);
    }

    public static CurriculumVersion createNextVersion(UUID materialId, int nextVersionNo, String originalFileName,
                                                      String fileUri, Long fileSizeBytes,
                                                      String contentHash, UUID createdBy) {
        return new CurriculumVersion(materialId, nextVersionNo, originalFileName, fileUri, fileSizeBytes, contentHash, createdBy);
    }

    public void confirmPageCount(int pageCount) {
        if (this.pageCount != null) {
            return;
        }
        this.pageCount = pageCount;
    }

    public void deactivate() {
        this.status = CurriculumVersionStatus.INACTIVE;
    }
}