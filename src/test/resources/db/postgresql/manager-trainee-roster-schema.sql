-- MG-05 교육생 명부 조회가 실제로 읽는 테이블만 추린 PostgreSQL 스키마다.
-- 타입·NOT NULL·CHECK는 테이블정의서(02_AUTH / 03_ORG / 04_PLAN / 05_CUR / 06_MEAS)를 따르며,
-- 이 조회에 쓰이지 않는 컬럼과 감사·이력 컬럼은 생략했다. 운영 스키마의 대체물이 아니라 조회 검증용이다.

CREATE TABLE organization (
	org_id UUID PRIMARY KEY,
	name VARCHAR(200) NOT NULL,
	status VARCHAR(30) NOT NULL,
	deleted_at TIMESTAMPTZ
);

CREATE TABLE "role" (
	role_id UUID PRIMARY KEY,
	code VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE app_user (
	user_id UUID PRIMARY KEY,
	org_id UUID REFERENCES organization(org_id),
	role_id UUID NOT NULL REFERENCES "role"(role_id),
	email VARCHAR(320) NOT NULL,
	name VARCHAR(200) NOT NULL,
	status VARCHAR(100) NOT NULL
		CHECK (status IN ('PENDING', 'ACTIVE', 'LOCKED', 'INACTIVE')),
	deleted_at TIMESTAMPTZ
);

CREATE TABLE cohort (
	cohort_id UUID PRIMARY KEY,
	org_id UUID NOT NULL REFERENCES organization(org_id),
	name VARCHAR(200) NOT NULL,
	status VARCHAR(30) NOT NULL
		CHECK (status IN ('PLANNED', 'RUNNING', 'CLOSED')),
	deleted_at TIMESTAMPTZ
);

CREATE TABLE "class" (
	class_id UUID PRIMARY KEY,
	org_id UUID NOT NULL REFERENCES organization(org_id),
	cohort_id UUID NOT NULL REFERENCES cohort(cohort_id),
	name VARCHAR(200) NOT NULL,
	lifecycle_status VARCHAR(30) NOT NULL,
	deleted_at TIMESTAMPTZ
);

CREATE TABLE manager_assignment (
	assignment_id UUID PRIMARY KEY,
	manager_user_id UUID NOT NULL REFERENCES app_user(user_id),
	org_id UUID NOT NULL REFERENCES organization(org_id),
	class_id UUID NOT NULL REFERENCES "class"(class_id),
	assigned_at TIMESTAMPTZ NOT NULL,
	unassigned_at TIMESTAMPTZ,
	status VARCHAR(30) NOT NULL
		CHECK (status IN ('ACTIVE', 'ENDED')),
	-- ACTIVE이면 종료 필드가 NULL이고 ENDED이면 필수다.
	CHECK ((status = 'ACTIVE' AND unassigned_at IS NULL)
		OR (status = 'ENDED' AND unassigned_at IS NOT NULL))
);

CREATE TABLE cohort_member (
	cohort_member_id UUID PRIMARY KEY,
	org_id UUID NOT NULL REFERENCES organization(org_id),
	cohort_id UUID NOT NULL REFERENCES cohort(cohort_id),
	user_id UUID NOT NULL REFERENCES app_user(user_id),
	joined_at TIMESTAMPTZ NOT NULL,
	left_at TIMESTAMPTZ,
	status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
		CHECK (status IN ('ACTIVE', 'LEFT')),
	UNIQUE (cohort_id, user_id)
);

CREATE TABLE class_membership (
	class_membership_id UUID PRIMARY KEY,
	class_id UUID NOT NULL REFERENCES "class"(class_id),
	cohort_member_id UUID NOT NULL REFERENCES cohort_member(cohort_member_id),
	org_id UUID NOT NULL REFERENCES organization(org_id),
	assigned_at TIMESTAMPTZ NOT NULL,
	unassigned_at TIMESTAMPTZ
);

-- 같은 기수 구성원의 동시 유효 반 배정은 최대 1건이다.
CREATE UNIQUE INDEX uq_class_membership_active
	ON class_membership (cohort_member_id)
	WHERE unassigned_at IS NULL;

CREATE TABLE project (
	project_id UUID PRIMARY KEY,
	org_id UUID NOT NULL REFERENCES organization(org_id),
	cohort_id UUID NOT NULL REFERENCES cohort(cohort_id),
	name VARCHAR(200) NOT NULL,
	sequence_no INTEGER NOT NULL,
	project_category VARCHAR(30) NOT NULL
		CHECK (project_category IN ('MINI_PROJECT', 'BIG_PROJECT')),
	start_date DATE NOT NULL,
	end_date DATE NOT NULL CHECK (end_date >= start_date),
	lifecycle_status VARCHAR(30) NOT NULL DEFAULT 'PLANNED'
		CHECK (lifecycle_status IN ('PLANNED', 'RUNNING', 'CLOSED')),
	deleted_at TIMESTAMPTZ,
	UNIQUE (cohort_id, sequence_no)
);

CREATE TABLE project_verification_concept_set (
	concept_set_id UUID PRIMARY KEY,
	project_id UUID NOT NULL REFERENCES project(project_id),
	org_id UUID NOT NULL REFERENCES organization(org_id),
	version_no INTEGER NOT NULL CHECK (version_no > 0),
	status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
		CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
	effective_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
	effective_to TIMESTAMPTZ
);

CREATE TABLE teaches (
	teaches_id UUID PRIMARY KEY,
	org_id UUID NOT NULL REFERENCES organization(org_id),
	canonical_name VARCHAR(200) NOT NULL,
	normalized_name VARCHAR(200) NOT NULL,
	status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
		CHECK (status IN ('ACTIVE', 'INACTIVE', 'MERGED'))
);

CREATE TABLE project_verification_concept (
	project_concept_id UUID PRIMARY KEY,
	concept_set_id UUID NOT NULL REFERENCES project_verification_concept_set(concept_set_id),
	org_id UUID NOT NULL REFERENCES organization(org_id),
	teaches_id UUID NOT NULL REFERENCES teaches(teaches_id),
	sequence_no INTEGER NOT NULL,
	UNIQUE (concept_set_id, sequence_no),
	UNIQUE (concept_set_id, teaches_id)
);

CREATE TABLE project_assessment_round (
	assessment_round_id UUID PRIMARY KEY,
	project_id UUID NOT NULL REFERENCES project(project_id),
	org_id UUID NOT NULL REFERENCES organization(org_id),
	cohort_id UUID NOT NULL REFERENCES cohort(cohort_id),
	concept_set_id UUID REFERENCES project_verification_concept_set(concept_set_id),
	round_no INTEGER NOT NULL CHECK (round_no > 0),
	round_name VARCHAR(200) NOT NULL,
	submission_due_at TIMESTAMPTZ NOT NULL,
	is_final BOOLEAN NOT NULL DEFAULT TRUE,
	status VARCHAR(100) NOT NULL
		CHECK (status IN ('PLANNED', 'OPEN', 'CLOSED', 'COMPLETED')),
	deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_project_assessment_round_no_active
	ON project_assessment_round (project_id, round_no)
	WHERE deleted_at IS NULL;

-- 팀 코드 분석 결과 루트. 이 조회에서는 팀 공유 문제를 개인 수행에 연결하는 키로만 쓴다.
CREATE TABLE code_analysis (
	analysis_id UUID PRIMARY KEY,
	org_id UUID NOT NULL REFERENCES organization(org_id),
	status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE measurement_attempt (
	attempt_id UUID PRIMARY KEY,
	org_id UUID NOT NULL REFERENCES organization(org_id),
	cohort_id UUID NOT NULL REFERENCES cohort(cohort_id),
	assessment_round_id UUID NOT NULL REFERENCES project_assessment_round(assessment_round_id),
	project_id UUID NOT NULL REFERENCES project(project_id),
	user_id UUID NOT NULL REFERENCES app_user(user_id),
	code_analysis_id UUID REFERENCES code_analysis(analysis_id),
	attempt_type VARCHAR(100) NOT NULL
		CHECK (attempt_type IN ('INITIAL', 'RETRY', 'REVIEW')),
	attempt_sequence_no INTEGER NOT NULL CHECK (attempt_sequence_no > 0),
	status VARCHAR(100) NOT NULL
		CHECK (status IN ('NOT_STARTED', 'SUBMITTED', 'ANALYZING', 'SESSION_READY',
			'SESSION_IN_PROGRESS', 'COMPLETED', 'FAILED', 'EXPIRED')),
	validity_review_status VARCHAR(100) NOT NULL
		CHECK (validity_review_status IN ('NOT_REQUIRED', 'PENDING', 'CONFIRMED_INVALID', 'RESTORED_VALID'))
);

-- 최초 응시는 회차·사용자별 최대 1건이다.
CREATE UNIQUE INDEX uq_measurement_attempt_initial
	ON measurement_attempt (assessment_round_id, user_id)
	WHERE attempt_type = 'INITIAL';

CREATE TABLE assessment_session (
	session_id UUID PRIMARY KEY,
	org_id UUID NOT NULL REFERENCES organization(org_id),
	attempt_id UUID NOT NULL UNIQUE REFERENCES measurement_attempt(attempt_id),
	status VARCHAR(100) NOT NULL DEFAULT 'READY'
		CHECK (status IN ('READY', 'IN_PROGRESS', 'PAUSED', 'COMPLETED',
			'INTERRUPTED', 'INVALID', 'FAILED', 'SUPERSEDED'))
);

CREATE TABLE assessment_problem (
	problem_id UUID PRIMARY KEY,
	org_id UUID NOT NULL REFERENCES organization(org_id),
	code_analysis_id UUID NOT NULL REFERENCES code_analysis(analysis_id),
	problem_scope VARCHAR(100) NOT NULL
		CHECK (problem_scope IN ('TEAM_SHARED_PROBLEM', 'INDIVIDUAL_OWN_COMMIT')),
	project_verification_concept_id UUID REFERENCES project_verification_concept(project_concept_id),
	problem_no INTEGER NOT NULL CHECK (problem_no BETWEEN 1 AND 3),
	title TEXT,
	generation_status VARCHAR(30) NOT NULL
		CHECK (generation_status IN ('GENERATED', 'NOT_GENERATED')),
	not_generated_reason_code VARCHAR(50)
		CHECK (not_generated_reason_code IS NULL
			OR not_generated_reason_code IN ('NO_MATCHING_CODE_EVIDENCE')),
	-- 코드 근거를 찾지 못한 개념도 슬롯 행은 유지하되 제목·단계를 만들지 않는다.
	CHECK ((generation_status = 'GENERATED' AND title IS NOT NULL AND not_generated_reason_code IS NULL)
		OR (generation_status = 'NOT_GENERATED' AND title IS NULL
			AND not_generated_reason_code = 'NO_MATCHING_CODE_EVIDENCE'))
);

CREATE UNIQUE INDEX uq_assessment_problem_team_concept
	ON assessment_problem (code_analysis_id, project_verification_concept_id)
	WHERE problem_scope = 'TEAM_SHARED_PROBLEM';

CREATE TABLE problem_stage (
	problem_stage_id UUID PRIMARY KEY,
	session_id UUID NOT NULL REFERENCES assessment_session(session_id),
	problem_id UUID NOT NULL REFERENCES assessment_problem(problem_id),
	axis_code VARCHAR(10) NOT NULL
		CHECK (axis_code IN ('L1', 'L2', 'L3', 'L4')),
	-- 정의서상 생성 컬럼이다. 축 코드에서 파생하므로 직접 입력하지 않는다.
	question_sequence_no INTEGER GENERATED ALWAYS AS (
		CASE axis_code WHEN 'L1' THEN 1 WHEN 'L2' THEN 2 WHEN 'L3' THEN 3 WHEN 'L4' THEN 4 END
	) STORED,
	status VARCHAR(30) NOT NULL
		CHECK (status IN ('PREPARED', 'IN_PROGRESS', 'PASSED', 'NOT_PASSED', 'NOT_REACHED', 'NOT_ANSWERED')),
	UNIQUE (session_id, problem_id, axis_code)
);
