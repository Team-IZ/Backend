package com.bigproject.backend.domain.projectexecution.application;

import com.bigproject.backend.domain.auth.domain.AuthUser;
import com.bigproject.backend.domain.member.domain.Role;
import com.bigproject.backend.domain.projectexecution.domain.CurrentRoundErrorCode;
import com.bigproject.backend.domain.projectexecution.domain.CurrentRoundQueryRepository;
import com.bigproject.backend.domain.projectexecution.domain.CurrentRoundQueryRepository.CurrentRoundRow;
import com.bigproject.backend.domain.projectexecution.domain.CurrentRoundStatus;
import com.bigproject.backend.domain.projectexecution.domain.DefaultActionCode;
import com.bigproject.backend.domain.projectexecution.presentation.dto.CurrentRoundResponse;
import com.bigproject.backend.global.exception.ApiException;
import com.bigproject.backend.global.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교육생 홈(TR-01) "지금 할 일 하나" 조회.
 *
 * <p>상태 판정은 {@code trainee_home_round_view}가 이미 끝내서 준다
 * ({@code representative_status}, {@code default_action_code}). 이 서비스는 그 값을
 * {@link CurrentRoundStatus}/{@link DefaultActionCode}로 옮기기만 하고 재판정하지 않는다 —
 * 뷰와 다른 우선순위를 여기서 다시 짜면 두 계약이 갈라진다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrentRoundService {

    private final CurrentUserResolver currentUserResolver;
    private final CurrentRoundQueryRepository currentRoundQueryRepository;

    public CurrentRoundResponse findCurrentRound() {
        AuthUser actor = currentUserResolver.resolveCurrentUser();
        if (actor.role() != Role.TRAINEE) {
            throw new ApiException(CurrentRoundErrorCode.NOT_A_TRAINEE);
        }

        return currentRoundQueryRepository.findCurrentRound(actor.userId())
                .map(this::toResponse)
                .orElseGet(CurrentRoundResponse::noActiveRound);
    }

    private CurrentRoundResponse toResponse(CurrentRoundRow row) {
        return new CurrentRoundResponse(
                CurrentRoundStatus.valueOf(row.representativeStatus()),
                DefaultActionCode.valueOf(row.defaultActionCode()),
                row.warningCodes(),
                row.actionUnavailableReasonCode(),
                row.projectId(),
                row.projectName(),
                row.assessmentRoundId(),
                row.roundNo(),
                row.roundName(),
                row.curriculumDisplayNames(),
                row.submissionDueAt(),
                row.canResubmit(),
                row.analysisFailureCode(),
                row.assessmentOpenAt(),
                row.assessmentCloseAt(),
                row.latestReviewAttemptId(),
                row.reviewDueAt(),
                row.reportPublishStatus(),
                row.managerName(),
                row.commitEmailStatus()
        );
    }
}
