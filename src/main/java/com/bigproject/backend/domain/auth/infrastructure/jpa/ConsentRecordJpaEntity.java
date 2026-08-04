package com.bigproject.backend.domain.auth.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "consent_record")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsentRecordJpaEntity {
	@Id
	@Column(name = "consent_id", nullable = false, updatable = false)
	private UUID consentId;
}
