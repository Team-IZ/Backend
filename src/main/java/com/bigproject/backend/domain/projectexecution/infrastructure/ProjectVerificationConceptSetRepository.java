package projectexecution.infrastructure;

import projectexecution.domain.ConceptSetStatus;
import projectexecution.domain.ProjectVerificationConceptSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProjectVerificationConceptSetRepository extends JpaRepository<ProjectVerificationConceptSet, UUID> {

    Optional<ProjectVerificationConceptSet> findByProjectIdAndStatus(UUID projectId, ConceptSetStatus status);
}