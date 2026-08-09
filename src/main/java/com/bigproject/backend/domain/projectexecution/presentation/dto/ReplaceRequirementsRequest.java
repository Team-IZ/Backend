package com.bigproject.backend.domain.projectexecution.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 요구사항 전체 교체 요청.
 *
 * <p><b>{@code requirementTitles}는 필수다.</b> 이건 {@code PUT} 전체 교체라 "보낸 목록이 최종 상태"가
 * 되는데, 키가 없어도 통과하면 <em>빈 요청</em>과 <em>전부 지우기</em>를 서버도 화면도 구분할 수 없다.
 * {@code @NotNull}을 붙이면 springdoc이 그것을 읽어 스펙의 {@code required}에 싣고, 생성된 프론트
 * 타입에서 이 필드를 빠뜨린 호출이 컴파일 단계에서 걸린다 — 전에는 통과한 뒤 런타임 400이 났다.
 *
 * <p>"전부 지우기"는 <b>빈 배열</b>로 표현한다. 아래 설명에 적어 두었으니 화면은 그 경우에만
 * 확인 모달을 띄우면 된다.
 */
@Schema(description = "프로젝트 요구사항 전체 교체 요청")
public record ReplaceRequirementsRequest(
        @Schema(
                description = """
                        요구사항 문구 목록. 보낸 목록이 그대로 최종 상태가 된다 —
                        목록에 없는 기존 문구는 폐기(retire)되고 새 문구는 추가된다.
                        완전히 같은 문구만 "유지"로 인식하고, 조금이라도 다르면 폐기 + 추가로 처리한다.

                        **빈 배열 `[]`을 보내면 기존 요구사항이 전부 폐기된다.** 정상 동작이므로 400이 아니다 —
                        화면은 이 경우에만 확인 모달을 띄우면 된다.
                        키 자체를 빠뜨리면 400이다. '빈 요청'과 '전부 지우기'를 구분하기 위해서다.""",
                example = "[\"로그인 기능 구현\", \"게시글 CRUD\"]",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotNull(message = "requirementTitles는 필수입니다. 전부 지우려면 빈 배열을 보내세요.")
        List<String> requirementTitles
) {
}