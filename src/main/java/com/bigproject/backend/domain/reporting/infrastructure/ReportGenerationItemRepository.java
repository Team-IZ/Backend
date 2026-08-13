package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.ReportGenerationItem;
import com.bigproject.backend.domain.reporting.domain.ReportGenerationItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** {@code report_generation_item} 읽기·쓰기. */
public interface ReportGenerationItemRepository extends JpaRepository<ReportGenerationItem, UUID> {

	/** 폴링 대상. {@code QUEUED}·{@code RUNNING}을 넘겨 부른다. */
	List<ReportGenerationItem> findByStatusIn(Collection<ReportGenerationItemStatus> statuses);

	/** 한 실행에 속한 전부. run을 닫아도 되는지(모두 종료됐는지) 판정한다. */
	List<ReportGenerationItem> findByGenerationRunId(UUID generationRunId);
}
