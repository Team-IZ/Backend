package com.bigproject.backend.domain.auth.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ConsentRecordJpaRepository extends JpaRepository<ConsentRecordJpaEntity, UUID> {
	@Modifying(flushAutomatically = true)
	@Query(value = """
			INSERT INTO consent_record (
				consent_id, org_id, user_id, consent_code, policy_version, agreed,
				agreed_at, capture_channel, source_ip,
				user_agent, locale, evidence_hash, created_at
			) VALUES (
				:consentId, :organizationId, :userId, :consentCode, :policyVersion, :agreed,
				:agreedAt, :captureChannel, CAST(:sourceIp AS inet),
				:userAgent, :locale, :evidenceHash, :agreedAt
			)
			""", nativeQuery = true)
	int insert(
			@Param("consentId") UUID consentId,
			@Param("organizationId") UUID organizationId,
			@Param("userId") UUID userId,
			@Param("consentCode") String consentCode,
			@Param("policyVersion") int policyVersion,
			@Param("agreed") boolean agreed,
			@Param("agreedAt") Instant agreedAt,
			@Param("captureChannel") String captureChannel,
			@Param("sourceIp") String sourceIp,
			@Param("userAgent") String userAgent,
			@Param("locale") String locale,
			@Param("evidenceHash") String evidenceHash
	);
}
