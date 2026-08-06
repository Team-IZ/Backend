package projectexecution.infrastructure;

import projectexecution.domain.ProjectCurriculum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectCurriculumRepository extends JpaRepository<ProjectCurriculum, UUID> {

    // "쓰인 회차" 조회용 — 이 교안 버전을 연결한 프로젝트 전체
    List<ProjectCurriculum> findAllByCurriculumVersionId(UUID curriculumVersionId);
}