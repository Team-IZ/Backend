package com.bigproject.backend.domain.codeanalysis.infrastructure;

import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerClient;
import com.bigproject.backend.domain.codeanalysis.application.AnalysisServerException;
import com.bigproject.backend.domain.codeanalysis.domain.AnalysisFailureCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.UUID;

/**
 * AI 서버 클라이언트가 아직 없을 때 대신 등록되는 구현.
 *
 * <p><b>앱이 뜨게 하려고 두는 것이지 동작을 흉내내려는 게 아니다.</b> {@code AnalysisBatchService}가
 * {@link AnalysisServerClient}를 요구하는데 구현이 하나도 없으면 컨텍스트가 시작되지 않아 제출 API까지
 * 못 쓰게 된다. 분석 연동이 아직인 것과 서버가 안 뜨는 것은 전혀 다른 문제다.
 *
 * <p>그래서 <b>호출되면 반드시 실패한다.</b> 조용히 성공한 척하면 job이 QUEUED로 쌓이고 폴링이
 * 영원히 돌면서 "왜 분석이 안 끝나지"를 추적하게 된다. 실패 코드는 {@code TEMPORARY_ERROR}다 —
 * 재시도로 풀릴 수 있는 상태이고, 실제로 실제 구현이 붙으면 그렇게 된다.
 *
 * <p>실제 구현을 {@code @Component}로 등록하면 {@link ConditionalOnMissingBean}이 이 빈을 물러나게
 * 한다. 이 파일은 지우지 않아도 된다.
 */
@Configuration
public class AnalysisServerClientConfig {

	private static final String MESSAGE =
			"AI 서버 클라이언트가 구성되지 않았다. AnalysisServerClient 구현을 빈으로 등록해야 한다.";

	// 메서드 이름이 곧 빈 이름이다. 클래스 이름과 같으면 @Configuration 자신의 빈과 부딪혀
	// BeanDefinitionOverrideException 으로 컨텍스트가 통째로 안 뜬다.
	@Bean
	@ConditionalOnMissingBean(AnalysisServerClient.class)
	public AnalysisServerClient unconfiguredAnalysisServerClient() {
		return new AnalysisServerClient() {
			@Override
			public UUID requestAnalysis(AnalysisRequest request) {
				throw new AnalysisServerException(AnalysisFailureCode.TEMPORARY_ERROR, MESSAGE);
			}

			@Override
			public Optional<AnalysisProgress> fetchProgress(UUID externalJobId) {
				throw new AnalysisServerException(AnalysisFailureCode.TEMPORARY_ERROR, MESSAGE);
			}
		};
	}
}
