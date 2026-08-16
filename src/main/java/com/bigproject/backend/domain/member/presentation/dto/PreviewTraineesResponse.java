package com.bigproject.backend.domain.member.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 사전 검증(드라이런) 결과. <b>아무것도 만들지 않았다.</b>
 *
 * <p>31차 R2 — 9차 Q3-③ 이래 등록 응답({@link RegisterTraineesResponse})을 그대로 돌려줬다. 화면이
 * 미리보기와 등록 결과를 한 컴포넌트로 그릴 수 있게 하려던 것인데, <b>그 대가로 수를 세는 필드가
 * "등록됐다"는 이름을 달았다.</b> 프론트는 이 응답을 처음 보고 미리보기가 진짜 등록을 했는지
 * 기수 명단을 뒤졌다. 이름이 사실과 다르면 설명 한 줄로 덮을 것이 아니라 이름을 고쳐야 한다.
 *
 * <p>같이 없앤 두 필드도 이유가 같다. {@code invitationSentCount}는 항상 0이고
 * {@code batchRequestId}는 항상 null이라, 등록 응답에서 뜻이 있는 값들이 여기서는 <b>화면이
 * null 여부로 무엇을 갈라야 하는지 묻게 만드는 자리</b>일 뿐이었다.
 *
 * <p>{@link RegisterTraineesResponse.Failure}는 그대로 쓴다 — 행별 판정은 등록 경로와 같은
 * 메서드가 내리므로 {@code row}·{@code email}·{@code status}의 뜻도 같다. 여기서 형을 따로 만들면
 * 스펙에 같은 모양이 두 벌 생기고, 화면은 실패 목록을 그리는 컴포넌트를 두 번 쓰게 된다.
 */
@Schema(description = """
		교육생 명단 사전 검증(드라이런) 결과.

		**이 호출은 계정도 초대도 만들지 않는다** — 수는 전부 「그렇게 될 것」이지 「그렇게 됐다」가 아니다.""")
public record PreviewTraineesResponse(
		@Schema(description = "검사 대상으로 받은 전체 교육생 행 수", example = "3")
		int requestedCount,
		@Schema(
				description = "지금 등록을 요청하면 **등록될 수 있는** 행 수(= requestedCount − failures.length). "
						+ "아직 등록되지 않았습니다. 두 호출 사이에 다른 운영자가 같은 주소를 등록할 수 있어 "
						+ "이 수가 등록 결과를 보장하지는 않습니다.",
				example = "2"
		)
		int registrableCount,
		@Schema(description = "지금 등록하면 걸릴 입력 행별 목록. 판정 규칙과 status 값은 등록과 같습니다.")
		List<RegisterTraineesResponse.Failure> failures
) {
}
