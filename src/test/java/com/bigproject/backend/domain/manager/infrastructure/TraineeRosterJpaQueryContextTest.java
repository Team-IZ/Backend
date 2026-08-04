package com.bigproject.backend.domain.manager.infrastructure;

import com.bigproject.backend.domain.academicoperations.infrastructure.CohortRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ManagerAssignmentRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ProjectRepository;
import com.bigproject.backend.domain.academicoperations.infrastructure.ProjectVerificationConceptRepository;
import com.bigproject.backend.domain.assessment.infrastructure.ProjectAssessmentRoundRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 교육생 명부에서 JdbcTemplate 대신 JPA로 옮긴 단순 조회들의 JPQL을 부트스트랩 시점에 검증한다.
 *
 * <p>Spring Data는 리포지토리 프록시를 만들 때 {@code @Query}의 JPQL을 파싱하므로, 엔터티·필드명 오타나
 * 잘못된 조인·생성자 표현식이 있으면 이 테스트의 컨텍스트 로딩이 실패한다. 쿼리를 실행하지는 않기 때문에
 * 스키마가 필요 없고 Docker 없이도 돌아간다. 실제 데이터 동작 검증은 PostgreSQL 통합 테스트가 담당한다.
 */
@DataJpaTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.datasource.url=jdbc:h2:mem:trainee-roster-jpa-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password="
})
class TraineeRosterJpaQueryContextTest {
	@Autowired
	private CohortRepository cohortRepository;
	@Autowired
	private ManagerAssignmentRepository managerAssignmentRepository;
	@Autowired
	private ProjectRepository projectRepository;
	@Autowired
	private ProjectAssessmentRoundRepository projectAssessmentRoundRepository;
	@Autowired
	private ProjectVerificationConceptRepository projectVerificationConceptRepository;

	@Test
	void validatesRosterLookupQueriesAtBootstrap() {
		assertThat(cohortRepository).isNotNull();
		assertThat(managerAssignmentRepository).isNotNull();
		assertThat(projectRepository).isNotNull();
		assertThat(projectAssessmentRoundRepository).isNotNull();
		assertThat(projectVerificationConceptRepository).isNotNull();
	}
}
