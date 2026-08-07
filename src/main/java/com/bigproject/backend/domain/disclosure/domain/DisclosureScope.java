package com.bigproject.backend.domain.disclosure.domain;

import io.swagger.v3.oas.annotations.media.Schema;

// DB CHECK(organization_policy.default_disclosure_scope): IN ('FULL','SUMMARY','PRIVATE')
@Schema(name = "DisclosureScope", description = "리포트 공개 범위. SUMMARY(요약만) · PRIVATE(비공개) · FULL(전문)", enumAsRef = true)
public enum DisclosureScope {
	SUMMARY,
	PRIVATE,
	FULL
}
