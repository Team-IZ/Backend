package com.bigproject.backend.domain.reporting.infrastructure;

import com.bigproject.backend.domain.reporting.domain.ReportEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * {@code report_evidence} 쓰기 전용에 가깝다. 읽는 쪽은 {@code trainee_report_problem_view}를 거치는
 * {@link JdbcTraineeReportQueryRepository}이지 이 인터페이스가 아니다.
 */
public interface ReportEvidenceRepository extends JpaRepository<ReportEvidence, UUID> {
}
