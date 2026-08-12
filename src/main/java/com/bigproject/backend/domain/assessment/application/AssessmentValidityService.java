package com.bigproject.backend.domain.assessment.application;

import com.bigproject.backend.domain.assessment.domain.AssessmentValidityErrorCode;
import com.bigproject.backend.domain.assessment.domain.AssessmentValidityRepository;
import com.bigproject.backend.domain.assessment.presentation.dto.AssessmentValidityResponse;
import com.bigproject.backend.domain.assessment.presentation.dto.UpdateAssessmentValidityRequest;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.ManagerViewScopeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssessmentValidityService {
	private final ManagerViewScopeGuard scopeGuard;
	private final AssessmentValidityRepository repository;

	@Transactional
	public AssessmentValidityResponse update(
			String email, UUID attemptId, String rawRequestId, UpdateAssessmentValidityRequest request) {
		var actor = scopeGuard.requireManager(email);
		validate(request);
		var before = repository.lockAttempt(attemptId, actor.userId())
				.orElseThrow(() -> new ApiException(AssessmentValidityErrorCode.ASSESSMENT_ATTEMPT_NOT_FOUND));
		String targetStatus = request.decision() == UpdateAssessmentValidityRequest.Decision.CONFIRM_INVALID
				? "CONFIRMED_INVALID" : "RESTORED_VALID";
		if (!("PENDING".equals(before.validityStatus()) && "CONFIRMED_INVALID".equals(targetStatus))
				&& !("CONFIRMED_INVALID".equals(before.validityStatus()) && "RESTORED_VALID".equals(targetStatus))) {
			throw new ApiException(AssessmentValidityErrorCode.VALIDITY_REVIEW_NOT_ACTIONABLE);
		}
		if (repository.updateDecision(attemptId, request.rowVersion(), targetStatus,
				request.reasonCode().name(), request.note(), actor.userId()) != 1) {
			throw new ApiException(AssessmentValidityErrorCode.VALIDITY_ROW_VERSION_CONFLICT);
		}
		if ("CONFIRMED_INVALID".equals(targetStatus)) repository.activateInvalidReason(before);
		else repository.resolveInvalidReason(before);
		var after = repository.lockAttempt(attemptId, actor.userId()).orElseThrow();
		repository.insertAudit(before, after, actor.userId(), requestId(rawRequestId));
		return new AssessmentValidityResponse(after.attemptId(), after.validityStatus(),
				after.decisionReasonCode(), after.decisionNote(), after.rowVersion(), after.reviewedAt());
	}

	private void validate(UpdateAssessmentValidityRequest request) {
		boolean confirmedReason = request.reasonCode() == UpdateAssessmentValidityRequest.DecisionReason.REVIEWED_VIOLATION_CONFIRMED;
		if ((request.decision() == UpdateAssessmentValidityRequest.Decision.CONFIRM_INVALID) != confirmedReason) {
			throw new ApiException(AssessmentValidityErrorCode.VALIDITY_DECISION_INVALID);
		}
	}

	private UUID requestId(String raw) {
		if (raw == null || raw.isBlank()) return UUID.randomUUID();
		try { return UUID.fromString(raw); }
		catch (IllegalArgumentException exception) { return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)); }
	}
}
