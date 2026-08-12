package com.bigproject.backend.domain.manager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ManagerViewCanonicalSqlContractTest {
	@Test
	void canonicalDdlAndViewsContainEveryManagerApiSource() throws IOException {
		Path ddlPath = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_DDL.sql");
		Path viewsPath = Path.of("docs/table-definition/테이블정의서_v07_교육생홈_View.sql");
		assumeTrue(Files.exists(ddlPath) && Files.exists(viewsPath),
				"정본 DDL/View 문서가 없어 계약 검증을 건너뜁니다.");

		String ddl = Files.readString(ddlPath);
		String views = Files.readString(viewsPath);

		assertThat(ddl).contains("reminder_dispatch", "measurement_attempt", "interview_candidate_reason");
		assertThat(views).contains(
				"assessment_round_attendance", "manager_invalid_attempt_review_view",
				"manager_interview_list_view", "manager_class_heatmap_view",
				"manager_team_heatmap_view", "manager_trainee_heatmap_view",
				"manager_retried_trainee_heatmap_view", "manager_trainee_roster_view",
				"manager_trainee_detail_timeline_view");
		assertThat(views).contains("comparison_attempt_type", "concept_result_items", "row_aggregation_status");
	}
}
