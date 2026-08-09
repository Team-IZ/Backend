package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 교육생 초대 재발송 요청(11차 R2).
 *
 * <p>명단 탭이 <b>여러 명을 골라</b> 보내는 화면이라 목록으로 받는다 — 한 명씩 받는 경로였다면
 * 고른 수만큼 호출이 나가고 중간에 하나가 실패했을 때 화면이 몇 명에게 나갔는지 다시 세야 한다.
 */
@Schema(description = "교육생 초대 재발송 요청")
public record ResendTraineeInvitationsRequest(

        @Schema(description = """
                재발송할 교육생 사용자 ID 목록입니다. 명단 응답의 `traineeId`를 그대로 넣습니다.

                재발송 버튼은 `pendingInvitationTokenId`가 `null`이 아닌 행에서만 켜면 됩니다 —
                이미 활성화됐거나 초대가 취소된 계정을 넣으면 그 행만 `failures`로 돌아옵니다.
                """)
        @NotEmpty(message = "재발송할 교육생을 1명 이상 지정해야 합니다.")
        @Size(max = 200, message = "한 번에 최대 200명까지 재발송할 수 있습니다.")
        List<UUID> traineeIds
) {
}
