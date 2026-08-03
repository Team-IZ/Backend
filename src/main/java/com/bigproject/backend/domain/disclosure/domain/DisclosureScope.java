package com.bigproject.backend.domain.disclosure.domain;

// DB CHECK(organization_policy.default_disclosure_scope): IN ('FULL','SUMMARY','PRIVATE')
public enum DisclosureScope {
	SUMMARY,
	PRIVATE,
	FULL
}
