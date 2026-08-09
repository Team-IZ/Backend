package com.bigproject.backend.domain.curriculum.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "curriculum_section")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CurriculumSection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "source_analysis_id", nullable = false, updatable = false)
    private UUID sourceAnalysisId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private Integer sequenceNo;

    @Column(name = "title", nullable = false, length = 200, updatable = false)
    private String title;

    @Column(name = "page_start", nullable = false, updatable = false)
    private Integer pageStart;

    @Column(name = "page_end", nullable = false, updatable = false)
    private Integer pageEnd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "keywords", nullable = false, updatable = false)
    private List<String> keywords;

    @Column(name = "confidence", nullable = false, updatable = false)
    private BigDecimal confidence;

    @Builder
    private CurriculumSection(UUID versionId, UUID sourceAnalysisId, Integer sequenceNo, String title,
                              Integer pageStart, Integer pageEnd, List<String> keywords,
                              BigDecimal confidence) {
        this.versionId = versionId;
        this.sourceAnalysisId = sourceAnalysisId;
        this.sequenceNo = sequenceNo;
        this.title = title;
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.keywords = keywords;
        this.confidence = confidence;
    }

    public static CurriculumSection create(UUID versionId, UUID sourceAnalysisId, int sequenceNo, String title,
                                           int pageStart, int pageEnd, List<String> keywords,
                                           BigDecimal confidence) {
        return CurriculumSection.builder()
                .versionId(versionId).sourceAnalysisId(sourceAnalysisId).sequenceNo(sequenceNo)
                .title(title).pageStart(pageStart).pageEnd(pageEnd)
                .keywords(keywords).confidence(confidence)
                .build();
    }
}