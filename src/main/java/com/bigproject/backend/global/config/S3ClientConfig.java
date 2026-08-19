package com.bigproject.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 교안·제출물 아티팩트 저장에 쓰는 공용 {@link S3Client}.
 *
 * <p>이전에는 {@code CurriculumServiceImpl}이 {@code private static final S3Client}를 직접 만들어
 * 쓰고 있었고, 그 리전이 {@code AP_SOUTHEAST_2}(시드니)로 하드코딩돼 있었다 — 나머지 인프라(App
 * Runner·DB·다른 S3 버킷) 전부 {@code ap-northeast-1}(도쿄)인데 여기만 달라서, 실제로 그 분기가
 * 실행되는 순간 크로스리전 요청으로 막힐 뻔했다. `@Bean`으로 빼서 도메인 서비스 여러 곳(교안·제출물)이
 * 같은 클라이언트를 재사용하게 하고, 리전도 나머지와 통일한다.
 *
 * <p>자격 증명은 SDK 기본 체인을 그대로 쓴다 — App Runner에서는 instance role, 로컬에서는
 * {@code ~/.aws} 프로필로 이미 동작을 확인했다(이 환경에서 두 경로 다 별도 설정 없이 통과).
 */
@Configuration
public class S3ClientConfig {

	@Bean
	public S3Client s3Client(@Value("${aws.s3.region:ap-northeast-1}") String region) {
		return S3Client.builder()
				.region(Region.of(region))
				.build();
	}
}
