package com.bigproject.backend.domain.curriculum.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "curriculum_teaches_mapping")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurriculumTeachesMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "mapping_id", nullable = false, updatable = false)
    private UUID mappingId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "teaches_id", nullable = false, updatable = false)
    private UUID teachesId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "section_id")
    private UUID sectionId;

    @Column(name = "source_analysis_id", nullable = false, updatable = false)
    private UUID sourceAnalysisId;

    @Column(name = "extracted_name", nullable = false, length = 200)
    private String extractedName;

    @Column(name = "source_description")
    private String sourceDescription;

    @Column(name = "page_start", nullable = false, updatable = false)
    private Integer pageStart;

    @Column(name = "page_end", nullable = false, updatable = false)
    private Integer pageEnd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_pages", nullable = false, columnDefinition = "jsonb")
    private List<Integer> sourcePages;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private Integer sequenceNo;

    @Column(name = "confidence", nullable = false, updatable = false)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_status", nullable = false, length = 30)
    private MappingStatus mappingStatus;

    @Builder
    private CurriculumTeachesMapping(UUID orgId, UUID teachesId, UUID versionId, UUID sectionId,
                                     UUID sourceAnalysisId, String extractedName, String sourceDescription,
                                     Integer pageStart, Integer pageEnd, List<Integer> sourcePages,
                                     Integer sequenceNo, BigDecimal confidence, MappingStatus mappingStatus) {
        this.orgId = orgId;
        this.teachesId = teachesId;
        this.versionId = versionId;
        this.sectionId = sectionId;
        this.sourceAnalysisId = sourceAnalysisId;
        this.extractedName = extractedName;
        this.sourceDescription = sourceDescription;
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.sourcePages = sourcePages;
        this.sequenceNo = sequenceNo;
        this.confidence = confidence;
        this.mappingStatus = mappingStatus;
    }

    public static CurriculumTeachesMapping create(UUID orgId, UUID teachesId, UUID versionId, UUID sectionId,
                                                  UUID sourceAnalysisId, String extractedName,
                                                  String sourceDescription, int pageStart, int pageEnd,
                                                  List<Integer> sourcePages, int sequenceNo, BigDecimal confidence,
                                                  MappingStatus mappingStatus) {
        if (mappingStatus == MappingStatus.ACTIVE && (sectionId == null || extractedName == null || extractedName.isBlank())) {
            throw new IllegalArgumentException("ACTIVE 매핑은 section_id와 extracted_name이 필수입니다.");
        }
        return CurriculumTeachesMapping.builder()
                .orgId(orgId)
                .teachesId(teachesId)
                .versionId(versionId)
                .sectionId(sectionId)
                .sourceAnalysisId(sourceAnalysisId)
                .extractedName(extractedName)
                .sourceDescription(sourceDescription)
                .pageStart(pageStart)
                .pageEnd(pageEnd)
                .sourcePages(sourcePages)
                .sequenceNo(sequenceNo)
                .confidence(confidence)
                .mappingStatus(mappingStatus)
                .build();
    }

    public void approve() {
        if (this.sectionId == null || this.extractedName == null || this.extractedName.isBlank()) {
            throw new IllegalStateException("section_id와 extracted_name 없이는 승인할 수 없습니다.");
        }
        this.mappingStatus = MappingStatus.ACTIVE;
    }

    public void deactivate() {
        this.mappingStatus = MappingStatus.INACTIVE;
    }
}