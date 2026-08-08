package com.bigproject.backend.domain.usagemetering.application;

import com.bigproject.backend.domain.usagemetering.domain.AiUsage;
import com.bigproject.backend.domain.usagemetering.infrastructure.AiUsageRepository;
import com.bigproject.backend.global.ai.AiUsageEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AI가 돌려준 {@code aiUsage[]}를 {@code ai_usage} 원장에 적재한다.
 *
 * <p>다섯 엔드포인트가 모두 이 경로를 쓴다 — 성공 응답이든 실패 응답이든 <b>태운 토큰은
 * 원장에 남긴다</b>(백엔드 회신 §3 A-5). 실패했다고 안 남기면 청구액이 실제보다 작아진다.
 *
 * <h2>왜 별도 트랜잭션인가</h2>
 *
 * <p>{@link Propagation#REQUIRES_NEW}다. 업무 트랜잭션(리포트 저장 등)이 뒤에서 롤백되어도
 * <b>이미 태운 비용은 사라지지 않는다.</b> 원장을 업무 트랜잭션에 묶으면 "LLM은 돌았는데
 * 저장이 실패해서 청구 근거가 없는" 호출이 생긴다.
 *
 * <h2>단가는 여기서 계산하지 않는다</h2>
 *
 * <p>{@code pricing_status = UNPRICED}로 넣는다. 단가 조회({@code ai_model})와 비용 산정은
 * 별개 관심사이고, 이 컬럼들은 {@code updatable = false}라 나중에 UPDATE도 못 한다.
 * {@code UNPRICED}는 기존 집계가 <b>0으로 더하지 않고 따로 드러내는</b> 값이라
 * (AiUsageRepository.sumPricedCostByOrgId) 청구액을 왜곡하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageRecorder {

	private final AiUsageRepository aiUsageRepository;

	/**
	 * 원장에 적재한다. 실제로 저장된 행 수를 돌려준다.
	 *
	 * <p><b>예외를 밖으로 던지지 않는다.</b> 원장 적재가 업무 흐름을 깨면 안 된다 —
	 * 리포트는 잘 만들어졌는데 원장 INSERT 하나 때문에 사용자가 500을 받는 것은 손해가 더 크다.
	 * 대신 실패는 전부 로그로 남겨 나중에 메울 수 있게 한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int record(List<AiUsageEnvelope> usages, AiUsageAttribution attribution) {
		if (usages == null || usages.isEmpty()) {
			return 0;
		}
		if (attribution.orgId() == null) {
			// org_id가 NOT NULL이라 어차피 INSERT가 거부된다. 여기서 끊고 로그를 남기는 편이
			// 스택트레이스만 남기는 것보다 원인을 찾기 쉽다.
			log.error("ai_usage 적재 불가: orgId가 없습니다. usages={}", usages.size());
			return 0;
		}

		int saved = 0;
		for (AiUsageEnvelope usage : usages) {
			if (persist(usage, attribution)) {
				saved++;
			}
		}
		return saved;
	}

	private boolean persist(AiUsageEnvelope usage, AiUsageAttribution attribution) {
		AiUsage.FeatureCode featureCode = parse(AiUsage.FeatureCode.class, usage.featureCode());
		AiUsage.ContextType contextType = parse(AiUsage.ContextType.class, usage.contextType());
		AiUsage.Status status = parse(AiUsage.Status.class, usage.status());

		if (featureCode == null || contextType == null || status == null) {
			// enum 컬럼이 전부 NOT NULL이라 모르는 값이면 저장할 방법이 없다.
			// AI가 우리보다 먼저 값을 늘린 상황이므로 조용히 넘기지 않고 크게 남긴다.
			log.error("ai_usage 적재 실패(알 수 없는 코드): featureCode={}, contextType={}, status={}",
					usage.featureCode(), usage.contextType(), usage.status());
			return false;
		}

		String contextId = attribution.contextIdOverride() != null
				? attribution.contextIdOverride()
				: usage.contextId();

		if (needsContextIdOverride(contextType) && attribution.contextIdOverride() == null) {
			// 막지는 않는다 — 원장을 잃는 것보다 미귀속으로라도 남기는 편이 낫다.
			// 다만 이 로그가 보이면 호출부가 실제 PK를 안 넘긴 것이다.
			log.warn("ai_usage contextId 미교체: contextType={}, AI가 준 jobId가 그대로 저장된다. contextId={}",
					contextType, usage.contextId());
		}

		try {
			aiUsageRepository.save(AiUsage.builder()
					.orgId(attribution.orgId())
					.actorUserId(attribution.actorUserId())
					.cohortId(attribution.cohortId())
					.classId(attribution.classId())
					.projectId(attribution.projectId())
					.modelCode(usage.modelCode())
					.featureCode(featureCode)
					.contextType(contextType)
					.contextId(contextId)
					.triggerType(attribution.triggerType() == null
							? AiUsage.TriggerType.USER : attribution.triggerType())
					// 🔴 DB CHECK: tier_code·tier_policy_id는 CODE_SESSION만 채우고
					// REPORT_GENERATION·INTERVIEW_BRIEF_GENERATION은 반드시 NULL이어야 한다.
					// AI는 티어를 모르므로(기관이 고르는 값) 여기서도 채우지 않는다 —
					// 코드 세션 경로가 붙을 때 그 도메인이 자기 티어를 넘겨야 한다.
					.tierCode(null)
					.tierPolicyId(null)
					.attributionStatus(attributionStatus(attribution))
					// unallocated_reason_code는 비워 둔다. 허용 값 집합이 DDL CHECK에 있는지
					// 확인되지 않았고, 추측한 코드를 넣으면 INSERT 자체가 거부된다.
					.unallocatedReasonCode(null)
					.classAttributionStatus(attribution.classId() != null
							? AiUsage.ClassAttributionStatus.ALLOCATED
							// 리포트 생성·면담 브리프는 반 축이 없는 호출이라 "실패"가 아니라 "해당 없음"이다.
							: AiUsage.ClassAttributionStatus.NOT_APPLICABLE)
					.classUnallocatedReasonCode(null)
					.requestId(fallback(usage.requestId(), contextId))
					.traceId(fallback(usage.traceId(), fallback(usage.requestId(), contextId)))
					.idempotencyKey(fallback(usage.idempotencyKey(), UUID.randomUUID().toString()))
					.inputTokenCount(zeroIfNull(usage.inputTokenCount()))
					.outputTokenCount(zeroIfNull(usage.outputTokenCount()))
					.cachedTokenCount(zeroIfNull(usage.cachedTokenCount()))
					.pricingStatus(AiUsage.PricingStatus.UNPRICED)
					.status(status)
					.failureCode(usage.failureCode())
					.latencyMs(usage.latencyMs() == null ? 0 : usage.latencyMs())
					.occurredAt(usage.occurredAt() == null ? Instant.now() : usage.occurredAt())
					.build());
			return true;
		} catch (DataIntegrityViolationException exception) {
			// idempotency_key가 전역 UNIQUE다. 같은 키가 다시 왔다는 것은 재시도라는 뜻이고,
			// 원장 관점에서는 이미 기록된 상태라 정상이다 — 실패로 취급하지 않는다.
			log.info("ai_usage 중복 적재 무시: idempotencyKey={}", usage.idempotencyKey());
			return false;
		} catch (RuntimeException exception) {
			log.error("ai_usage 적재 실패: idempotencyKey={}", usage.idempotencyKey(), exception);
			return false;
		}
	}

	/**
	 * AI가 자기 jobId를 대신 넣어 보내는 컨텍스트인가.
	 * AI 저장소 {@code app/schemas/usage.py}가 이 둘만 "Spring이 저장 시점에 교체해야 한다"고 적어 뒀다.
	 */
	private static boolean needsContextIdOverride(AiUsage.ContextType contextType) {
		return contextType == AiUsage.ContextType.REPORT_SNAPSHOT
				|| contextType == AiUsage.ContextType.CURRICULUM_ANALYSIS;
	}

	/** 기수·프로젝트가 모두 있어야 완전 귀속이다. 하나만 있으면 부분 귀속으로 드러낸다. */
	private static AiUsage.AttributionStatus attributionStatus(AiUsageAttribution attribution) {
		boolean hasCohort = attribution.cohortId() != null;
		boolean hasProject = attribution.projectId() != null;
		if (hasCohort && hasProject) {
			return AiUsage.AttributionStatus.ALLOCATED;
		}
		if (hasCohort || hasProject) {
			return AiUsage.AttributionStatus.PARTIALLY_ALLOCATED;
		}
		return AiUsage.AttributionStatus.UNALLOCATED;
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String fallback(String value, String alternative) {
		return value == null || value.isBlank() ? alternative : value;
	}

	private static long zeroIfNull(Long value) {
		return value == null ? 0L : value;
	}
}
