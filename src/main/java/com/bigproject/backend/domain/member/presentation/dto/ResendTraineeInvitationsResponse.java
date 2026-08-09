package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.application.TraineeRosterService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * 교육생 초대 재발송 결과(11차 R2).
 *
 * <p>{@code registerTrainees}와 같은 모양이다 — {@code requestedCount} · {@code invitationSentCount} ·
 * {@code failures[]}. 화면이 등록과 재발송을 한 컴포넌트로 그릴 수 있다.
 */
@Schema(description = "교육생 초대 재발송 결과")
public record ResendTraineeInvitationsResponse(

        @Schema(description = "요청에 담긴 교육생 수", example = "3") int requestedCount,

        @Schema(description = """
                **실제로 초대 메일이 나간 수**입니다. `registerTrainees`의 같은 이름 필드와 같은 뜻입니다.

                화면이 '3명에게 활성화 초대를 다시 보냈어요'라고 쓸 수 있는 근거가 이 값입니다 —
                받는 사람용 API(`POST /auth/invitations/resend`)는 계정 존재 여부를 숨기려 항상 같은 202를 주므로
                이 수를 알 수 없었습니다.
                """, example = "3") int invitationSentCount,

        @Schema(description = "재발송하지 못한 행만 담깁니다. 전부 성공하면 빈 배열입니다")
        List<Failure> failures
) {

    @Schema(name = "TraineeInvitationResendFailure", description = "재발송이 걸린 교육생 한 명")
    public record Failure(
            @Schema(description = "요청에 넣은 교육생 사용자 ID") UUID traineeId,

            @Schema(description = "그 교육생의 이메일. 명단에 없는 ID였으면 null", nullable = true) String email,

            @Schema(description = """
                    걸린 이유입니다.

                    | 값 | 뜻 | 화면이 할 일 |
                    |---|---|---|
                    | `NOT_FOUND` | 이 기수에 없는 교육생 | 명단이 낡았다 — 목록을 다시 읽는다 |
                    | `NOT_PENDING` | 이미 활성화됐거나 초대가 취소됨 | 재발송 버튼을 끈다 |
                    | `NO_INVITATION` | 초대 원장이 없다 | 재발송이 아니라 **초대**를 새로 보내야 한다 |
                    """)
            TraineeRosterService.InvitationResendStatus status
    ) {
    }

    public static ResendTraineeInvitationsResponse from(TraineeRosterService.InvitationResendResult result) {
        return new ResendTraineeInvitationsResponse(
                result.requestedCount(),
                result.invitationSentCount(),
                result.failures().stream()
                        .map(failure -> new Failure(failure.traineeId(), failure.email(), failure.status()))
                        .toList());
    }
}
