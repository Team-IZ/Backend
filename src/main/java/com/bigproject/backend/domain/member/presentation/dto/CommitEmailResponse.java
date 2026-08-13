package com.bigproject.backend.domain.member.presentation.dto;

import com.bigproject.backend.domain.member.domain.CommitEmail;
import com.bigproject.backend.domain.member.domain.CommitEmailStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = """
		교육생 커밋 이메일 등록·검증 상태. 미등록이면 registered=false이고 나머지 필드는 모두 null입니다 \
		(DB CHECK가 all-or-nothing이라 부분 등록 상태는 존재하지 않습니다).""")
public record CommitEmailResponse(
		@Schema(description = "커밋 이메일 등록 여부. commitEmail 존재 여부에서 파생되는 값입니다.", example = "true")
		boolean registered,

		@Schema(description = "등록된 커밋 이메일. 미등록이면 null입니다.", example = "gildong@example.com",
				nullable = true)
		String commitEmail,

		// 값 집합을 인라인으로 다시 적지 않는다. 같은 세 값이 CommitEmailStatus 로도 나가고 있어
		// 생성기가 같은 뜻의 타입을 두 개 만들고, 한쪽만 늘어나면 조용히 갈라진다(20차 R9).
		@Schema(
				description = """
						커밋 이메일 검증 상태. PUT으로 등록·변경하면 항상 PENDING이 되며, \
						VERIFIED는 별도 검증 완료 경로에서만 부여됩니다.""",
				implementation = CommitEmailStatus.class,
				example = "PENDING"
		)
		String status,

		@Schema(
				description = "검증 완료 방식. 자가 입력만으로는 부여되지 않으므로 PENDING 상태에서는 항상 null입니다.",
				allowableValues = {"OAUTH", "MANAGER_CONFIRMED"},
				nullable = true,
				example = "null"
		)
		String verificationMethod,

		@Schema(description = "검증 완료 시각. status가 VERIFIED일 때만 값이 있습니다.", example = "null")
		Instant verifiedAt,

		@Schema(description = "커밋 이메일이 등록·변경된 시각", example = "2026-08-06T09:14:02Z")
		Instant updatedAt
) {
	public static CommitEmailResponse from(CommitEmail commitEmail) {
		return new CommitEmailResponse(
				commitEmail.registered(),
				commitEmail.commitEmail(),
				commitEmail.status(),
				commitEmail.verificationMethod(),
				commitEmail.verifiedAt(),
				commitEmail.updatedAt()
		);
	}
}
