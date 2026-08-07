package com.bigproject.backend.domain.auth.presentation;

import com.bigproject.backend.domain.auth.domain.ConsentCatalog;
import com.bigproject.backend.domain.auth.presentation.dto.ConsentItemResponse;
import com.bigproject.backend.domain.member.domain.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Consent", description = "가입 화면 동의 항목 조회 API")
@RestController
@RequestMapping("/consents")
public class ConsentController {
	private final int consentPolicyVersion;

	public ConsentController(@Value("${consent.policy-version:1}") int consentPolicyVersion) {
		this.consentPolicyVersion = consentPolicyVersion;
	}

	@Operation(
			summary = "약관 목록 조회 | ✅ 사용 가능",
			description = """
					가입 화면이 표시할 동의 항목을 역할별로 받아온다. 인증 없이 호출한다.

					**요청**
					- role (선택, 기본 MANAGER): `SUPER_ADMIN` · `OPERATOR` · `MANAGER` · `TRAINEE`

					**응답**
					- 오퍼레이터·매니저·슈퍼어드민: 필수 2건
					- 교육생: 필수 4건 + 선택 1건(`ANONYMIZED_DATA_USAGE`)
					- requestField: 이 항목의 동의 여부를 후속 가입 요청(`/auth/manager-signup`,
					  `/auth/trainee-activation`)에서 실어 보낼 **필드명**이다. 코드-필드 대응표를
					  화면이 따로 들고 있지 않아도 되도록 서버가 내려준다
					- policyVersion: 표시한 동의 문서 버전이며 동의 기록에 그대로 저장된다

					**required=true 항목을 false로 제출하면 가입이 400으로 거부된다.** 화면은 필수 항목이
					모두 체크되기 전까지 제출 버튼을 막아야 한다. 교육생의 익명 활용 동의만 거부해도 가입된다.

					항목 구성은 서버가 정하므로 화면에 항목을 하드코딩하지 않는다.
					"""
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "역할별 동의 항목 목록"),
			@ApiResponse(responseCode = "400", description = "지원하지 않는 role 값")
	})
	@GetMapping
	public ResponseEntity<List<ConsentItemResponse>> list(
			@Parameter(description = "동의 항목을 조회할 대상 역할입니다. 생략하면 오퍼레이터·매니저 기준으로 응답합니다.")
			@RequestParam(defaultValue = "MANAGER") Role role
	) {
		return ResponseEntity.ok(ConsentCatalog.forRole(role).stream()
				.map(item -> ConsentItemResponse.from(item, consentPolicyVersion))
				.toList());
	}
}
