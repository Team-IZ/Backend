package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.domain.AnalysisJob;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 코드 분석에 쓸 AI 모델을 카탈로그({@code ai_model})에서 찾는다.
 *
 * <p><b>왜 설정값 하나로 끝내지 않는가.</b> AI에 보낼 값({@code model_code})과 우리가 원장에
 * 남길 값({@code model_id})이 서로 다른 컬럼이라, 어느 쪽이든 코드에 문자열로 박으면 둘이 어긋날 수
 * 있다. 카탈로그를 한 번 읽어 둘을 함께 가져오면 그 어긋남이 구조적으로 생기지 않는다.
 *
 * <p><b>2026-08-10 정정.</b> {@code /analyses} 요청의 {@code providerModelCode} 필드에는
 * {@code provider_model_code}(공급자 원본 식별자, 예: {@code nemotron-3-ultra-550b-a55b})가 아니라
 * {@code model_code}(전체 코드, 예: {@code nvidia/nemotron-3-ultra-550b-a55b})를 보내야 AI가 모델을
 * 인식한다. 필드 이름은 그대로 두고 값만 바꾼다 — AI 쪽 요청 스키마의 필드명을 이쪽에서 정할 수는
 * 없다. {@code reporting.application.ReportBatchService}도 같은 결론에 도달해 지금은
 * {@code provider_model_code}를 보내지 않는다(설정값 {@code ai.report.model-code}를 쓰며 그 형식이
 * {@code model_code}와 같다).
 *
 * <p><b>왜 model_code로 찾는가.</b> {@code model_code}에만 UNIQUE가 있다
 * ({@code uq_ai_model_model_code}). {@code provider_model_code}는 {@code (provider, ...)} 복합
 * UNIQUE라 단독으로는 유일하지 않다. 2026-08-09부터 {@code model_code}는
 * {@code provider || '/' || provider_model_code} 규칙을 따르므로 설정값도 그 형태다
 * (예: {@code nvidia/nemotron-3-ultra-550b-a55b}).
 *
 * <p>엔티티를 새로 만들지 않고 네이티브 조회로 두는 것은 {@link AnalysisDispatchRepository}와 같은
 * 이유다 — 여기 필요한 것은 컬럼 두 개를 읽는 것뿐이고, 모델 카탈로그를 쓰기까지 하는 주체는
 * 슈퍼어드민 화면이지 이 배치가 아니다.
 */
public interface AnalysisModelRepository extends Repository<AnalysisJob, UUID> {

	/**
	 * ACTIVE 모델만 고른다. INACTIVE는 폐기된 모델이고
	 * ({@code deepseek-ai/deepseek-v4-flash}가 그런 경우다) 과거 사용량 참조 때문에 행만 남아 있다.
	 * 그걸 새 분석에 쓰면 폐기한 의미가 없다.
	 */
	@Query(value = """
			SELECT m.model_id            AS modelId,
			       m.model_code          AS modelCode,
			       m.provider_model_code AS providerModelCode
			  FROM ai_model m
			 WHERE m.model_code = :modelCode
			   AND m.status     = 'ACTIVE'
			""", nativeQuery = true)
	Optional<AnalysisModel> findActiveByModelCode(@Param("modelCode") String modelCode);

	interface AnalysisModel {

		/** {@code analysis_job.requested_model_id}에 남길 값. */
		UUID getModelId();

		/**
		 * {@code /analyses} 요청 본문 {@code providerModelCode} 필드로 보낼 값.
		 *
		 * <p>이름과 달리 {@code provider_model_code}가 아니라 <b>전체 코드({@code model_code})</b>를
		 * 돌려준다 — AI가 그 값이어야 모델을 인식한다(2026-08-10 정정).
		 */
		String getModelCode();

		/** {@code provider_model_code}. 지금은 {@code reporting.application.ReportBatchService}만 쓴다. */
		String getProviderModelCode();
	}
}
