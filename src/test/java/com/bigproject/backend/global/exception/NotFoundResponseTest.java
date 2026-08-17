package com.bigproject.backend.global.exception;

import com.bigproject.backend.domain.academicoperations.domain.AcademicOperationsErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 25차 R4 — <b>애플리케이션이 404를 본문까지 갖춰 끝까지 내보내는지</b>를 못 박는다.
 *
 * <p>프론트 25차 §4가 실서버에서 「없는 것」을 물으면 응답이 아예 오지 않는다고 보고했다
 * (헤더 0줄, 150초까지 무응답). 같은 시각 형식 오류 400·405·409는 1~2초에 돌아왔다.
 * 그래서 "예외를 던지기 전에 앱이 무언가를 더 하고 있는 것 아니냐"가 의심 대상이었다.
 *
 * <p>이 테스트는 그 의심을 <b>앱 안에서는 배제</b>한다. 도메인 404({@code COHORT_NOT_FOUND})와
 * 매핑되지 않은 경로의 404가 모두 {@code ErrorResponse} 본문을 실은 완결된 응답으로 나간다.
 * 응답을 만드는 경로가 409·400과 <b>같은 어드바이스 한 곳</b>이라는 것도 함께 확인한다 —
 * 그 셋이 같은 코드를 지나가는데 404만 화면에 닿지 않는다면, 원인은 상태 코드를 보고 처리를
 * 달리하는 <b>앱 바깥 층</b>에 있다.
 */
class NotFoundResponseTest {

	private final MockMvc mockMvc = standaloneSetup(new ProbeController())
			.setControllerAdvice(new ApiExceptionHandler(), new GlobalExceptionHandler())
			.build();

	@Test
	void 도메인_404는_code와_message를_실은_완결된_응답으로_나간다() throws Exception {
		mockMvc.perform(get("/probe/cohort-not-found"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.code").value("COHORT_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("기수를 찾을 수 없습니다."));
	}

	@Test
	void 매핑되지_않은_경로의_404도_같은_봉투로_나간다() throws Exception {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();

		var response = handler.handleNoResourceFound(
				new NoResourceFoundException(
						org.springframework.http.HttpMethod.GET, "/api/v0/no-such-endpoint", "no-such-endpoint"),
				new MockHttpServletRequest("GET", "/api/v0/no-such-endpoint"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
		// 요청 경로는 싣지 않는다 — 반사형 노출의 통로가 된다.
		assertThat(response.getBody().message()).doesNotContain("no-such-endpoint");
	}

	/**
	 * 404와 409가 <b>같은 어드바이스·같은 조립</b>을 지난다. 상태 코드만 다르다.
	 *
	 * <p>이것이 R4의 핵심 근거다. 프론트 실측에서 같은 엔드포인트의 409는 돌아오고 404만
	 * 무응답이었는데, 앱 안에서 둘은 분기가 없다.
	 */
	@Test
	void 상태만_다를_뿐_404와_409는_같은_경로로_조립된다() throws Exception {
		mockMvc.perform(get("/probe/classroom-name-taken"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CLASSROOM_NAME_TAKEN"));

		mockMvc.perform(get("/probe/classroom-not-found"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CLASSROOM_NOT_FOUND"));
	}

	/**
	 * 32차 R12 — 교안 상세도 같은 자리다.
	 *
	 * <p>프론트가 없는 {@code materialId}로 65초까지 무응답을 관측했는데, 형식이 틀린 id에는
	 * 400이 정상적으로 돌아왔다. 앱은 {@code CURRICULUM_MATERIAL_NOT_FOUND}를 완결된 404로
	 * 내보내며 그 경로에 DB 조회 한 번 말고는 아무것도 없다 —
	 * <b>33차 R1(404만 앞단에서 막힘)과 같은 층</b>이라는 뜻이다.
	 */
	@Test
	void 교안_404도_같은_봉투로_나간다() throws Exception {
		mockMvc.perform(get("/probe/curriculum-not-found"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CURRICULUM_MATERIAL_NOT_FOUND"));
	}

	@RestController
	static class ProbeController {

		@GetMapping("/probe/cohort-not-found")
		void cohortNotFound() {
			throw new ApiException(AcademicOperationsErrorCode.COHORT_NOT_FOUND);
		}

		@GetMapping("/probe/curriculum-not-found")
		void curriculumNotFound() {
			throw new com.bigproject.backend.domain.curriculum.domain.CurriculumException(
					com.bigproject.backend.domain.curriculum.domain.CurriculumErrorCode
							.CURRICULUM_MATERIAL_NOT_FOUND);
		}

		@GetMapping("/probe/classroom-not-found")
		void classroomNotFound() {
			throw new ApiException(AcademicOperationsErrorCode.CLASSROOM_NOT_FOUND);
		}

		@GetMapping("/probe/classroom-name-taken")
		void classroomNameTaken() {
			throw new ApiException(AcademicOperationsErrorCode.CLASSROOM_NAME_TAKEN);
		}
	}
}
