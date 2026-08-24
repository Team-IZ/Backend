create table app_user
(
    user_id                          uuid                     default gen_random_uuid()            not null
        constraint pk_app_user
            primary key,
    org_id                           uuid,
    email                            citext                                                        not null,
    normalized_email                 varchar(320)                                                  not null
        constraint uq_app_user_normalized_email
            unique,
    name                             varchar(200),
    password_hash                    varchar(128),
    status                           varchar(30)              default 'PENDING'::character varying not null
        constraint ck_app_user_status
            check ((status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])),
    inactivated_at                   timestamp with time zone,
    inactivated_by                   uuid
        constraint fk_app_user_inactivated_by
            references app_user
            on delete restrict,
    inactivated_reason_code          varchar(30)
        constraint ck_app_user_inactivated_reason_code
            check ((inactivated_reason_code)::text = ANY
                   (ARRAY [('RESIGNED'::character varying)::text, ('ADMIN_SUSPENDED'::character varying)::text, ('CONTRACT_ENDED'::character varying)::text, ('SECURITY_ACTION'::character varying)::text, ('OTHER'::character varying)::text])),
    inactivated_reason               text,
    is_email_verified                boolean                  default false                        not null,
    email_verified_at                timestamp with time zone,
    failed_login_count               integer                  default 0                            not null
        constraint ck_app_user_failed_login_count
            check (failed_login_count >= 0),
    login_blocked_until              timestamp with time zone,
    last_login_at                    timestamp with time zone,
    password_changed_at              timestamp with time zone,
    commit_email                     varchar(320),
    commit_email_normalized          varchar(320),
    commit_email_status              varchar(30)
        constraint ck_app_user_commit_email_status
            check ((commit_email_status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('VERIFIED'::character varying)::text, ('UNVERIFIED'::character varying)::text])),
    commit_email_verification_method varchar(30)
        constraint ck_app_user_commit_email_verification_method
            check ((commit_email_verification_method)::text = ANY
                   (ARRAY [('OAUTH'::character varying)::text, ('MANAGER_CONFIRMED'::character varying)::text])),
    commit_email_verified_by         uuid
        constraint fk_app_user_commit_email_verified_by
            references app_user
            on delete restrict,
    commit_email_verified_at         timestamp with time zone,
    commit_email_updated_at          timestamp with time zone,
    created_at                       timestamp with time zone default CURRENT_TIMESTAMP            not null,
    updated_at                       timestamp with time zone default CURRENT_TIMESTAMP            not null,
    deleted_at                       timestamp with time zone,
    row_version                      integer                  default 0                            not null
        constraint ck_app_user_row_version
            check (row_version >= 0),
    role_code                        varchar(30)                                                   not null
        constraint ck_app_user_role_code
            check ((role_code)::text = ANY
                   (ARRAY [('SUPER_ADMIN'::character varying)::text, ('OPERATOR'::character varying)::text, ('MANAGER'::character varying)::text, ('TRAINEE'::character varying)::text])),
    constraint ck_app_user_commit_email_updated_at
        check (((commit_email IS NULL) AND (commit_email_normalized IS NULL) AND (commit_email_status IS NULL) AND
                (commit_email_verification_method IS NULL) AND (commit_email_verified_by IS NULL) AND
                (commit_email_verified_at IS NULL) AND (commit_email_updated_at IS NULL)) OR
               ((commit_email IS NOT NULL) AND (commit_email_normalized IS NOT NULL) AND
                (commit_email_status IS NOT NULL) AND (commit_email_updated_at IS NOT NULL))),
    constraint ck_app_user_commit_email_verified_at
        check ((((commit_email_status)::text = 'VERIFIED'::text) AND (commit_email_verified_at IS NOT NULL)) OR
               (((commit_email_status)::text IS DISTINCT FROM 'VERIFIED'::text) AND
                (commit_email_verified_at IS NULL))),
    constraint ck_app_user_is_email_verified
        check (((is_email_verified = true) AND (email_verified_at IS NOT NULL)) OR
               ((is_email_verified = false) AND (email_verified_at IS NULL))),
    constraint ck_app_user_status_2
        check (((status)::text = 'PENDING'::text) OR
               ((name IS NOT NULL) AND (password_hash IS NOT NULL) AND (password_changed_at IS NOT NULL))),
    constraint ck_app_user_status_3
        check ((((status)::text = 'INACTIVE'::text) AND (inactivated_at IS NOT NULL) AND
                (inactivated_by IS NOT NULL) AND (inactivated_reason_code IS NOT NULL)) OR
               ((status)::text <> 'INACTIVE'::text))
);

comment on table app_user is '플랫폼의 로그인 계정 원장입니다. 사용자 역할과 기관 소속, 계정 생명주기, 이메일 소유 확인, 로그인 지연·비밀번호 변경 기준을 관리하고 교육생 계정에는 현재 Git 커밋 이메일 검증 상태를 함께 보존합니다. | 정의서명: AppUser | 제약·비고: • 상태: PENDING / ACTIVE / INACTIVE • org_id NULL은 기관 미소속 SUPER_ADMIN만 허용하고 OPERATOR·MANAGER·TRAINEE는 기관 소속이 필수입니다. • normalized_email은 로그인·중복 판정용 전체 UNIQUE 값이며 email은 표시·연락용 원문입니다. • PENDING은 명단 등록 후 아직 활성화하지 않은 TRAINEE에만 사용하며 name·password_hash·password_changed_at이 NULL일 수 있습니다. • ACTIVE·INACTIVE는 name·password_hash·password_changed_at이 모두 필수입니다. • ACTIVE이면 is_email_verified=TRUE이고 email_verified_at이 필수입니다. • is_email_verified=TRUE이면 email_verified_at이 필수이고, FALSE이면 email_verified_at은 NULL이어야 합니다. • INACTIVE이면 inactivated_at·inactivated_by·inactivated_reason_code가 필수입니다. • 비활성화 사유: RESIGNED / ADMIN_SUSPENDED / CONTRACT_ENDED / SECURITY_ACTION / OTHER • AUTH_THROTTLED는 계정 상태가 아니라 failed_login_count와 login_blocked_until로 표현합니다. • login_blocked_until';

comment on column app_user.user_id is '모든 사용자 계정의 기본키이다.';

comment on column app_user.org_id is '사용자의 소속 기관이며 테넌트 격리와 RLS 필터에 사용한다.';

comment on column app_user.email is '로그인·알림·초대에 사용하는 이메일 주소이다.';

comment on column app_user.normalized_email is '이메일 중복 판정에 사용하는 정규화 값이다.';

comment on column app_user.name is '사용자의 화면 표시 이름이다.';

comment on column app_user.password_hash is '원문 비밀번호 대신 저장하는 단방향 해시이다.';

comment on column app_user.status is '계정 생명주기 상태이다.';

comment on column app_user.inactivated_at is '계정이 비활성화된 시각이다.';

comment on column app_user.inactivated_by is '계정을 비활성화한 사용자이다.';

comment on column app_user.inactivated_reason_code is '계정 비활성화 사유를 나타내는 안정 코드이다.';

comment on column app_user.inactivated_reason is '계정 비활성화의 상세 사유이다.';

comment on column app_user.is_email_verified is '로그인 이메일의 검증 완료 여부이다.';

comment on column app_user.email_verified_at is '로그인 이메일 검증 완료 시각이다.';

comment on column app_user.failed_login_count is '연속 로그인 실패 횟수이다.';

comment on column app_user.login_blocked_until is '로그인 차단 종료 시각이다. 인증 실패 누적에 따른 자동 지연과 운영자의 수동 차단이 함께 이 컬럼을 쓰며, 둘의 구분은 audit_log의 AUTH.LOGIN_LOCK_SET 기록으로 한다.';

comment on column app_user.last_login_at is '최근 로그인 성공 시각이다.';

comment on column app_user.password_changed_at is '비밀번호가 마지막으로 설정·변경된 시각이다.';

comment on column app_user.commit_email is '교육생 커밋 귀속에 사용하는 현재 이메일이다.';

comment on column app_user.commit_email_normalized is '커밋 이메일 매칭에 사용하는 정규화 값이다. 활성 계정(deleted_at IS NULL) 범위에서만 기관 내 유일하며(uq_app_user_commit_email_active), 탈퇴한 계정이 쓰던 값은 다른 사용자가 재사용할 수 있다.';

comment on column app_user.commit_email_status is '교육생 커밋 이메일 검증 상태이다. NULL은 미등록을 뜻하며 등록(PENDING)만으로도 커밋 귀속 매칭은 동작한다. VERIFIED는 본인 확인까지 끝난 상태다.';

comment on column app_user.commit_email_verification_method is '커밋 이메일 검증 방식이다.';

comment on column app_user.commit_email_verified_by is '수동 검증을 수행한 사용자이다.';

comment on column app_user.commit_email_verified_at is '커밋 이메일 검증 완료 시각이다.';

comment on column app_user.commit_email_updated_at is '현재 커밋 이메일이 등록·변경된 시각이다.';

comment on column app_user.created_at is '사용자 계정 생성 시각이다.';

comment on column app_user.updated_at is '사용자 계정 최근 수정 시각이다.';

comment on column app_user.deleted_at is '사용자 계정 논리 삭제 시각이다.';

comment on column app_user.row_version is '동시 수정 충돌을 탐지하는 낙관적 잠금 버전이다.';

comment on column app_user.role_code is '사용자에게 부여된 시스템 역할 코드이다. SUPER_ADMIN·OPERATOR·MANAGER·TRAINEE 중 하나이며, user_invitation.target_role_code와 같은 패턴으로 FK 없이 코드를 직접 저장한다.';

alter table app_user
    owner to postgres;

create table ai_model
(
    model_id                uuid                     default gen_random_uuid()           not null
        constraint pk_ai_model
            primary key,
    model_code              varchar(100)                                                 not null
        constraint uq_ai_model_model_code
            unique,
    provider                varchar(100)                                                 not null,
    provider_model_code     varchar(100)                                                 not null,
    display_name            varchar(200)                                                 not null,
    status                  varchar(100)             default 'ACTIVE'::character varying not null
        constraint ck_ai_model_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])),
    capability_payload      jsonb
        constraint ck_ai_model_capability_payload_shape
            check ((capability_payload IS NULL) OR (jsonb_typeof(capability_payload) = 'object'::text)),
    context_window          integer                                                      not null
        constraint ck_ai_model_context_window
            check (context_window > 0),
    max_output_tokens       bigint                                                       not null
        constraint ck_ai_model_max_output_tokens
            check (max_output_tokens > 0),
    input_unit_price        numeric(18, 6)
        constraint ck_ai_model_input_unit_price_min
            check (input_unit_price >= (0)::numeric),
    output_unit_price       numeric(18, 6)
        constraint ck_ai_model_output_unit_price_min
            check (output_unit_price >= (0)::numeric),
    cached_input_unit_price numeric(18, 6)
        constraint ck_ai_model_cached_input_unit_price_min
            check (cached_input_unit_price >= (0)::numeric),
    currency_code           varchar(3)               default 'USD'::character varying,
    price_unit_token_count  integer                  default 1000000,
    price_effective_from    timestamp with time zone,
    price_updated_by        uuid
        constraint fk_ai_model_price_updated_by
            references app_user
            on delete restrict,
    price_updated_at        timestamp with time zone,
    data_processing_region  text                                                         not null,
    created_at              timestamp with time zone default CURRENT_TIMESTAMP           not null,
    updated_at              timestamp with time zone default CURRENT_TIMESTAMP           not null,
    constraint uq_ai_model_provider_provider_model_code
        unique (provider, provider_model_code),
    constraint ck_ai_model_price_completeness
        check (((input_unit_price IS NULL) AND (output_unit_price IS NULL)) OR
               (((currency_code)::text = 'USD'::text) AND (price_unit_token_count IS NOT NULL) AND
                (price_unit_token_count > 0) AND (price_effective_from IS NOT NULL) AND
                (price_updated_at IS NOT NULL))),
    constraint ck_ai_model_price_pair
        check ((input_unit_price IS NULL) = (output_unit_price IS NULL))
);

comment on table ai_model is '서비스가 사용할 수 있는 AI 모델의 공통 제품 목록입니다. 모델 이름, 공급자 코드, 처리 능력과 현재 단가를 한 번 등록하고 정책과 사용량 원장에서 논리 모델을 직접 참조합니다. | 정의서명: AiModel | 제약·비고: • model_code UNIQUE, UNIQUE(provider, provider_model_code) • 상태: ACTIVE / INACTIVE • context_window·max_output_tokens는 0보다 큼 • 입력·출력 단가는 함께 NULL 또는 함께 NOT NULL • 단가 설정 시 currency_code=''USD''·price_unit_token_count·price_effective_from·price_updated_at 필수 • 단가와 비용은 0 이상, 기본 단가 단위는 1,000,000 토큰 • 사용 이력 모델은 물리 삭제하지 않고 INACTIVE로 전환 • 입력·출력 단가 중 하나라도 설정하면 두 단가, USD 통화, 단가 기준 토큰 수와 적용 시각이 모두 완결되어야 합니다. • 단가 미완결 모델은 채점 정책이나 질문 생성·요약 티어 정책에 연결할 수 없습니다. • 단가 변경은 이후 호출부터 적용하며 과거 AiUsage 비용을 자동으로 다시 계산하지 않습니다. • 현재 활성 정책이 참조하는 모델을 비활성화할 때 서비스 호출 불가 여부를 먼저 검증합니다.';

comment on column ai_model.model_id is '논리 AI 모델을 식별한다.';

comment on column ai_model.model_code is '화면·API·사용량 집계의 불변 모델 코드다. 특정 목록을 DB 제약으로 고정하지 않는다.';

comment on column ai_model.provider is 'AI 공급자 코드다.';

comment on column ai_model.provider_model_code is '공급자 원본 모델 식별자다.';

comment on column ai_model.display_name is '사용자 화면 표시명이다.';

comment on column ai_model.status is '논리 모델 사용 가능 상태다. 사용 이력 모델은 물리 삭제하지 않는다.';

comment on column ai_model.capability_payload is '모델 역량 메타데이터다.';

comment on column ai_model.context_window is '최대 컨텍스트 윈도우다.';

comment on column ai_model.max_output_tokens is '최대 출력 토큰 수다.';

comment on column ai_model.input_unit_price is '현재 입력 토큰 단가다.';

comment on column ai_model.output_unit_price is '현재 출력 토큰 단가다.';

comment on column ai_model.cached_input_unit_price is '현재 캐시 입력 토큰 단가다.';

comment on column ai_model.currency_code is '단가 통화다. 단가가 설정되면 USD여야 한다.';

comment on column ai_model.price_unit_token_count is '단가 기준 토큰 수다.';

comment on column ai_model.price_effective_from is '현재 단가 적용 시작 시각이다.';

comment on column ai_model.price_updated_by is '현재 단가를 마지막으로 변경한 슈퍼어드민이다.';

comment on column ai_model.price_updated_at is '현재 단가의 마지막 변경 시각이다.';

comment on column ai_model.data_processing_region is '모델 데이터 처리 리전이다.';

comment on column ai_model.created_at is '생성 시각이다.';

comment on column ai_model.updated_at is '최근 변경 시각이다.';

alter table ai_model
    owner to postgres;

grant delete, insert, select, update on ai_model to teamiz_app;

create unique index uq_app_user_commit_email_active
    on app_user (org_id, commit_email_normalized)
    where ((commit_email_normalized IS NOT NULL) AND (deleted_at IS NULL));

grant delete, insert, select, update on app_user to teamiz_app;

create table organization
(
    org_id                       uuid                     default gen_random_uuid()           not null
        constraint pk_organization
            primary key,
    name                         varchar(200)                                                 not null,
    normalized_name              varchar(200)                                                 not null
        constraint uq_organization_normalized_name
            unique,
    slug                         varchar(64)
        constraint uq_organization_slug
            unique
        constraint ck_organization_slug
            check ((slug IS NULL) OR ((slug)::text ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'::text)),
    display_code                 varchar(32),
    email_domain                 varchar(255)
        constraint ck_organization_email_domain_format
            check ((email_domain IS NULL) OR ((email_domain)::text ~
                                              '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$'::text)),
    create_idempotency_key       uuid
        constraint uq_organization_create_idempotency_key
            unique,
    create_request_fingerprint   char(64)
        constraint ck_organization_create_request_fingerprint_format
            check ((create_request_fingerprint IS NULL) OR (create_request_fingerprint ~ '^[0-9a-f]{64}$'::text)),
    status                       varchar(100)             default 'ACTIVE'::character varying not null
        constraint ck_organization_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('SUSPENDED'::character varying)::text, ('DELETION_PENDING'::character varying)::text, ('DELETED'::character varying)::text])),
    retention_until              timestamp with time zone,
    suspended_at                 timestamp with time zone,
    suspended_reason             text,
    deletion_idempotency_key     uuid
        constraint uq_organization_deletion_idempotency_key
            unique,
    deletion_request_fingerprint char(64)
        constraint ck_organization_deletion_request_fingerprint_format
            check ((deletion_request_fingerprint IS NULL) OR (deletion_request_fingerprint ~ '^[0-9a-f]{64}$'::text)),
    deletion_requested_at        timestamp with time zone,
    deletion_requested_by        uuid
        constraint fk_organization_deletion_requested_by
            references app_user
            on delete restrict,
    purge_status                 varchar(30)              default 'NONE'::character varying   not null
        constraint ck_organization_purge_status
            check ((purge_status)::text = ANY
                   (ARRAY [('NONE'::character varying)::text, ('SCHEDULED'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('FAILED'::character varying)::text, ('COMPLETED'::character varying)::text])),
    purge_started_at             timestamp with time zone,
    purge_failed_at              timestamp with time zone,
    purge_failure_code           varchar(100)
        constraint ck_organization_purge_failure_code_format
            check ((purge_failure_code IS NULL) OR ((purge_failure_code)::text ~ '^[A-Z][A-Z0-9_]{0,99}$'::text)),
    restored_at                  timestamp with time zone,
    restored_by                  uuid
        constraint fk_organization_restored_by
            references app_user
            on delete restrict,
    deleted_at                   timestamp with time zone,
    created_by                   uuid                                                         not null
        constraint fk_organization_created_by
            references app_user
            on delete restrict,
    created_at                   timestamp with time zone default CURRENT_TIMESTAMP           not null,
    updated_by                   uuid
        constraint fk_organization_updated_by
            references app_user
            on delete restrict,
    updated_at                   timestamp with time zone default CURRENT_TIMESTAMP           not null,
    row_version                  integer                  default 0                           not null
        constraint ck_organization_row_version
            check (row_version >= 0),
    constraint ck_organization_create_key_pair
        check ((create_idempotency_key IS NULL) = (create_request_fingerprint IS NULL)),
    constraint ck_organization_deleted_at_required
        check (((status)::text <> 'DELETED'::text) OR (deleted_at IS NOT NULL)),
    constraint ck_organization_deletion_key_pair
        check ((deletion_idempotency_key IS NULL) = (deletion_request_fingerprint IS NULL)),
    constraint ck_organization_deletion_pending_fields
        check (((status)::text <> ALL
                (ARRAY [('DELETION_PENDING'::character varying)::text, ('DELETED'::character varying)::text])) OR
               ((retention_until IS NOT NULL) AND (deletion_requested_at IS NOT NULL) AND
                (deletion_requested_by IS NOT NULL))),
    constraint ck_organization_purge_failed_fields
        check (((purge_status)::text <> 'FAILED'::text) OR
               ((purge_failed_at IS NOT NULL) AND (purge_failure_code IS NOT NULL))),
    constraint ck_organization_restored_pair
        check ((restored_at IS NULL) = (restored_by IS NULL)),
    constraint ck_organization_suspended_at_required
        check (((status)::text <> 'SUSPENDED'::text) OR (suspended_at IS NOT NULL))
);

comment on table organization is '서비스를 이용하는 학교·회사 같은 한 고객 기관을 나타냅니다. 건물의 ‘기관 기본 카드’처럼 기관 이름과 운영 상태, 삭제·복구 기록을 한곳에서 관리하며 다른 기관의 데이터가 섞이지 않게 하는 가장 위쪽 기준점입니다. | 정의서명: Organization | 제약·비고: • 상태: ACTIVE / SUSPENDED / DELETION_PENDING / DELETED • normalized_name은 물리 파기 전 전체 기관 범위 UNIQUE • slug는 값이 있으면 전체 기관 UNIQUE • 생성·삭제 멱등성 키와 요청 지문은 각각 동시 NULL 또는 동시 NOT NULL • DELETION_PENDING·DELETED이면 deletion_requested_at·deletion_requested_by·retention_until 필수 • DELETED이면 deleted_at 필수 • 복구 일시와 복구자는 쌍으로 관리 • 파기 상태: NONE / SCHEDULED / IN_PROGRESS / FAILED / COMPLETED • purge_status는 NOT NULL·기본값 NONE이며 삭제 요청 성공 시 SCHEDULED로 전이 • BUDGET_EXCEEDED·ORGANIZATION_OPERATOR_EMPTY·display_status·is_new는 조회 파생값 • row_version >= 0 • purge_failure_code는 영문 대문자·숫자·밑줄 1~100자의 확장형 서비스 코드 카탈로그를 사용하며 DB CHECK는 형식만 검증 • status=''SUSPENDED''이면 suspended_at이 필수이며, 정지는 기관 사용자의 로그인·업무 요청을 막지만 사용자 행을 일괄 삭제하거나 INACTIVE로 바꾸지 않습니다. • purge_status=''FAILED''이면 purge';

comment on column organization.org_id is '기관·테넌트를 식별한다.';

comment on column organization.name is '화면 표시 기관명이다.';

comment on column organization.normalized_name is 'Unicode NFKC, trim, 연속 공백 축소, 영문 case folding을 적용한 기관명이다. 물리 파기 전에는 상태와 관계없이 재사용하지 않는다.';

comment on column organization.slug is 'URL·외부 연동용 짧은 기관 식별값이다.';

comment on column organization.display_code is '화면 표시용 기관 코드다.';

comment on column organization.email_domain is '기관 사용자 초대 입력 검증용 도메인이다. NULL이면 도메인 제한을 적용하지 않으며 권한·테넌트의 단독 근거로 사용하지 않는다.';

comment on column organization.create_idempotency_key is 'SA-01 기관 생성 성공 결과를 클라이언트 생성 의도와 연결한다. 이관·시드 행은 NULL을 허용한다.';

comment on column organization.create_request_fingerprint is '정규화한 기관 생성 요청의 지문이다. 동일 멱등성 키 재사용 시 요청 동일성을 검증한다.';

comment on column organization.status is '기관 생명주기 상태다. 비용·미배정 경고는 저장하지 않는다.';

comment on column organization.retention_until is '삭제 요청 시점의 유효 `retention_days`로 고정한 보존 만료 시각이다. 이후 정책 변경으로 자동 갱신하지 않는다.';

comment on column organization.suspended_at is '기관 정지 시각이다.';

comment on column organization.suspended_reason is '기관 정지 사유다.';

comment on column organization.deletion_idempotency_key is '기관 삭제 요청의 사용자 의도를 식별한다. 같은 키·같은 지문은 최초 결과를 반환한다.';

comment on column organization.deletion_request_fingerprint is '기관명 확인값과 삭제 요청을 정규화한 지문이다.';

comment on column organization.deletion_requested_at is '삭제 요청 시각이다.';

comment on column organization.deletion_requested_by is '삭제 요청 슈퍼어드민이다.';

comment on column organization.purge_status is '기관 물리 파기 진행 상태다. 삭제 요청 성공 시 `SCHEDULED`로 전이한다.';

comment on column organization.purge_started_at is '최근 물리 파기 실행 시작 시각이다.';

comment on column organization.purge_failed_at is '최근 물리 파기 실패 시각이다.';

comment on column organization.purge_failure_code is '`SYS.ORGANIZATION_PURGE_FAILURE_CODE` 확장형 서비스 코드 카탈로그다.';

comment on column organization.restored_at is '보존기간 내 삭제 요청을 복구한 최근 시각이다.';

comment on column organization.restored_by is '최근 복구 슈퍼어드민이다.';

comment on column organization.deleted_at is '삭제 확정 시각이며 그 전에는 NULL이다.';

comment on column organization.created_by is '기관 생성자다.';

comment on column organization.created_at is '최초 생성 시각이다.';

comment on column organization.updated_by is '최근 변경자다.';

comment on column organization.updated_at is '최근 변경 시각이다.';

comment on column organization.row_version is '낙관적 잠금 버전이다.';

alter table organization
    owner to postgres;

alter table app_user
    add constraint fk_app_user_org_id
        foreign key (org_id) references organization
            on delete restrict;

create table audit_log
(
    audit_id        uuid                     default gen_random_uuid() not null
        constraint pk_audit_log
            primary key,
    org_id          uuid
        constraint fk_audit_log_org_id
            references organization
            on delete restrict,
    actor_user_id   uuid
        constraint fk_audit_log_actor_user_id
            references app_user
            on delete restrict,
    actor_type      varchar(100)                                       not null
        constraint ck_audit_log_actor_type
            check ((actor_type)::text = ANY
                   (ARRAY [('USER'::character varying)::text, ('SYSTEM'::character varying)::text, ('JOB'::character varying)::text, ('DB_TRIGGER'::character varying)::text])),
    event_code      varchar(100)                                       not null
        constraint ck_audit_log_event_code_format
            check ((event_code)::text ~ '^[A-Z0-9_]+\.[A-Z0-9_]+$'::text),
    action          text                                               not null,
    target_type     varchar(100)                                       not null
        constraint ck_audit_log_target_type_format
            check ((target_type)::text ~ '^[A-Z][A-Z0-9_]*$'::text),
    target_id       text                                               not null,
    result          varchar(20)                                        not null
        constraint ck_audit_log_result
            check ((result)::text = ANY
                   (ARRAY [('SUCCESS'::character varying)::text, ('FAILURE'::character varying)::text, ('DENIED'::character varying)::text, ('PARTIAL'::character varying)::text])),
    failure_reason  text,
    before_snapshot jsonb,
    after_snapshot  jsonb,
    request_id      uuid                                               not null,
    trace_id        text                                               not null,
    correlation_id  uuid,
    source_ip       inet,
    user_agent      text,
    occurred_at     timestamp with time zone                           not null,
    recorded_at     timestamp with time zone default CURRENT_TIMESTAMP not null,
    integrity_hash  varchar(128)                                       not null
);

comment on table audit_log is '누가, 언제, 무엇을, 어떤 결과로 처리했는지를 남기는 변경 불가 기록입니다. 학교의 출입 기록이나 CCTV 기록처럼 보안 사고와 정책 변경을 나중에 추적할 수 있게 하며, 기존 기록을 고쳐 쓰거나 지우지 않습니다. | 정의서명: AuditLog | 제약·비고: • APPEND-ONLY: 애플리케이션 계정 UPDATE·DELETE 권한 회수 및 차단 • actor_type: USER / SYSTEM / JOB / DB_TRIGGER • USER이면 actor_user_id 필수, 그 외 유형은 actor_user_id NULL • result: SUCCESS / FAILURE / DENIED / PARTIAL • FAILURE·DENIED이면 failure_reason 필수 • target_type·target_id는 허용 목록과 서비스 검증을 사용하는 다형 논리 참조 • 스냅샷은 허용 필드만 저장하고 비밀·개인정보를 마스킹 • org_id 직접 RLS 적용 및 대상 기관 경로 일치 • event_code는 ''도메인.행위'' 형식의 확장형 감사 이벤트 카탈로그 • target_type은 허용 엔터티 물리명에 대응하는 영문 대문자 스네이크 케이스 카탈로그 • USER 행위자의 actor_user_id는 클라이언트가 보낸 사용자 ID가 아니라 검증된 인증 토큰에서 결정합니다. • 기관 생성 같은 업무가 롤백되어도 운영 장애 추적용 실패 감사는 업무 트랜잭션과 분리하여 남길 수 있습니다. • 원문 토큰, API 키, secret_ref의 실제 비밀값과 불필요한 개인정보를 before_snapshot·after_snapshot에 넣지 않습니다. • integrity_hash의 해시 체인·서명 방식은 별도 보안 운영 정책에서 확정합니다.';

comment on column audit_log.audit_id is '감사 로그 행을 식별한다. 애플리케이션 계정의 UPDATE·DELETE를 차단한다.';

comment on column audit_log.org_id is '기관 관련 감사 기록의 테넌트 경계다. 기관 미소속 플랫폼 행위는 NULL을 허용한다.';

comment on column audit_log.actor_user_id is '사용자 행위자의 계정 ID다.';

comment on column audit_log.actor_type is '감사 행위자 유형이다.';

comment on column audit_log.event_code is '감사 이벤트 코드다. 예: `AUTH.LOGIN_SUCCESS`.';

comment on column audit_log.action is '사람이 읽을 수 있는 행위 요약이다.';

comment on column audit_log.target_type is '다형 논리 참조 대상 유형이다.';

comment on column audit_log.target_id is '감사 대상 식별자다.';

comment on column audit_log.result is '감사 처리 결과다.';

comment on column audit_log.failure_reason is '실패·거부 사유다.';

comment on column audit_log.before_snapshot is '변경 전 스냅샷이다.';

comment on column audit_log.after_snapshot is '변경 후 스냅샷이다.';

comment on column audit_log.request_id is '현재 HTTP 요청 또는 백그라운드 실행 ID다.';

comment on column audit_log.trace_id is '분산 추적 ID다.';

comment on column audit_log.correlation_id is '최초 요청과 후속 비동기 작업·재시도를 연결한다.';

comment on column audit_log.source_ip is '행위 원천 IP다.';

comment on column audit_log.user_agent is '행위 사용자 에이전트다.';

comment on column audit_log.occurred_at is '감사 대상 행위가 실제 발생한 시각이다.';

comment on column audit_log.recorded_at is '감사 원장 기록 시각이다.';

comment on column audit_log.integrity_hash is '감사 레코드 무결성 검증 해시다.';

alter table audit_log
    owner to postgres;

grant delete, insert, select, update on audit_log to teamiz_app;

create table consent_record
(
    consent_id      uuid                     default gen_random_uuid() not null
        constraint pk_consent_record
            primary key,
    org_id          uuid
        constraint fk_consent_record_org_id
            references organization
            on delete restrict,
    user_id         uuid                                               not null
        constraint fk_consent_record_user_id
            references app_user
            on delete restrict,
    consent_code    varchar(100)                                       not null
        constraint ck_consent_record_consent_code
            check ((consent_code)::text = ANY
                   (ARRAY [('TERMS_OF_SERVICE'::character varying)::text, ('PRIVACY_POLICY'::character varying)::text, ('AI_ANALYSIS'::character varying)::text, ('ORGANIZATION_SHARING'::character varying)::text, ('ANONYMIZED_DATA_USAGE'::character varying)::text])),
    policy_version  integer                                            not null
        constraint ck_consent_record_policy_version
            check (policy_version > 0),
    agreed          boolean                                            not null,
    agreed_at       timestamp with time zone                           not null,
    capture_channel varchar(30)                                        not null
        constraint ck_consent_record_capture_channel
            check ((capture_channel)::text = ANY
                   (ARRAY [('INVITE_LINK'::character varying)::text, ('WEB'::character varying)::text, ('ADMIN'::character varying)::text])),
    source_ip       inet,
    user_agent      text,
    locale          varchar(20)                                        not null,
    evidence_hash   varchar(128)                                       not null,
    created_at      timestamp with time zone default CURRENT_TIMESTAMP not null
);

comment on table consent_record is '서비스 약관·개인정보 처리·AI 분석·기관 공유·익명 활용에 대한 사용자의 동의·거부·철회 결정을 정책 버전별로 보존하는 불변 이력입니다. 현재 동의 상태만 저장하지 않고 각 결정 시점의 정책·채널·근거를 재현할 수 있게 합니다. | 정의서명: ConsentRecord | 제약·비고: • 테이블 유형은 HISTORY이며 완전한 APPEND-ONLY 원장입니다. • consent_code: TERMS_OF_SERVICE / PRIVACY_POLICY / AI_ANALYSIS / ORGANIZATION_SHARING / ANONYMIZED_DATA_USAGE • capture_channel: INVITE_LINK / WEB / ADMIN • policy_version은 0보다 커야 하며 evidence_hash와 함께 당시 표시 정책의 근거를 식별합니다. • agreed=TRUE는 동의, FALSE는 거부 또는 철회 결정입니다. • agreed_at은 호환성 물리명을 유지하지만 동의·거부·철회의 공통 결정 시각이며 API에서는 decidedAt 의미로 노출합니다. • 동의 변경·철회는 기존 행 UPDATE가 아니라 같은 user_id·consent_code·policy_version의 새 행을 추가합니다. • 최신 결정은 created_at DESC, consent_id DESC 순서로 판정합니다. • OPERATOR·MANAGER는 필수 2개, TRAINEE는 필수 4개와 선택 1개, SUPER_ADMIN은 필수 2개의 결정을 가입·활성화 트랜잭션에서 저장합니다. • org_id는 SUPER_ADMIN(무소속)이면 NULL, 그 외에는 user_id가 속한 기관과 반드시 일치하며 기관 격리와 RLS 기준으로 사용합니다. • 정책 원문 전체나 민감정보를 저장하지 않고 evidence_hash와 버전으로 무결성을 확인합니다.';

comment on column consent_record.consent_id is '동의 이력 기본키이다.';

comment on column consent_record.org_id is '동의 기록의 직접 테넌트 경계이다. 슈퍼어드민(무소속) 사용자의 동의 기록은 NULL이며, 그 외에는 user_id가 속한 기관과 반드시 일치해야 한다(애플리케이션이 보장).';

comment on column consent_record.user_id is '동의 결정을 수행한 사용자이다.';

comment on column consent_record.consent_code is '동의 항목 코드이다.';

comment on column consent_record.policy_version is '사용자가 확인한 동의 문서 버전이다.';

comment on column consent_record.agreed is '해당 버전·항목에 대한 동의 여부이다.';

comment on column consent_record.agreed_at is '동의·거부·철회 결정이 이루어진 시각이다.';

comment on column consent_record.capture_channel is '동의를 수집한 채널이다.';

comment on column consent_record.source_ip is '동의 요청 원천 IP이다.';

comment on column consent_record.user_agent is '동의 요청 사용자 에이전트이다.';

comment on column consent_record.locale is '사용자가 확인한 동의 문서 로케일이다.';

comment on column consent_record.evidence_hash is '정책 본문·표시 정보의 무결성 검증 해시이다.';

comment on column consent_record.created_at is '동의 이력 생성 시각이다.';

alter table consent_record
    owner to postgres;

grant delete, insert, select, update on consent_record to teamiz_app;

create table curriculum_material
(
    material_id      uuid                     default gen_random_uuid() not null
        constraint pk_curriculum_material
            primary key,
    org_id           uuid                                               not null
        constraint fk_curriculum_material_org_id
            references organization
            on delete restrict,
    title            varchar(200)                                       not null,
    normalized_title varchar(200)                                       not null,
    topic            text,
    material_type    varchar(100),
    created_by       uuid                                               not null
        constraint fk_curriculum_material_created_by
            references app_user
            on delete restrict,
    created_at       timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at       timestamp with time zone default CURRENT_TIMESTAMP not null,
    deleted_at       timestamp with time zone,
    constraint uq_curriculum_material_org_id_normalized_title
        unique (org_id, normalized_title)
);

comment on table curriculum_material is '기관에서 공통으로 사용하는 교안의 논리 식별자입니다. 실제 PDF 원본과 파일 메타데이터는 CurriculumVersion이 소유하며, 최신 버전·분석 상태·섹션·개념·프로젝트 사용 현황은 View에서 파생합니다. | 정의서명: CurriculumMaterial | 제약·비고: • 교안은 특정 기수·반에 직접 귀속하지 않는 기관 공용 자산입니다. • 사용 기수는 ProjectCurriculum → Project → Cohort 경로에서 파생합니다. • 필수 등록 입력은 title과 PDF이며 topic·material_type은 선택 메타데이터입니다. • normalized_title은 기관 범위 검색·정렬에 사용하지만 현재 요구사항에는 UNIQUE(org_id, normalized_title)가 확정되어 있지 않습니다. • PDF 원본·파일 URI·해시·페이지 수는 CurriculumVersion이 소유합니다. • 최신 버전 ID·분석 상태·섹션 수·개념 수·연결 프로젝트 수를 이 엔터티에 중복 저장하지 않습니다. • 다른 기관 간 공유·플랫폼 공용 카탈로그는 지원하지 않습니다. • CurriculumVersion 또는 ProjectCurriculum 참조가 있으면 물리 삭제를 금지하고 deleted_at으로 논리 삭제합니다. • 논리 삭제된 교안은 신규 버전 생성·프로젝트 연결 대상에서 제외하지만 과거 조회·리포트 근거는 유지합니다.';

comment on column curriculum_material.material_id is '기관 공용 교안의 논리 자산을 식별한다.';

comment on column curriculum_material.org_id is '교안 소유 기관이며 RLS 테넌트 경계로 사용한다.';

comment on column curriculum_material.title is '교안의 화면 표시 제목이다.';

comment on column curriculum_material.normalized_title is '기관 범위 검색·중복 탐지에 사용하는 정규화 제목이다.';

comment on column curriculum_material.topic is '교안의 선택 학습 주제 메타데이터이다.';

comment on column curriculum_material.material_type is '교안의 선택 유형 메타데이터이다.';

comment on column curriculum_material.created_by is '교안 등록자를 식별한다.';

comment on column curriculum_material.created_at is '교안 논리 자산이 생성된 시각이다.';

comment on column curriculum_material.updated_at is '교안 메타데이터의 최근 변경 시각이다.';

comment on column curriculum_material.deleted_at is '교안이 논리 삭제된 시각이다.';

alter table curriculum_material
    owner to postgres;

grant delete, insert, select, update on curriculum_material to teamiz_app;

create table curriculum_version
(
    version_id         uuid                     default gen_random_uuid()           not null
        constraint pk_curriculum_version
            primary key,
    material_id        uuid                                                         not null
        constraint fk_curriculum_version_material_id
            references curriculum_material
            on delete restrict,
    version_no         integer                                                      not null
        constraint ck_curriculum_version_version_no
            check (version_no > 0),
    original_file_name varchar(255)                                                 not null,
    file_uri           text                                                         not null,
    mime_type          varchar(100)                                                 not null
        constraint ck_curriculum_version_mime_type
            check ((mime_type)::text = 'application/pdf'::text),
    file_size_bytes    bigint                                                       not null
        constraint ck_curriculum_version_file_size_bytes
            check (file_size_bytes > 0),
    content_hash       varchar(128)                                                 not null,
    page_count         integer
        constraint ck_curriculum_version_page_count
            check ((page_count IS NULL) OR (page_count > 0)),
    status             varchar(30)              default 'ACTIVE'::character varying not null
        constraint ck_curriculum_version_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])),
    created_by         uuid                                                         not null
        constraint fk_curriculum_version_created_by
            references app_user
            on delete restrict,
    created_at         timestamp with time zone default CURRENT_TIMESTAMP           not null,
    constraint uq_curriculum_version_material_id_content_hash
        unique (material_id, content_hash),
    constraint uq_curriculum_version_material_id_version_no
        unique (material_id, version_no)
);

comment on table curriculum_version is '교안 PDF 원본의 내용이 달라질 때만 생성하는 불변 버전 원장입니다. 같은 파일 내용의 재분석은 새 버전을 만들지 않고 동일 version_id 아래 새로운 CurriculumAnalysis 실행으로 보존합니다. | 정의서명: CurriculumVersion | 제약·비고: • version_no > 0, UNIQUE(material_id, version_no) • 같은 교안에서 동일 파일 내용을 중복 버전으로 만들지 않도록 UNIQUE(material_id, content_hash) 또는 동등한 잠금 중복 검증을 적용합니다. • 최초 버전은 version_no=1이며 새 버전 번호는 material_id 기준 잠금 후 원자 증가합니다. • 원본 파일 내용이 달라질 때만 신규 version_no를 생성합니다. • 동일 content_hash의 재분석은 같은 version_id 아래 새 CurriculumAnalysis를 생성합니다. • 상태: ACTIVE / INACTIVE • ACTIVE는 신규 분석·프로젝트 연결 가능, INACTIVE는 과거 참조만 유지하고 신규 연결을 차단합니다. • mime_type=''application/pdf'', file_size_bytes > 0, page_count IS NULL OR page_count > 0 • version_no·file_uri·content_hash·원본 파일 메타데이터는 생성 후 변경하지 않습니다. • 프로젝트·문제·리포트가 참조하는 버전은 자동 교체하거나 물리 삭제하지 않습니다.';

comment on column curriculum_version.version_id is '교안 원본 버전을 식별한다.';

comment on column curriculum_version.material_id is '버전이 속한 논리 교안을 참조한다.';

comment on column curriculum_version.version_no is '교안 내 증가하는 원본 버전 번호이다.';

comment on column curriculum_version.original_file_name is '업로드 당시 PDF 원본 파일명이다.';

comment on column curriculum_version.file_uri is '불변 PDF 원본의 저장 위치이다.';

comment on column curriculum_version.mime_type is '원본 파일의 MIME 유형이다.';

comment on column curriculum_version.file_size_bytes is '원본 PDF 파일 크기이다.';

comment on column curriculum_version.content_hash is '원본 내용 동일성·무결성 검증용 해시이다.';

comment on column curriculum_version.page_count is '성공한 분석에서 확정된 PDF 페이지 수이다.';

comment on column curriculum_version.status is '원본 버전 생명주기 상태이다.';

comment on column curriculum_version.created_by is '버전 등록자를 식별한다.';

comment on column curriculum_version.created_at is '원본 버전 생성 시각이다.';

alter table curriculum_version
    owner to postgres;

create table curriculum_analysis
(
    analysis_id          uuid                     default gen_random_uuid()            not null
        constraint pk_curriculum_analysis
            primary key,
    version_id           uuid                                                          not null
        constraint fk_curriculum_analysis_version_id
            references curriculum_version
            on delete restrict,
    model_id             uuid                                                          not null
        constraint fk_curriculum_analysis_model_id
            references ai_model
            on delete restrict,
    retry_of_analysis_id uuid
        constraint fk_curriculum_analysis_retry_of_analysis_id
            references curriculum_analysis
            on delete restrict,
    analysis_version     integer                                                       not null
        constraint ck_curriculum_analysis_analysis_version
            check (analysis_version > 0),
    status               varchar(30)              default 'PENDING'::character varying not null
        constraint ck_curriculum_analysis_status
            check ((status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])),
    fallback_used        boolean                  default false                        not null,
    idempotency_key      uuid                                                          not null,
    request_fingerprint  char(64)                                                      not null
        constraint ck_curriculum_analysis_request_fingerprint
            check (request_fingerprint ~ '^[0-9a-f]{64}$'::text),
    request_reason       varchar(100),
    impact_acknowledged  boolean                                                       not null,
    requested_by         uuid                                                          not null
        constraint fk_curriculum_analysis_requested_by
            references app_user
            on delete restrict,
    requested_at         timestamp with time zone default CURRENT_TIMESTAMP            not null,
    started_at           timestamp with time zone,
    completed_at         timestamp with time zone,
    failed_at            timestamp with time zone,
    failure_code         varchar(100)
        constraint ck_curriculum_analysis_failure_code
            check ((failure_code)::text = ANY
                   (ARRAY [('PDF_PASSWORD_PROTECTED'::character varying)::text, ('INVALID_PDF'::character varying)::text, ('CORRUPTED_PDF'::character varying)::text, ('STORAGE_FILE_NOT_FOUND'::character varying)::text, ('STORAGE_READ_FAILED'::character varying)::text, ('TEXT_EXTRACTION_FAILED'::character varying)::text, ('SCANNED_PDF_UNSUPPORTED'::character varying)::text, ('MODEL_TIMEOUT'::character varying)::text, ('RATE_LIMITED'::character varying)::text, ('PROVIDER_ERROR'::character varying)::text, ('INVALID_AI_RESPONSE'::character varying)::text, ('CONTEXT_OVERFLOW'::character varying)::text, ('INTERNAL_ERROR'::character varying)::text])),
    failure_stage        varchar(100)
        constraint ck_curriculum_analysis_failure_stage
            check ((failure_stage)::text = ANY
                   (ARRAY [('FILE_VALIDATION'::character varying)::text, ('FILE_RETRIEVAL'::character varying)::text, ('TEXT_EXTRACTION'::character varying)::text, ('STRUCTURE_EXTRACTION'::character varying)::text, ('CONCEPT_EXTRACTION'::character varying)::text, ('CONCEPT_MAPPING'::character varying)::text, ('RESULT_PERSISTENCE'::character varying)::text])),
    failure_reason       text,
    is_retryable         boolean,
    recovery_action      varchar(50)
        constraint ck_curriculum_analysis_recovery_action
            check ((recovery_action)::text = ANY
                   (ARRAY [('RETRY_SAME_FILE'::character varying)::text, ('REPLACE_FILE'::character varying)::text, ('CONTACT_SUPPORT'::character varying)::text])),
    created_at           timestamp with time zone default CURRENT_TIMESTAMP            not null,
    external_job_id      uuid
        constraint uq_curriculum_analysis_external_job_id
            unique,
    constraint uq_curriculum_analysis_version_id_idempotency_key
        unique (version_id, idempotency_key)
);

comment on table curriculum_analysis is '교안 버전별 최초 분석과 재분석 요청·실행·실패·복구를 보존하는 실행 원장입니다. 최근 실행과 마지막 성공 실행을 구분하고 성공 결과의 섹션·개념 매핑을 기존 결과에 덮어쓰지 않습니다. | 정의서명: CurriculumAnalysis | 제약·비고: • 상태: PENDING / RUNNING / SUCCEEDED / FAILED • analysis_version은 실행 순번이 아니라 분석 정책·파이프라인 버전이며 모델·정책 버전은 request_fingerprint에도 포함합니다. • UNIQUE(version_id, idempotency_key) • 같은 version_id의 PENDING/RUNNING 실행은 최대 1건입니다. • retry_of_analysis_id는 같은 version_id의 이전 FAILED 또는 재시도 대상 실행만 참조하고 순환을 금지합니다. • 최초 분석은 request_reason NULL을 허용하고 재분석·재시도는 request_reason을 필수로 기록합니다. • 영향 대상이 존재하는 재분석은 impact_acknowledged=TRUE여야 하며 영향 산식·확인값을 AuditLog 요청 스냅샷에도 보존합니다. • PENDING은 started_at·completed_at·failed_at과 실패 필드가 모두 NULL입니다. • RUNNING은 started_at이 필수이고 completed_at·failed_at과 실패 필드는 NULL입니다. • SUCCEEDED는 started_at·completed_at이 필수이고 실패 필드는 NULL입니다. • FAILED는 started_at·failed_at·failure_code·failure_stage·failure_reason·is_retryable·recovery_action이 필수이고 complete';

comment on column curriculum_analysis.analysis_id is '개별 교안 분석 실행을 식별한다.';

comment on column curriculum_analysis.version_id is '분석 대상 교안 원본 버전이다.';

comment on column curriculum_analysis.model_id is '실제 분석에 사용한 논리 AI 모델이다.';

comment on column curriculum_analysis.retry_of_analysis_id is '재시도한 이전 분석 실행이다.';

comment on column curriculum_analysis.analysis_version is '분석 로직·산출물 계약 버전이다.';

comment on column curriculum_analysis.status is '교안 분석 실행 상태이다.';

comment on column curriculum_analysis.fallback_used is '정상 추출 대신 대체 처리 결과를 사용했는지 나타낸다.';

comment on column curriculum_analysis.idempotency_key is '같은 버전 분석 요청의 중복 실행을 방지한다.';

comment on column curriculum_analysis.request_fingerprint is '정규화 분석 요청의 SHA-256 지문이다.';

comment on column curriculum_analysis.request_reason is '재분석 요청 사유 값을 저장한다.';

comment on column curriculum_analysis.impact_acknowledged is '영향 확인 여부 값을 저장한다.';

comment on column curriculum_analysis.requested_by is '분석 또는 재분석을 요청한 사용자이다.';

comment on column curriculum_analysis.requested_at is '분석 요청 접수 시각이다.';

comment on column curriculum_analysis.started_at is '분석 실행 시작 시각이다.';

comment on column curriculum_analysis.completed_at is '성공 완료 시각이다.';

comment on column curriculum_analysis.failed_at is '실패 확정 시각이다.';

comment on column curriculum_analysis.failure_code is '안정적인 분석 실패 코드이다.';

comment on column curriculum_analysis.failure_stage is '분석 실패가 발생한 처리 단계이다.';

comment on column curriculum_analysis.failure_reason is '운영 진단용 실패 상세 사유이다.';

comment on column curriculum_analysis.is_retryable is '같은 원본으로 재시도할 수 있는지 나타낸다.';

comment on column curriculum_analysis.recovery_action is '사용자에게 제시할 복구 행동이다.';

comment on column curriculum_analysis.created_at is '분석 실행 원장 생성 시각이다.';

comment on column curriculum_analysis.external_job_id is 'AI 서버가 반환한 교안 분석 작업 ID를 보관한다.';

alter table curriculum_analysis
    owner to postgres;

create unique index uq_curriculum_analysis_active_per_version
    on curriculum_analysis (version_id)
    where ((status)::text = ANY (ARRAY [('PENDING'::character varying)::text, ('RUNNING'::character varying)::text]));

grant delete, insert, select, update on curriculum_analysis to teamiz_app;

grant delete, insert, select, update on curriculum_version to teamiz_app;

create table curriculum_section
(
    section_id         uuid default gen_random_uuid() not null
        constraint pk_curriculum_section
            primary key,
    version_id         uuid                           not null
        constraint fk_curriculum_section_version_id
            references curriculum_version
            on delete restrict,
    source_analysis_id uuid                           not null
        constraint fk_curriculum_section_source_analysis_id
            references curriculum_analysis
            on delete restrict,
    sequence_no        integer                        not null
        constraint ck_curriculum_section_sequence_no
            check (sequence_no > 0),
    title              varchar(200)                   not null,
    page_start         integer                        not null
        constraint ck_curriculum_section_page_start
            check (page_start > 0),
    page_end           integer                        not null,
    keywords           jsonb                          not null,
    confidence         numeric(18, 6)                 not null
        constraint ck_curriculum_section_confidence
            check ((confidence >= (0)::numeric) AND (confidence <= (1)::numeric)),
    constraint uq_curriculum_section_source_analysis_id_sequence_no
        unique (source_analysis_id, sequence_no),
    constraint ck_curriculum_section_page_end
        check (page_end >= page_start)
);

comment on table curriculum_section is '특정 CurriculumAnalysis가 교안 버전에서 추출한 의미 단위 섹션입니다. 성공 분석마다 새로운 섹션 집합을 생성하며 과거 성공 분석의 섹션은 수정·삭제하지 않습니다. | 정의서명: CurriculumSection | 제약·비고: • sequence_no > 0, UNIQUE(source_analysis_id, sequence_no) • CurriculumSection.version_id = CurriculumAnalysis.version_id • SUCCEEDED 분석 결과 확정 전 CurriculumVersion.page_count는 양수여야 합니다. • 1 <= page_start <= page_end <= CurriculumVersion.page_count • 동일 분석 실행의 섹션 페이지 범위 중첩은 허용합니다. • 표시 순서는 페이지 번호가 아니라 sequence_no를 사용합니다. • SUCCEEDED 분석의 섹션은 불변이며 재분석은 새 source_analysis_id 집합을 생성합니다. • title이 비어 있을 때의 폴백 명칭, keywords 자료형, confidence 단위·범위는 현재 요구사항에서 확정되지 않았으므로 임의 CHECK를 추가하지 않습니다. • created_at을 순서·유일성 기준으로 사용하지 않습니다.';

comment on column curriculum_section.section_id is '분석 실행이 생성한 의미 단위 섹션을 식별한다.';

comment on column curriculum_section.version_id is '섹션이 속한 교안 원본 버전이다.';

comment on column curriculum_section.source_analysis_id is '섹션을 생성한 정확한 분석 실행이다.';

comment on column curriculum_section.sequence_no is '분석 결과에서 사용하는 섹션 표시 순서이다.';

comment on column curriculum_section.title is '분석에서 추출한 섹션 제목이다.';

comment on column curriculum_section.page_start is '섹션 시작 페이지 번호이다.';

comment on column curriculum_section.page_end is '섹션 종료 페이지 번호이다.';

comment on column curriculum_section.keywords is '섹션에서 추출한 핵심어 목록이다.';

comment on column curriculum_section.confidence is '섹션 추출 신뢰도이다.';

alter table curriculum_section
    owner to postgres;

grant delete, insert, select, update on curriculum_section to teamiz_app;

create table teaches
(
    teaches_id             uuid                     default gen_random_uuid()           not null
        constraint pk_teaches
            primary key,
    org_id                 uuid                                                         not null
        constraint fk_teaches_org_id
            references organization
            on delete restrict,
    canonical_name         varchar(200)                                                 not null,
    normalized_name        varchar(200)                                                 not null,
    canonical_description  text                                                         not null,
    status                 varchar(30)              default 'ACTIVE'::character varying not null
        constraint ck_teaches_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text, ('MERGED'::character varying)::text])),
    merged_into_teaches_id uuid
        constraint fk_teaches_merged_into_teaches_id
            references teaches
            on delete restrict,
    created_by             uuid
        constraint fk_teaches_created_by
            references app_user
            on delete restrict,
    created_at             timestamp with time zone default CURRENT_TIMESTAMP           not null,
    updated_at             timestamp with time zone default CURRENT_TIMESTAMP           not null
);

comment on table teaches is '여러 교안 버전에서 재사용하는 기관 단위 표준 학습 개념 원장입니다. 교안별 표현·설명·페이지는 CurriculumTeachesMapping이 소유하고, 본 엔터티는 표준명과 대표 설명·병합 상태만 관리합니다. | 정의서명: Teaches | 제약·비고: • 상태: ACTIVE / INACTIVE / MERGED • canonical_name·normalized_name은 공백일 수 없습니다. • 활성 개념 기준 부분 UNIQUE(org_id, normalized_name)를 적용합니다. • ACTIVE·INACTIVE이면 merged_into_teaches_id는 NULL이고 MERGED이면 필수입니다. • merged_into_teaches_id는 같은 기관의 자기 자신이 아닌 대표 개념을 가리켜야 하며 순환 병합을 금지합니다. • 대표 개념이 다시 MERGED인 다단 연결을 저장하지 않고 최종 비병합 대표를 직접 가리키도록 합니다. • 특정 교안·버전·섹션·페이지를 직접 참조하지 않습니다. • 교안별 출처 설명·페이지 위치는 CurriculumTeachesMapping이 소유합니다. • 참조 매핑·프로젝트 검증 개념·평가 문제가 있으면 물리 삭제하지 않고 INACTIVE 또는 MERGED로 전이합니다. • 표준 명칭·대표 설명·병합 상태 변경은 AuditLog에 변경 전후 값과 처리자를 필수 기록합니다.';

comment on column teaches.teaches_id is '기관 공통 표준 학습 개념을 식별한다.';

comment on column teaches.org_id is '표준 개념 소유 기관이며 RLS 경계이다.';

comment on column teaches.canonical_name is '기관에서 사용하는 표준 개념명이다.';

comment on column teaches.normalized_name is '활성 개념 중복 판정에 사용하는 정규화 이름이다.';

comment on column teaches.canonical_description is '교안에 종속되지 않는 표준 개념 설명이다.';

comment on column teaches.status is '표준 개념 상태이다.';

comment on column teaches.merged_into_teaches_id is 'MERGED 상태에서 대표 개념을 참조한다.';

comment on column teaches.created_by is '개념 등록자를 식별한다.';

comment on column teaches.created_at is '표준 개념 생성 시각이다.';

comment on column teaches.updated_at is '표준 개념의 최근 변경 시각이다.';

alter table teaches
    owner to postgres;

create table curriculum_teaches_mapping
(
    mapping_id         uuid        default gen_random_uuid()            not null
        constraint pk_curriculum_teaches_mapping
            primary key,
    org_id             uuid                                             not null
        constraint fk_curriculum_teaches_mapping_org_id
            references organization
            on delete restrict,
    teaches_id         uuid                                             not null
        constraint fk_curriculum_teaches_mapping_teaches_id
            references teaches
            on delete restrict,
    version_id         uuid                                             not null
        constraint fk_curriculum_teaches_mapping_version_id
            references curriculum_version
            on delete restrict,
    section_id         uuid
        constraint fk_curriculum_teaches_mapping_section_id
            references curriculum_section
            on delete restrict,
    source_analysis_id uuid                                             not null
        constraint fk_curriculum_teaches_mapping_source_analysis_id
            references curriculum_analysis
            on delete restrict,
    extracted_name     varchar(200)                                     not null,
    source_description text,
    page_start         integer                                          not null
        constraint ck_curriculum_teaches_mapping_page_start
            check (page_start > 0),
    page_end           integer                                          not null,
    sequence_no        integer                                          not null
        constraint ck_curriculum_teaches_mapping_sequence_no
            check (sequence_no > 0),
    confidence         numeric(18, 6)                                   not null
        constraint ck_curriculum_teaches_mapping_confidence
            check ((confidence >= (0)::numeric) AND (confidence <= (1)::numeric)),
    teach_kind         varchar(30) default 'CONCEPT'::character varying not null
        constraint ck_curriculum_teaches_mapping_teach_kind
            check ((teach_kind)::text = ANY
                   (ARRAY [('CONCEPT'::character varying)::text, ('CODE_EXAMPLE'::character varying)::text, ('CAUTION'::character varying)::text])),
    source_evidence    text,
    sibling_names      jsonb       default '[]'::jsonb                  not null,
    mapping_status     varchar(30)                                      not null
        constraint ck_curriculum_teaches_mapping_mapping_status
            check ((mapping_status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('REVIEW_REQUIRED'::character varying)::text, ('INACTIVE'::character varying)::text])),
    source_pages       jsonb                                            not null
        constraint ck_curriculum_teaches_mapping_source_pages
            check (jsonb_typeof(source_pages) = 'array'::text),
    constraint uq_curriculum_teaches_mapping_source_analysis_id_sequence_no
        unique (source_analysis_id, sequence_no),
    constraint uq_curriculum_teaches_mapping_version_id_teaches_id_page_sta
        unique (version_id, teaches_id, page_start, page_end, source_analysis_id),
    constraint ck_curriculum_teaches_mapping_page_end
        check (page_end >= page_start)
);

comment on table curriculum_teaches_mapping is '교안 버전의 특정 분석 실행에서 표준 개념이 어떤 표현·설명·페이지·섹션으로 등장했는지 보존하는 원장입니다. 프로젝트 검증 개념은 정확한 mapping_id를 참조하고 평가 문제는 해당 검증 개념을 통해 과거 교안 출처를 재현합니다. | 정의서명: CurriculumTeachesMapping | 제약·비고: • mapping_status: ACTIVE / REVIEW_REQUIRED / INACTIVE • teach_kind: CONCEPT / CODE_EXAMPLE / CAUTION • ACTIVE는 검토가 끝나 신규 프로젝트 검증 개념 후보로 사용할 수 있는 승인 매핑입니다. • REVIEW_REQUIRED는 자동 병합·프로젝트 후보 사용 전 검토가 필요한 매핑이며 INACTIVE는 신규 후보에서 제외하지만 과거 참조를 유지합니다. • sequence_no > 0, UNIQUE(source_analysis_id, sequence_no) • UNIQUE(version_id, teaches_id, page_start, page_end, source_analysis_id) • version_id는 source_analysis_id가 가리키는 CurriculumAnalysis.version_id와 일치해야 합니다. • 1 <= page_start <= page_end <= CurriculumVersion.page_count • section_id가 있으면 버전·분석 실행이 같고 매핑 페이지가 섹션 범위 안에 있어야 합니다. • ACTIVE 매핑은 유효한 section_id와 공백이 아닌 extracted_name이 필수입니다. • AI 응답 canonicalName은 extracted_name에 저장하고 신규 Teaches의 canonical_name 초기값으로만 사용합니다. • sour';

comment on column curriculum_teaches_mapping.mapping_id is '교안 버전의 개념 출현 매핑을 식별한다.';

comment on column curriculum_teaches_mapping.org_id is '매핑의 직접 테넌트 경계이다.';

comment on column curriculum_teaches_mapping.teaches_id is '기관 공통 표준 개념을 참조한다.';

comment on column curriculum_teaches_mapping.version_id is '개념이 출현한 교안 원본 버전이다.';

comment on column curriculum_teaches_mapping.section_id is '개념이 속한 유효 섹션이다.';

comment on column curriculum_teaches_mapping.source_analysis_id is '매핑을 생성한 정확한 교안 분석 실행이다.';

comment on column curriculum_teaches_mapping.extracted_name is '해당 교안에서 실제 추출된 개념 표현이다.';

comment on column curriculum_teaches_mapping.source_description is '해당 교안 문맥에서 추출된 개념 설명이다.';

comment on column curriculum_teaches_mapping.page_start is '교안 문맥 시작 페이지이다.';

comment on column curriculum_teaches_mapping.page_end is '교안 문맥 종료 페이지이다.';

comment on column curriculum_teaches_mapping.sequence_no is '동일 분석 실행의 매핑 표시 순서이다.';

comment on column curriculum_teaches_mapping.confidence is '개념 매핑 신뢰도이다.';

comment on column curriculum_teaches_mapping.teach_kind is '교안에서 추출된 개념 항목의 역할 종류를 저장한다.';

comment on column curriculum_teaches_mapping.source_evidence is '교안에서 해당 개념을 식별한 원문 근거·코드 식별자·설명 단서를 저장한다.';

comment on column curriculum_teaches_mapping.sibling_names is '같은 교안 구간에서 함께 추출된 대안·연관 개념명을 JSON 배열로 저장한다.';

comment on column curriculum_teaches_mapping.mapping_status is '교안별 개념 매핑 검토·활성 상태이다.';

comment on column curriculum_teaches_mapping.source_pages is '개념이 등장한 페이지 번호 목록을 원형 그대로 보존한다.';

alter table curriculum_teaches_mapping
    owner to postgres;

grant delete, insert, select, update on curriculum_teaches_mapping to teamiz_app;

create unique index uq_teaches_active_normalized_name
    on teaches (org_id, normalized_name)
    where ((status)::text = 'ACTIVE'::text);

grant delete, insert, select, update on teaches to teamiz_app;

grant delete, insert, select, update on organization to teamiz_app;

create table organization_policy
(
    policy_id                                uuid                     default gen_random_uuid()             not null
        constraint pk_organization_policy
            primary key,
    org_id                                   uuid                                                           not null
        constraint fk_organization_policy_org_id
            references organization
            on delete restrict,
    policy_version                           integer                                                        not null
        constraint ck_organization_policy_policy_version
            check (policy_version > 0),
    monthly_ai_budget                        numeric(18, 6)                                                 not null
        constraint ck_organization_policy_monthly_ai_budget
            check (monthly_ai_budget >= (0)::numeric),
    currency_code                            varchar(3)               default 'USD'::character varying      not null
        constraint ck_organization_policy_currency_code
            check ((currency_code)::text = 'USD'::text)
        constraint ck_organization_policy_currency_code_2
            check ((currency_code)::text = 'USD'::text),
    monthly_token_limit                      bigint
        constraint ck_organization_policy_monthly_token_limit
            check ((monthly_token_limit IS NULL) OR (monthly_token_limit >= 0)),
    storage_limit_bytes                      bigint
        constraint ck_organization_policy_storage_limit_bytes
            check ((storage_limit_bytes IS NULL) OR (storage_limit_bytes >= 0)),
    retention_days                           integer                                                        not null
        constraint ck_organization_policy_retention_days
            check (retention_days > 0),
    default_disclosure_scope                 varchar(100)                                                   not null
        constraint ck_organization_policy_default_disclosure_scope
            check ((default_disclosure_scope)::text = ANY
                   (ARRAY [('PRIVATE'::character varying)::text, ('SUMMARY'::character varying)::text, ('FULL'::character varying)::text])),
    code_session_tier_code                   varchar(30)              default 'BALANCED'::character varying not null
        constraint ck_organization_policy_code_session_tier_code
            check ((code_session_tier_code)::text = ANY
                   (ARRAY [('ACCURACY_FIRST'::character varying)::text, ('BALANCED'::character varying)::text, ('COST_FIRST'::character varying)::text])),
    allow_manager_invite                     boolean                  default true                          not null,
    allow_data_export                        boolean                  default true                          not null,
    allow_zip_submission                     boolean                  default true                          not null,
    allow_github_integration                 boolean                  default true                          not null,
    enable_big_project_contribution_analysis boolean                  default true                          not null,
    effective_from                           timestamp with time zone default CURRENT_TIMESTAMP             not null,
    effective_to                             timestamp with time zone,
    status                                   varchar(100)             default 'ACTIVE'::character varying   not null
        constraint ck_organization_policy_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('SUPERSEDED'::character varying)::text, ('EXPIRED'::character varying)::text])),
    created_by                               uuid                                                           not null
        constraint fk_organization_policy_created_by
            references app_user
            on delete restrict,
    created_at                               timestamp with time zone default CURRENT_TIMESTAMP             not null,
    updated_by                               uuid
        constraint fk_organization_policy_updated_by
            references app_user
            on delete restrict,
    updated_at                               timestamp with time zone default CURRENT_TIMESTAMP             not null,
    constraint uq_organization_policy_org_id_policy_version
        unique (org_id, policy_version),
    constraint ck_organization_policy_effective_to
        check ((effective_to IS NULL) OR (effective_to > effective_from))
);

comment on table organization_policy is '한 기관에 적용되는 운영 규칙 묶음입니다. 한 달 AI 예산, 토큰·저장 한도, 데이터 보존 기간, GitHub·ZIP 사용 허용 여부 등을 버전별로 남겨서 ‘그때 어떤 규칙이 적용됐는지’를 나중에도 확인할 수 있게 합니다. | 정의서명: OrganizationPolicy | 제약·비고: • UNIQUE(org_id, policy_version), 기관별 현재 ACTIVE 정책 최대 1건 • monthly_ai_budget >= 0, retention_days > 0 • currency_code는 NOT NULL·USD 고정 • 토큰·저장량 상한은 NULL이면 무제한, 값이 있으면 0 이상 • 공개 범위: PRIVATE / SUMMARY / FULL • 티어: ACCURACY_FIRST / BALANCED / COST_FIRST (CODE_SESSION 전용) • 상태: ACTIVE / SUPERSEDED / EXPIRED • effective_to는 NULL 또는 effective_from보다 이후 • 정책값 변경은 기존 행 덮어쓰기 대신 현재 버전 종료 후 신규 버전 생성 • 정책 수정은 현재 행을 덮어쓰지 않고 기존 ACTIVE 버전을 종료한 뒤 policy_version+1의 새 행을 생성합니다. • expectedPolicyVersion과 멱등성 키를 검증하고 기존 정책 종료·신규 정책 생성·감사를 한 트랜잭션으로 처리합니다. • 코드 제출 프로젝트가 존재할 때 allow_github_integration와 allow_zip_submission을 동시에 FALSE로 만드는 변경은 금지합니다. • monthly_ai_budget=0은 예산이 없는 상태이며 무제한을 뜻하지 않고, monthly_token_limit=0은 신규 세션 허용량이 0임을 뜻합니다.';

comment on column organization_policy.policy_id is '정책 버전 행을 식별한다.';

comment on column organization_policy.org_id is '정책 적용 기관이다.';

comment on column organization_policy.policy_version is '기관 내 증가하는 정책 버전이다.';

comment on column organization_policy.monthly_ai_budget is '월간 AI 비용 예산이다. `0`은 무제한·미설정이 아니라 예산 0을 의미한다. 오퍼레이터는 비용 화면에서 현재 유효 값만 읽을 수 있고 변경은 슈퍼어드민만 가능하다.';

comment on column organization_policy.currency_code is '플랫폼 공통 과금·예산 통화다. 기관별 변경을 허용하지 않는다.';

comment on column organization_policy.monthly_token_limit is '월간 토큰 상한이며 NULL은 무제한이다.';

comment on column organization_policy.storage_limit_bytes is '저장량 상한이며 NULL은 무제한이다.';

comment on column organization_policy.retention_days is '기관 데이터 보존 일수다.';

comment on column organization_policy.default_disclosure_scope is '신규 기수 결과 공개 기본값이다.';

comment on column organization_policy.code_session_tier_code is '질문 생성 기능에서 기관이 선택한 플랫폼 모델 티어다. 실제 모델 ID는 플랫폼 티어 정책에서 결정한다.';

comment on column organization_policy.allow_manager_invite is '신규 매니저 초대·재발송 허용 여부다.';

comment on column organization_policy.allow_data_export is '신규 데이터 export 생성 허용 여부다.';

comment on column organization_policy.allow_zip_submission is '신규 ZIP 코드 제출 허용 여부다.';

comment on column organization_policy.allow_github_integration is '신규 GitHub 조직 연결 허용 여부다. 실제 연결 상태는 `organization_github_integration`에서 관리한다.';

comment on column organization_policy.enable_big_project_contribution_analysis is '신규 빅프로젝트 기여도 분석 실행 허용 여부다.';

comment on column organization_policy.effective_from is '정책 효력 시작 시각이다.';

comment on column organization_policy.effective_to is '정책 효력 종료 시각이다.';

comment on column organization_policy.status is '정책 버전 상태다.';

comment on column organization_policy.created_by is '해당 정책 버전 생성자다.';

comment on column organization_policy.created_at is '정책 버전 생성 시각이다.';

comment on column organization_policy.updated_by is '활성 버전을 종료·대체한 사용자다.';

comment on column organization_policy.updated_at is '상태 또는 효력 종료 시각의 최근 변경 시각이다.';

alter table organization_policy
    owner to postgres;

create table cohort
(
    cohort_id            uuid                     default gen_random_uuid()            not null
        constraint pk_cohort
            primary key,
    org_id               uuid                                                          not null
        constraint fk_cohort_org_id
            references organization
            on delete restrict,
    name                 varchar(200)                                                  not null,
    start_date           date                                                          not null,
    end_date             date                                                          not null,
    status               varchar(30)              default 'PLANNED'::character varying not null
        constraint ck_cohort_status
            check ((status)::text = ANY
                   (ARRAY [('PLANNED'::character varying)::text, ('RUNNING'::character varying)::text, ('CLOSED'::character varying)::text])),
    created_by           uuid                                                          not null
        constraint fk_cohort_created_by
            references app_user
            on delete restrict,
    created_at           timestamp with time zone default CURRENT_TIMESTAMP            not null,
    updated_by           uuid
        constraint fk_cohort_updated_by
            references app_user
            on delete restrict,
    updated_at           timestamp with time zone default CURRENT_TIMESTAMP            not null,
    closed_at            timestamp with time zone,
    retention_until      timestamp with time zone,
    retention_policy_id  uuid
        constraint fk_cohort_retention_policy_id
            references organization_policy
            on delete restrict,
    disclosure_scope     varchar(30)                                                   not null,
    disclosure_policy_id uuid                                                          not null
        constraint fk_cohort_disclosure_policy_id
            references organization_policy
            on delete restrict,
    deleted_at           timestamp with time zone,
    constraint uq_cohort_org_id_name
        unique (org_id, name),
    constraint ck_cohort_closed_fields
        check ((((status)::text = 'CLOSED'::text) AND (closed_at IS NOT NULL) AND (retention_policy_id IS NOT NULL) AND
                (retention_until IS NOT NULL) AND (retention_until > closed_at)) OR
               (((status)::text <> 'CLOSED'::text) AND (closed_at IS NULL) AND (retention_policy_id IS NULL) AND
                (retention_until IS NULL))),
    constraint ck_cohort_end_date
        check (end_date >= start_date)
);

comment on table cohort is '기관이 운영하는 교육 기수의 기간·생명주기·공개 정책·종료 시 보존 정책을 관리하는 상위 원장입니다. 반, 명단, 프로젝트는 기수에 속하며 오퍼레이터의 접근 범위는 생성자가 아니라 기관 일치로 판정합니다. | 정의서명: Cohort | 제약·비고: • UNIQUE(org_id, name), start_date <= end_date • 상태: PLANNED / RUNNING / CLOSED • created_by는 감사·추적 정보이며 오퍼레이터 조회 권한은 AppUser.org_id와 Cohort.org_id 일치로 판정합니다. • 생성 시 현재 활성 OrganizationPolicy의 disclosure_policy_id와 disclosure_scope를 스냅샷으로 고정합니다. • status=''CLOSED''이면 closed_at·retention_policy_id·retention_until이 필수이고 retention_until > closed_at이어야 합니다. • PLANNED·RUNNING에서는 closed_at·retention_policy_id·retention_until이 NULL이어야 합니다. • CLOSED 전이는 활성 Class 종료, 기간형 배정 종료, 신규 쓰기 차단과 같은 트랜잭션으로 처리합니다. • CLOSED 기수에는 신규 반·명단·매니저 배정·프로젝트·회차·교안 연결을 허용하지 않습니다. • 일반적인 CLOSED → RUNNING 역전이는 금지하고 운영 복구는 별도 권한·사유·감사 계약으로 처리합니다.';

comment on column cohort.cohort_id is '기관 내부 교육 기수를 식별하는 기본키이다.';

comment on column cohort.org_id is '기수가 속한 기관을 식별한다.';

comment on column cohort.name is '기수의 화면 표시 및 업무 식별 명칭이다.';

comment on column cohort.start_date is '기수 교육 시작일이다.';

comment on column cohort.end_date is '기수 교육 종료일이다.';

comment on column cohort.status is '기수 생명주기 상태이다.';

comment on column cohort.created_by is '기수를 생성한 사용자를 참조한다.';

comment on column cohort.created_at is '기수가 생성된 시각이다.';

comment on column cohort.updated_by is '기수를 마지막으로 수정한 사용자를 선택적으로 참조한다.';

comment on column cohort.updated_at is '기수의 최근 수정 시각이다.';

comment on column cohort.closed_at is '기수가 CLOSED로 전이된 시각이다.';

comment on column cohort.retention_until is '기수 종료 시점 정책으로 고정한 보존 만료 시각이다.';

comment on column cohort.retention_policy_id is '기수 종료 시점의 기관 정책 버전을 참조한다.';

comment on column cohort.disclosure_scope is '기수 생성 시 복사한 결과 공개 범위 스냅샷이다.';

comment on column cohort.disclosure_policy_id is '기수 생성 시점의 기관 정책 버전을 참조한다.';

comment on column cohort.deleted_at is '기수가 논리 삭제된 시각이다.';

alter table cohort
    owner to postgres;

create table project
(
    project_id                    uuid                     default gen_random_uuid()            not null
        constraint pk_project
            primary key,
    org_id                        uuid                                                          not null
        constraint fk_project_org_id
            references organization
            on delete restrict,
    cohort_id                     uuid                                                          not null
        constraint fk_project_cohort_id
            references cohort
            on delete restrict,
    name                          varchar(200)                                                  not null,
    sequence_no                   integer                                                       not null,
    project_category              varchar(30)                                                   not null
        constraint ck_project_project_category
            check ((project_category)::text = ANY
                   (ARRAY [('MINI_PROJECT'::character varying)::text, ('BIG_PROJECT'::character varying)::text])),
    concept_source_mode           varchar(30)                                                   not null
        constraint ck_project_concept_source_mode
            check ((concept_source_mode)::text = ANY
                   (ARRAY [('PROJECT_FIXED'::character varying)::text, ('OWN_COMMIT_DYNAMIC'::character varying)::text])),
    curriculum_not_applicable     boolean                  default false                        not null,
    start_date                    date                                                          not null,
    end_date                      date                                                          not null,
    default_extraction_scope_code varchar(100)                                                  not null
        constraint ck_project_default_extraction_scope_code
            check ((default_extraction_scope_code)::text = ANY
                   (ARRAY [('TOTAL'::character varying)::text, ('OWN_COMMIT'::character varying)::text])),
    lifecycle_status              varchar(30)              default 'PLANNED'::character varying not null
        constraint ck_project_lifecycle_status
            check ((lifecycle_status)::text = ANY
                   (ARRAY [('PLANNED'::character varying)::text, ('RUNNING'::character varying)::text, ('CLOSED'::character varying)::text])),
    created_by                    uuid                                                          not null
        constraint fk_project_created_by
            references app_user
            on delete restrict,
    created_at                    timestamp with time zone default CURRENT_TIMESTAMP            not null,
    updated_by                    uuid
        constraint fk_project_updated_by
            references app_user
            on delete restrict,
    updated_at                    timestamp with time zone default CURRENT_TIMESTAMP            not null,
    deleted_at                    timestamp with time zone,
    constraint uq_project_cohort_id_name
        unique (cohort_id, name),
    constraint uq_project_cohort_id_sequence_no
        unique (cohort_id, sequence_no),
    constraint ck_project_end_date
        check (end_date >= start_date)
);

comment on table project is '기수 전체가 공통으로 수행하는 프로젝트의 운영 순서, 분류, 기간, 개념 원천 방식과 생명주기를 관리합니다. 미니프로젝트와 빅프로젝트의 교안·개념·회차 규칙을 결정하는 상위 원장입니다. | 정의서명: Project | 제약·비고: • UNIQUE(cohort_id, name), sequence_no > 0, UNIQUE(cohort_id, sequence_no) • project_category: MINI_PROJECT / BIG_PROJECT • concept_source_mode: PROJECT_FIXED / OWN_COMMIT_DYNAMIC • MINI_PROJECT는 concept_source_mode=''PROJECT_FIXED'', curriculum_not_applicable=FALSE입니다. • BIG_PROJECT는 concept_source_mode=''OWN_COMMIT_DYNAMIC'', curriculum_not_applicable=TRUE입니다. • start_date·end_date는 필수이고 Cohort 기간 안에서 start_date <= end_date를 만족해야 합니다. • sequence_no는 기수 내 전체 프로젝트 운영 순서이며 화면 analysis_sequence_no는 삭제되지 않은 MINI_PROJECT만 sequence_no·project_id 순으로 재번호화한 조회값입니다. • ProjectAssessmentRound.round_no와 analysis_sequence_no를 혼용하지 않습니다. • 생성 시 최초 ProjectAssessmentRound(round_no=1)를 같은 트랜잭션에서 생성합니다. • MINI_PROJECT는 활성 회차 정확히 1건, 교안 1건 이상, 활성 개념 세트 1건과 서로 다른 개념 정확히 3건을 요구합니다. • ';

comment on column project.project_id is '프로젝트를 식별하는 기본키이다.';

comment on column project.org_id is '프로젝트가 속한 기관이다.';

comment on column project.cohort_id is '프로젝트가 속한 기수를 참조한다.';

comment on column project.name is '프로젝트의 표시 및 업무 식별 명칭이다.';

comment on column project.sequence_no is '운영 순서 번호 값을 저장한다.';

comment on column project.project_category is '프로젝트 분류 코드이다.';

comment on column project.concept_source_mode is '검증 개념 선정 방식을 나타낸다.';

comment on column project.curriculum_not_applicable is '교안 연결이 적용되지 않는 프로젝트인지 나타낸다.';

comment on column project.start_date is '프로젝트 수행 시작일이다.';

comment on column project.end_date is '프로젝트 수행 종료일이다.';

comment on column project.default_extraction_scope_code is '코드 분석의 기본 추출 범위 코드이다.';

comment on column project.lifecycle_status is '프로젝트 생명주기 상태이다.';

comment on column project.created_by is '프로젝트 생성자를 참조한다.';

comment on column project.created_at is '프로젝트가 생성된 시각이다.';

comment on column project.updated_by is '프로젝트 마지막 수정자를 선택적으로 참조한다.';

comment on column project.updated_at is '프로젝트 최근 수정 시각이다.';

comment on column project.deleted_at is '프로젝트가 논리 삭제된 시각이다.';

alter table project
    owner to postgres;

grant delete, insert, select, update on project to teamiz_app;

create table class
(
    class_id         uuid                     default gen_random_uuid()           not null
        constraint pk_class
            primary key,
    org_id           uuid                                                         not null
        constraint fk_class_org_id
            references organization
            on delete restrict,
    cohort_id        uuid                                                         not null
        constraint fk_class_cohort_id
            references cohort
            on delete restrict,
    name             varchar(200)                                                 not null,
    capacity         integer                                                      not null
        constraint ck_class_capacity
            check (capacity > 0),
    lifecycle_status varchar(30)              default 'ACTIVE'::character varying not null
        constraint ck_class_lifecycle_status
            check ((lifecycle_status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('CLOSED'::character varying)::text])),
    created_by       uuid                                                         not null
        constraint fk_class_created_by
            references app_user
            on delete restrict,
    created_at       timestamp with time zone default CURRENT_TIMESTAMP           not null,
    updated_at       timestamp with time zone default CURRENT_TIMESTAMP           not null,
    closed_at        timestamp with time zone,
    deleted_at       timestamp with time zone,
    constraint uq_class_cohort_id_name
        unique (cohort_id, name),
    constraint ck_class_closed_at_required
        check ((((lifecycle_status)::text = 'CLOSED'::text) AND (closed_at IS NOT NULL)) OR
               (((lifecycle_status)::text = 'ACTIVE'::text) AND (closed_at IS NULL)))
);

comment on table class is '기수 안의 교육 운영 반을 관리합니다. 반의 정원과 생명주기를 소유하지만 담당 매니저와 교육생은 각각 기간형 ManagerAssignment와 ClassMembership에서 관리합니다. | 정의서명: Class | 제약·비고: • UNIQUE(cohort_id, name), capacity > 0 • 생명주기 상태: ACTIVE / CLOSED • 반 생성 요청은 name·capacity만 받고 managerIds를 받지 않습니다. • ACTIVE이면 closed_at IS NULL, CLOSED이면 closed_at이 필수입니다. • 정원 축소는 현재 활성 ClassMembership 수보다 작게 만들 수 없으며 관련 행을 잠금 조회해 검증합니다. • CLOSED 반에는 신규 교육생·매니저 배정을 허용하지 않습니다. • Cohort가 CLOSED로 전이되면 소속 ACTIVE 반도 같은 트랜잭션에서 CLOSED로 전이합니다. • 담당 매니저 목록·공동 관리·미배정 경고는 ManagerAssignment에서 파생합니다.';

comment on column class.class_id is '기수 내 반을 식별하는 기본키이다.';

comment on column class.org_id is '반이 속한 기관을 식별한다.';

comment on column class.cohort_id is '반이 속한 기수를 참조한다.';

comment on column class.name is '반의 화면 표시 및 업무 식별 명칭이다.';

comment on column class.capacity is '반에 배정할 수 있는 최대 교육생 수이다.';

comment on column class.lifecycle_status is '반의 생명주기 상태이다.';

comment on column class.created_by is '반을 생성한 사용자를 참조한다.';

comment on column class.created_at is '반이 생성된 시각이다.';

comment on column class.updated_at is '반의 최근 수정 시각이다.';

comment on column class.closed_at is '반이 CLOSED로 전이된 시각이다.';

comment on column class.deleted_at is '반이 논리 삭제된 시각이다.';

alter table class
    owner to postgres;

grant delete, insert, select, update on class to teamiz_app;

grant delete, insert, select, update on cohort to teamiz_app;

create table cohort_member
(
    cohort_member_id uuid                     default gen_random_uuid()           not null
        constraint pk_cohort_member
            primary key,
    org_id           uuid                                                         not null
        constraint fk_cohort_member_org_id
            references organization
            on delete restrict,
    cohort_id        uuid                                                         not null
        constraint fk_cohort_member_cohort_id
            references cohort
            on delete restrict,
    user_id          uuid                                                         not null
        constraint fk_cohort_member_user_id
            references app_user
            on delete restrict,
    joined_at        timestamp with time zone                                     not null,
    left_at          timestamp with time zone,
    status           varchar(30)              default 'ACTIVE'::character varying not null
        constraint ck_cohort_member_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('LEFT'::character varying)::text])),
    created_at       timestamp with time zone default CURRENT_TIMESTAMP           not null,
    constraint uq_cohort_member_cohort_id_user_id
        unique (cohort_id, user_id)
);

comment on table cohort_member is '한 교육생 계정이 특정 기수에 참여한 기간을 보존하는 명단 원장입니다. 계정 활성 상태와 기수 소속 상태를 분리하고 반 배정·초대 재발송·명단 업무의 안정 식별자로 cohort_member_id를 사용합니다. | 정의서명: CohortMember | 제약·비고: • UNIQUE(cohort_id, user_id) • 상태: ACTIVE / LEFT • ACTIVE이면 left_at IS NULL, LEFT이면 left_at이 필수이고 left_at > joined_at이어야 합니다. • user_id는 같은 기관의 Role.code=''TRAINEE'' 사용자여야 합니다. • AppUser.status와 CohortMember.status는 서로 다른 생명주기입니다. • 현재 명단·반 배정·초대 재발송 요청 식별자는 user_id가 아니라 cohort_member_id로 통일합니다. • 같은 기관에 이미 존재하는 활성 교육생 계정을 연결할 때 새 AppUser나 중복 초대를 만들지 않습니다. • 명단 등록 실행·행별 결과 테이블은 만들지 않고 성공 행만 원장에 반영하며 실패·제외 사유는 API 응답으로 반환합니다.';

comment on column cohort_member.cohort_member_id is '기수 교육생 소속을 식별하는 기본키이다.';

comment on column cohort_member.org_id is '기수 교육생이 속한 기관이다.';

comment on column cohort_member.cohort_id is '교육생이 참여하는 기수이다.';

comment on column cohort_member.user_id is '기수에 참여하는 교육생 계정이다.';

comment on column cohort_member.joined_at is '기수 소속이 시작된 시각이다.';

comment on column cohort_member.left_at is '기수 소속이 종료된 시각이다.';

comment on column cohort_member.status is '기수 소속 상태이다.';

comment on column cohort_member.created_at is '기수 교육생 원장이 생성된 시각이다.';

alter table cohort_member
    owner to postgres;

create table class_membership
(
    class_membership_id uuid                     default gen_random_uuid() not null
        constraint pk_class_membership
            primary key,
    class_id            uuid                                               not null
        constraint fk_class_membership_class_id
            references class
            on delete restrict,
    cohort_member_id    uuid                                               not null
        constraint fk_class_membership_cohort_member_id
            references cohort_member
            on delete restrict,
    org_id              uuid                                               not null
        constraint fk_class_membership_org_id
            references organization
            on delete restrict,
    assigned_by         uuid                                               not null
        constraint fk_class_membership_assigned_by
            references app_user
            on delete restrict,
    assigned_at         timestamp with time zone                           not null,
    unassigned_by       uuid
        constraint fk_class_membership_unassigned_by
            references app_user
            on delete restrict,
    unassigned_at       timestamp with time zone,
    unassigned_reason   varchar(50)
        constraint ck_class_membership_unassigned_reason
            check ((unassigned_reason)::text = ANY
                   (ARRAY [('IMMEDIATE_ROLLBACK'::character varying)::text, ('MANUAL_MOVE'::character varying)::text, ('COHORT_CLOSED'::character varying)::text, ('MEMBER_LEFT'::character varying)::text, ('ADMIN_CORRECTION'::character varying)::text])),
    created_at          timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint ck_class_membership_unassigned_pair
        check (((unassigned_at IS NULL) AND (unassigned_by IS NULL) AND (unassigned_reason IS NULL)) OR
               ((unassigned_at IS NOT NULL) AND (unassigned_by IS NOT NULL) AND (unassigned_reason IS NOT NULL) AND
                (assigned_at < unassigned_at)))
);

comment on table class_membership is '기수 구성원이 어느 반에 언제 배정되었는지 보존하는 기간형 이력입니다. 반 이동·즉시 되돌리기·기수 종료 시 기존 행을 삭제하지 않고 종료 시각과 사유를 기록합니다. | 정의서명: ClassMembership | 제약·비고: • unassigned_at IS NULL인 행을 현재 유효 반 배정으로 봅니다. • 같은 기수 구성원의 동시 유효 반 배정은 최대 1건입니다. • 유효 기간은 assigned_at <= as_of_at < COALESCE(unassigned_at, infinity)로 판정합니다. • unassigned_at이 NULL이면 unassigned_by·unassigned_reason도 NULL이어야 합니다. • unassigned_at이 있으면 unassigned_by·unassigned_reason이 필수이고 assigned_at < unassigned_at이어야 합니다. • Class.cohort_id와 CohortMember.cohort_id, 모든 org_id 경로가 일치해야 합니다. • 배정 직전 CohortMember=ACTIVE, Class=ACTIVE, 정원, 기관·기수 경로를 잠금 재검증합니다. • 해제 사유: IMMEDIATE_ROLLBACK / MANUAL_MOVE / COHORT_CLOSED / MEMBER_LEFT / ADMIN_CORRECTION • 벌크 요청은 성공 행만 생성하고 행별 실패·제외 결과를 별도 실행 테이블 없이 API 응답으로 반환합니다.';

comment on column class_membership.class_membership_id is '반 배정 이력을 식별하는 기본키이다.';

comment on column class_membership.class_id is '배정 대상 반을 참조한다.';

comment on column class_membership.cohort_member_id is '배정 대상 기수 구성원을 참조한다.';

comment on column class_membership.org_id is '반 배정 이력이 속한 기관이다.';

comment on column class_membership.assigned_by is '반 배정을 수행한 사용자를 참조한다.';

comment on column class_membership.assigned_at is '반 배정이 시작된 시각이다.';

comment on column class_membership.unassigned_by is '반 배정을 해제한 사용자를 선택적으로 참조한다.';

comment on column class_membership.unassigned_at is '반 배정이 종료된 시각이다.';

comment on column class_membership.unassigned_reason is '반 배정 종료 사유 코드이다.';

comment on column class_membership.created_at is '반 배정 이력이 생성된 시각이다.';

alter table class_membership
    owner to postgres;

create table project_membership
(
    project_membership_id uuid                     default gen_random_uuid()           not null
        constraint pk_project_membership
            primary key,
    project_id            uuid                                                         not null
        constraint fk_project_membership_project_id
            references project
            on delete restrict,
    user_id               uuid                                                         not null
        constraint fk_project_membership_user_id
            references app_user
            on delete restrict,
    org_id                uuid                                                         not null
        constraint fk_project_membership_org_id
            references organization
            on delete restrict,
    class_id              uuid                                                         not null
        constraint fk_project_membership_class_id
            references class
            on delete restrict,
    class_membership_id   uuid
        constraint fk_project_membership_class_membership_id
            references class_membership
            on delete restrict,
    joined_at             timestamp with time zone                                     not null,
    left_at               timestamp with time zone,
    status                varchar(30)              default 'ACTIVE'::character varying not null
        constraint ck_project_membership_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('LEFT'::character varying)::text])),
    change_reason         text,
    changed_by            uuid                                                         not null
        constraint fk_project_membership_changed_by
            references app_user
            on delete restrict,
    created_at            timestamp with time zone default CURRENT_TIMESTAMP           not null
);

comment on table project_membership is '교육생이 특정 프로젝트의 편성 대상이 된 기간과 참여 시점의 반을 고정하는 원장입니다. 이후 현재 반이 변경되어도 과거 프로젝트의 팀·제출·결과 귀속을 재분류하지 않습니다. | 정의서명: ProjectMembership | 제약·비고: • 상태: ACTIVE / LEFT • ACTIVE이면 left_at IS NULL, LEFT이면 left_at이 필수이고 joined_at < left_at이어야 합니다. • 같은 project_id + user_id의 활성 참여는 최대 1건입니다. • class_id는 참여 시점 반 스냅샷이며 현재 ClassMembership 변경으로 갱신하지 않습니다. • class_membership_id가 있으면 해당 소속의 class_id·cohort_member/user·유효 시각이 본 행과 일치해야 합니다. • Project.cohort_id와 Class.cohort_id, 사용자 기수 소속, 모든 org_id 경로가 일치해야 합니다. • TeamMembership은 user_id가 아니라 project_membership_id를 참조합니다.';

comment on column project_membership.project_membership_id is '프로젝트 참여자를 식별하는 기본키이다.';

comment on column project_membership.project_id is '참여 대상 프로젝트이다.';

comment on column project_membership.user_id is '프로젝트 참여 교육생 계정이다.';

comment on column project_membership.org_id is '프로젝트 참여의 기관 경계이다.';

comment on column project_membership.class_id is '프로젝트 참여 시점의 반을 고정 참조한다.';

comment on column project_membership.class_membership_id is '프로젝트 참여 시점의 유효 반 소속을 선택적으로 참조한다.';

comment on column project_membership.joined_at is '프로젝트 참여가 시작된 시각이다.';

comment on column project_membership.left_at is '프로젝트 참여가 종료된 시각이다.';

comment on column project_membership.status is '프로젝트 참여 상태이다.';

comment on column project_membership.change_reason is '프로젝트 참여 변경 사유이다.';

comment on column project_membership.changed_by is '프로젝트 참여 변경 처리자를 참조한다.';

comment on column project_membership.created_at is '프로젝트 참여 원장이 생성된 시각이다.';

alter table project_membership
    owner to postgres;

create unique index uq_project_membership_active
    on project_membership (project_id, user_id)
    where ((status)::text = 'ACTIVE'::text);

grant delete, insert, select, update on project_membership to teamiz_app;

create unique index uq_class_membership_active_member
    on class_membership (cohort_member_id)
    where (unassigned_at IS NULL);

grant delete, insert, select, update on class_membership to teamiz_app;

grant delete, insert, select, update on cohort_member to teamiz_app;

create table project_curriculum
(
    project_curriculum_id uuid                     default gen_random_uuid() not null
        constraint pk_project_curriculum
            primary key,
    org_id                uuid                                               not null
        constraint fk_project_curriculum_org_id
            references organization
            on delete restrict,
    project_id            uuid                                               not null
        constraint fk_project_curriculum_project_id
            references project
            on delete restrict,
    curriculum_version_id uuid                                               not null
        constraint fk_project_curriculum_curriculum_version_id
            references curriculum_version
            on delete restrict,
    sequence_no           integer                  default 1                 not null
        constraint ck_project_curriculum_sequence_no
            check (sequence_no > 0),
    linked_at             timestamp with time zone default CURRENT_TIMESTAMP not null,
    linked_by             uuid                                               not null
        constraint fk_project_curriculum_linked_by
            references app_user
            on delete restrict,
    constraint uq_project_curriculum_project_id_curriculum_version_id
        unique (project_id, curriculum_version_id),
    constraint uq_project_curriculum_project_id_sequence_no
        unique (project_id, sequence_no)
);

comment on table project_curriculum is '미니프로젝트가 사용하는 정확한 교안 버전을 연결하는 교차 원장입니다. 같은 논리 교안의 최신 버전으로 자동 치환하지 않고 연결 당시 version_id를 불변으로 보존합니다. | 정의서명: ProjectCurriculum | 제약·비고: • sequence_no > 0, UNIQUE(project_id, sequence_no), UNIQUE(project_id, curriculum_version_id) • MINI_PROJECT에만 생성할 수 있고 BIG_PROJECT에는 생성하지 않습니다. • Project·CurriculumVersion·Organization 경로가 일치해야 합니다. • 연결 대상 버전은 유효한 성공 분석과 승인된 개념 매핑을 가져야 합니다. • 연결된 curriculum_version_id는 프로젝트 실행 데이터가 생성된 뒤 자동 변경하지 않습니다. • 교안 변경은 기존 성공 실행에 소급하지 않고 신규 실행 또는 허용된 구성 버전부터 적용합니다.';

comment on column project_curriculum.project_curriculum_id is '프로젝트 교안 연결을 식별하는 기본키이다.';

comment on column project_curriculum.org_id is '프로젝트 교안 연결의 기관 경계이다.';

comment on column project_curriculum.project_id is '교안이 연결된 프로젝트이다.';

comment on column project_curriculum.curriculum_version_id is '연결된 교안 버전을 참조한다.';

comment on column project_curriculum.sequence_no is '프로젝트 내 교안 표시 순서이다.';

comment on column project_curriculum.linked_at is '교안 버전이 프로젝트에 연결된 시각이다.';

comment on column project_curriculum.linked_by is '교안 연결을 수행한 사용자를 참조한다.';

alter table project_curriculum
    owner to postgres;

grant delete, insert, select, update on project_curriculum to teamiz_app;

create table report
(
    report_id                uuid         default gen_random_uuid()             not null
        constraint pk_report
            primary key,
    org_id                   uuid                                               not null
        constraint fk_report_org_id
            references organization
            on delete restrict,
    cohort_id                uuid                                               not null
        constraint fk_report_cohort_id
            references cohort
            on delete restrict,
    class_id                 uuid
        constraint fk_report_class_id
            references class
            on delete restrict,
    user_id                  uuid
        constraint fk_report_user_id
            references app_user
            on delete restrict,
    assessment_round_id      uuid,
    report_type              varchar(100)                                       not null
        constraint ck_report_report_type
            check ((report_type)::text = ANY
                   (ARRAY [('COHORT_SUMMARY'::character varying)::text, ('CHECKPOINT'::character varying)::text, ('TRAINEE_FINAL'::character varying)::text, ('COHORT_CURRICULUM_DIAGNOSIS'::character varying)::text, ('COHORT_OUTCOME'::character varying)::text])),
    lifecycle_status         varchar(30)                                        not null
        constraint ck_report_lifecycle_status
            check ((lifecycle_status)::text = ANY
                   (ARRAY [('DRAFT'::character varying)::text, ('ACTIVE'::character varying)::text, ('SUPERSEDED'::character varying)::text])),
    scheduled_publish_at     timestamp with time zone,
    published_at             timestamp with time zone,
    trainee_release_status   varchar(100) default 'RELEASED'::character varying not null
        constraint ck_report_trainee_release_status
            check ((trainee_release_status)::text = ANY
                   (ARRAY [('NOT_CONFIGURED'::character varying)::text, ('WITHHELD'::character varying)::text, ('RELEASED'::character varying)::text])),
    trainee_disclosure_scope varchar(100) default 'FULL'::character varying
        constraint ck_report_trainee_disclosure_scope
            check ((trainee_disclosure_scope)::text = ANY
                   (ARRAY [('PRIVATE'::character varying)::text, ('SUMMARY'::character varying)::text, ('FULL'::character varying)::text])),
    trainee_released_at      timestamp with time zone,
    trainee_released_by      uuid
        constraint fk_report_trainee_released_by
            references app_user
            on delete restrict,
    constraint ck_report_assessment_round_id
        check ((((report_type)::text = ANY
                 (ARRAY [('COHORT_CURRICULUM_DIAGNOSIS'::character varying)::text, ('COHORT_OUTCOME'::character varying)::text])) AND
                (class_id IS NULL) AND (user_id IS NULL) AND (assessment_round_id IS NULL)) OR
               ((user_id IS NOT NULL) AND (assessment_round_id IS NOT NULL) AND (class_id IS NULL)) OR
               ((class_id IS NOT NULL) AND (assessment_round_id IS NOT NULL) AND (user_id IS NULL))),
    constraint ck_report_published_at
        check ((scheduled_publish_at IS NULL) OR (published_at IS NULL) OR (published_at >= scheduled_publish_at))
);

comment on table report is '기수·반·프로젝트 회차·교육생 리포트의 논리 원장과 발행·교육생 공개 상태를 관리합니다. 생성 실행과 불변 결과는 각각 ReportGenerationRun·ReportSnapshot이 소유합니다. | 정의서명: Report | 제약·비고: • report_type: COHORT_SUMMARY / CHECKPOINT / TRAINEE_FINAL / COHORT_CURRICULUM_DIAGNOSIS / COHORT_OUTCOME • lifecycle_status: DRAFT / ACTIVE / SUPERSEDED • cohort_id는 항상 필수이며 모든 리포트는 단일 기관·기수 범위입니다. • 교육생 개인 리포트는 user_id·assessment_round_id가 필수이고 반 리포트는 class_id·assessment_round_id가 필수입니다. • 기수 리포트 COHORT_CURRICULUM_DIAGNOSIS·COHORT_OUTCOME은 class_id·user_id·assessment_round_id가 NULL입니다. • 같은 cohort_id + report_type의 ACTIVE 리포트는 최대 1건입니다. • scheduled_publish_at이 있으면 published_at은 NULL이거나 예정 시각 이상이어야 합니다. • 원천 회차가 모두 완료·발행되고 활성 ReportSnapshot이 존재하기 전에는 published_at을 설정하지 않습니다. • trainee_release_status: NOT_CONFIGURED / WITHHELD / RELEASED • NOT_CONFIGURED이면 공개 범위·시각·처리자가 NULL입니다. • WITHHELD이면 trainee_disclosure_scope=PRIVATE이고 공개 시각은 NULL입니다. • RELEASED이면 t';

comment on column report.report_id is '기수·회차·개인 리포트 논리 원장의 개별 레코드를 식별하는 고유 키이다.';

comment on column report.org_id is '기수·회차·개인 리포트 논리 원장의 소속 기관을 식별하며 테넌트 격리와 RLS 필터에 사용한다.';

comment on column report.cohort_id is '기수와의 업무 관계를 연결하는 외래 키이다.';

comment on column report.class_id is '리포트가 참조하는 class.class_id의 식별자이다.';

comment on column report.user_id is '사용자와의 업무 관계를 연결하는 외래 키이다.';

comment on column report.assessment_round_id is '리포트가 참조하는 평가 회차 ID 외래키 후보이다.';

comment on column report.report_type is '기수·회차·개인 리포트 논리 원장에서 관리하는 리포트유형 정보이다.';

comment on column report.lifecycle_status is '생명주기 상태 값을 저장한다.';

comment on column report.scheduled_publish_at is '발행 예정 일시 값을 저장한다.';

comment on column report.published_at is '실제 발행 일시 값을 저장한다.';

comment on column report.trainee_release_status is '교육생 공개 상태 값을 저장한다.';

comment on column report.trainee_disclosure_scope is '교육생 공개 범위 값을 저장한다.';

comment on column report.trainee_released_at is '교육생 공개 일시 값을 저장한다.';

comment on column report.trainee_released_by is '교육생 공개 처리자 ID 값을 저장한다.';

alter table report
    owner to postgres;

create unique index uq_report_active_class
    on report (assessment_round_id, class_id, report_type)
    where (((lifecycle_status)::text = 'ACTIVE'::text) AND (class_id IS NOT NULL) AND (user_id IS NULL));

create unique index uq_report_active_cohort
    on report (cohort_id, report_type)
    where (((lifecycle_status)::text = 'ACTIVE'::text) AND (class_id IS NULL) AND (user_id IS NULL) AND
           (assessment_round_id IS NULL));

create unique index uq_report_active_user
    on report (assessment_round_id, user_id, report_type)
    where (((lifecycle_status)::text = 'ACTIVE'::text) AND (user_id IS NOT NULL) AND (class_id IS NULL));

grant delete, insert, select, update on report to teamiz_app;

create table project_verification_concept_set
(
    concept_set_id uuid                     default gen_random_uuid()           not null
        constraint pk_project_verification_concept_set
            primary key,
    project_id     uuid                                                         not null
        constraint fk_project_verification_concept_set_project_id
            references project
            on delete restrict,
    org_id         uuid                                                         not null
        constraint fk_project_verification_concept_set_org_id
            references organization
            on delete restrict,
    version_no     integer                                                      not null
        constraint ck_project_verification_concept_set_version_no
            check (version_no > 0),
    status         varchar(30)              default 'ACTIVE'::character varying not null
        constraint ck_project_verification_concept_set_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('SUPERSEDED'::character varying)::text])),
    effective_from timestamp with time zone default CURRENT_TIMESTAMP           not null,
    effective_to   timestamp with time zone,
    created_by     uuid                                                         not null
        constraint fk_project_verification_concept_set_created_by
            references app_user
            on delete restrict,
    change_reason  text,
    created_at     timestamp with time zone default CURRENT_TIMESTAMP           not null,
    constraint uq_project_verification_concept_set_project_id_version_no
        unique (project_id, version_no),
    constraint ck_proj_verif_concept_set_effective_to
        check ((effective_to IS NULL) OR (effective_to > effective_from))
);

comment on table project_verification_concept_set is '미니프로젝트에 적용할 검증 개념 정확히 3건을 버전 단위로 묶는 원장입니다. 개념 교체 시 기존 세트를 덮어쓰지 않고 새 세트를 검증한 뒤 활성 세트를 원자 교체합니다. | 정의서명: ProjectVerificationConceptSet | 제약·비고: • version_no > 0, UNIQUE(project_id, version_no) • 상태: ACTIVE / SUPERSEDED • 프로젝트별 status=''ACTIVE'' AND effective_to IS NULL인 세트는 최대 1건입니다. • ACTIVE이면 effective_to IS NULL, SUPERSEDED이면 effective_to가 필수이고 effective_from < effective_to여야 합니다. • MINI_PROJECT에만 생성하고 BIG_PROJECT에는 생성하지 않습니다. • 새 세트와 서로 다른 ProjectVerificationConcept 3건을 먼저 생성·검증한 뒤 새 세트 활성화와 기존 세트 종료를 한 트랜잭션으로 처리합니다. • 교체 실패 시 기존 ACTIVE 세트와 기존 실행 결과를 유지합니다. • 회차가 고정 참조한 concept_set_id는 이후 현재 활성 세트로 바꾸지 않습니다.';

comment on column project_verification_concept_set.concept_set_id is '프로젝트 검증 개념 세트를 식별한다.';

comment on column project_verification_concept_set.project_id is '개념 세트가 속한 프로젝트이다.';

comment on column project_verification_concept_set.org_id is '개념 세트의 기관 경계이다.';

comment on column project_verification_concept_set.version_no is '프로젝트 내 개념 세트 버전 번호이다.';

comment on column project_verification_concept_set.status is '개념 세트 상태이다.';

comment on column project_verification_concept_set.effective_from is '개념 세트 적용 시작 시각이다.';

comment on column project_verification_concept_set.effective_to is '개념 세트 적용 종료 시각이다.';

comment on column project_verification_concept_set.created_by is '개념 세트 생성자를 참조한다.';

comment on column project_verification_concept_set.change_reason is '개념 세트 교체 사유이다.';

comment on column project_verification_concept_set.created_at is '개념 세트 생성 시각이다.';

alter table project_verification_concept_set
    owner to postgres;

create table project_assessment_round
(
    assessment_round_id          uuid                     default gen_random_uuid() not null
        constraint pk_project_assessment_round
            primary key,
    project_id                   uuid                                               not null
        constraint fk_project_assessment_round_project_id
            references project
            on delete restrict,
    org_id                       uuid                                               not null
        constraint fk_project_assessment_round_org_id
            references organization
            on delete restrict,
    cohort_id                    uuid                                               not null
        constraint fk_project_assessment_round_cohort_id
            references cohort
            on delete restrict,
    concept_set_id               uuid
        constraint fk_project_assessment_round_concept_set_id
            references project_verification_concept_set
            on delete restrict,
    round_no                     integer                                            not null
        constraint ck_project_assessment_round_round_no
            check (round_no > 0),
    round_name                   varchar(200)                                       not null,
    analysis_role_code           varchar(100)
        constraint ck_project_assessment_round_analysis_role_code
            check ((analysis_role_code)::text = ANY
                   (ARRAY [('BASELINE'::character varying)::text, ('INTERVENTION_CHECK'::character varying)::text, ('GROWTH_CONFIRMATION'::character varying)::text, ('AD_HOC'::character varying)::text])),
    trigger_type                 varchar(100)                                       not null,
    scheduled_at                 timestamp with time zone,
    submission_due_at            timestamp with time zone                           not null,
    assessment_open_at           timestamp with time zone,
    assessment_due_at            timestamp with time zone,
    report_publish_mode          varchar(100)                                       not null
        constraint ck_project_assessment_round_report_publish_mode
            check ((report_publish_mode)::text = 'ROUND_BATCH'::text),
    report_publish_not_before_at timestamp with time zone,
    is_final                     boolean                                            not null,
    status                       varchar(100)                                       not null
        constraint ck_project_assessment_round_status
            check ((status)::text = ANY
                   (ARRAY [('PLANNED'::character varying)::text, ('OPEN'::character varying)::text, ('CLOSED'::character varying)::text, ('COMPLETED'::character varying)::text])),
    created_by                   uuid                                               not null
        constraint fk_project_assessment_round_created_by
            references app_user
            on delete restrict,
    updated_by                   uuid                                               not null
        constraint fk_project_assessment_round_updated_by
            references app_user
            on delete restrict,
    created_at                   timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at                   timestamp with time zone default CURRENT_TIMESTAMP not null,
    deleted_at                   timestamp with time zone,
    constraint ck_project_assessment_round_assessment_open_at
        check ((assessment_open_at IS NULL) OR (assessment_open_at >= submission_due_at)),
    constraint ck_project_assessment_round_assessment_window
        check ((assessment_due_at IS NULL) OR (assessment_open_at IS NULL) OR (assessment_due_at > assessment_open_at)),
    constraint ck_project_assessment_round_report_publish_not_before_at
        check ((report_publish_not_before_at IS NULL) OR ((report_publish_not_before_at >= submission_due_at) AND
                                                          ((assessment_due_at IS NULL) OR
                                                           (report_publish_not_before_at >= assessment_due_at))))
);

comment on table project_assessment_round is '프로젝트 내부의 코드 제출 마감과 분석 실행 단위를 식별하는 공통 회차 원장입니다. 미니프로젝트는 활성 회차를 정확히 1건만 가지며, 빅프로젝트는 분석 역할이 다른 회차를 1건 이상 운영합니다. 회차 공통 응시 창(assessment_open_at·assessment_due_at)은 본 테이블이 소유하고, 교육생별 확정 창과 매니저 예외 연장은 MeasurementAttempt가 소유합니다. | 정의서명: ProjectAssessmentRound | 제약·비고: • 상태: PLANNED / OPEN / CLOSED / COMPLETED • CLOSED는 회차 공통 응시 마감을 뜻하며 개인 연장 건은 예외로 남을 수 있습니다. • assessment_open_at은 submission_due_at 이후여야 하고 assessment_due_at은 그보다 뒤여야 합니다. • PLANNED가 아닌 상태에서는 두 값이 모두 확정되어 있어야 합니다. • report_publish_not_before_at은 assessment_due_at 이후여야 합니다. • 삭제되지 않은 범위에서 프로젝트별 회차 번호·회차명을 유일하게 유지합니다. • 프로젝트별 삭제되지 않은 최종 회차는 최대 1건입니다. • MINI_PROJECT는 활성 회차 정확히 1건, round_no=1, is_final=TRUE를 요구합니다. • MINI_PROJECT는 OPEN 전이 또는 AnalysisJob 생성 전 concept_set_id를 확정하고 분석 시작 이후 변경하지 않습니다. • BIG_PROJECT는 concept_set_id=NULL, analysis_role_code NOT NULL이며 기본 분석 역할 3개는 프로젝트별 최대 1건입니다. • 분류별 회차 수·경로·기간·개념 세트 조건은 지연 제약 트리거 또는 잠금 기반 서비스 검증으로 강제합니다. • 계획 문제 슬롯 3건의 생성 상태가 모두 확정되고 GENERATED 문제마다 L1~L4 질문 4건과 힌트가 준비된 뒤 세션을 개방합니다. NOT_GENERATED 문제에는 단계 데이터를 요구하지 않습니다.';

comment on column project_assessment_round.assessment_round_id is '프로젝트 회차 레코드를 식별하는 기본키이다.';

comment on column project_assessment_round.project_id is '회차가 속한 프로젝트 ID이다.';

comment on column project_assessment_round.org_id is '회차가 속한 기관 ID이다.';

comment on column project_assessment_round.cohort_id is '회차가 속한 기수 ID이다.';

comment on column project_assessment_round.concept_set_id is '미니프로젝트 회차에 고정 적용할 검증 개념 세트 ID이다.';

comment on column project_assessment_round.round_no is '프로젝트 내 회차 순번이다.';

comment on column project_assessment_round.round_name is '프로젝트 내 회차 표시명이다.';

comment on column project_assessment_round.analysis_role_code is '빅프로젝트 회차의 분석 역할 코드이다.';

comment on column project_assessment_round.trigger_type is '회차 생성 계기를 식별하는 개방형 코드이다.';

comment on column project_assessment_round.scheduled_at is '선택적인 운영 일정 메타데이터이다.';

comment on column project_assessment_round.submission_due_at is '팀 코드 제출 마감 일시이다.';

comment on column project_assessment_round.assessment_open_at is '회차 공통 이해도 확인(응시) 시작 시각이다. 교육생별 MeasurementAttempt.assessment_open_at은 MAX(analysis_completed_at, 본 컬럼)으로 확정한다. 제출 마감 이후여야 한다.';

comment on column project_assessment_round.assessment_due_at is '회차 공통 이해도 확인(응시) 마감 시각이다. 교육생별 MeasurementAttempt.assessment_close_at의 기본값이며 매니저 개인 연장 시에만 달라진다. 예정 회차 안내와 리포트 발행 하한의 기준이다.';

comment on column project_assessment_round.report_publish_mode is '회차 리포트 발행 방식이다.';

comment on column project_assessment_round.report_publish_not_before_at is '리포트를 발행할 수 있는 최소 시각이다.';

comment on column project_assessment_round.is_final is '프로젝트의 최종 회차 여부이다.';

comment on column project_assessment_round.status is '프로젝트 회차의 생명주기 상태이다.';

comment on column project_assessment_round.created_by is '회차 생성 처리자 ID이다.';

comment on column project_assessment_round.updated_by is '회차 최종 수정 처리자 ID이다.';

comment on column project_assessment_round.created_at is '회차 생성 일시이다.';

comment on column project_assessment_round.updated_at is '회차 최종 수정 일시이다.';

comment on column project_assessment_round.deleted_at is '논리 삭제 처리 일시이며 NULL이면 활성 범위이다.';

alter table project_assessment_round
    owner to postgres;

create unique index uq_project_assessment_round_analysis_role_active
    on project_assessment_round (project_id, analysis_role_code)
    where ((deleted_at IS NULL) AND ((analysis_role_code)::text = ANY
                                     (ARRAY [('BASELINE'::character varying)::text, ('INTERVENTION_CHECK'::character varying)::text, ('GROWTH_CONFIRMATION'::character varying)::text])));

create unique index uq_project_assessment_round_final_active
    on project_assessment_round (project_id)
    where ((deleted_at IS NULL) AND (is_final = true));

create unique index uq_project_assessment_round_name_active
    on project_assessment_round (project_id, round_name)
    where (deleted_at IS NULL);

create unique index uq_project_assessment_round_no_active
    on project_assessment_round (project_id, round_no)
    where (deleted_at IS NULL);

grant delete, insert, select, update on project_assessment_round to teamiz_app;

create table project_verification_concept
(
    project_concept_id uuid default gen_random_uuid() not null
        constraint pk_project_verification_concept
            primary key,
    concept_set_id     uuid                           not null
        constraint fk_project_verification_concept_concept_set_id
            references project_verification_concept_set
            on delete restrict,
    org_id             uuid                           not null
        constraint fk_project_verification_concept_org_id
            references organization
            on delete restrict,
    teaches_id         uuid                           not null
        constraint fk_project_verification_concept_teaches_id
            references teaches
            on delete restrict,
    source_mapping_id  uuid                           not null
        constraint fk_project_verification_concept_source_mapping_id
            references curriculum_teaches_mapping
            on delete restrict,
    sequence_no        integer                        not null,
    constraint uq_project_verification_concept_concept_set_id_sequence_no
        unique (concept_set_id, sequence_no),
    constraint uq_project_verification_concept_concept_set_id_teaches_id
        unique (concept_set_id, teaches_id)
);

comment on table project_verification_concept is '활성 개념 세트에 포함된 세 개의 검증 개념과 원천 교안 매핑, 문제 표시 순서를 저장합니다. 선택된 개념은 미니프로젝트의 TEAM_SHARED_PROBLEM 계획 문제 슬롯 3건을 만드는 의미 축입니다. | 정의서명: ProjectVerificationConcept | 제약·비고: • 개념 세트마다 정확히 3건을 보유하고 sequence_no는 1·2·3만 허용합니다. • UNIQUE(concept_set_id, sequence_no), UNIQUE(concept_set_id, teaches_id) • source_mapping_id의 teaches_id와 본 행의 teaches_id가 일치해야 합니다. • 원천 매핑은 해당 프로젝트에 연결된 정확한 CurriculumVersion 범위의 유효 승인 매핑이어야 합니다. • 미니프로젝트는 선택 개념 3건에 대응하는 AssessmentProblem 슬롯 3건을 항상 기록합니다. • 코드 근거를 찾은 개념은 GENERATED로 문제와 세션별 ProblemStage 4건을 생성하고, 찾지 못한 개념은 NOT_GENERATED·NO_MATCHING_CODE_EVIDENCE로 기록하며 단계·질문·힌트를 생성하지 않습니다. • 실제 출제 문제는 1~3건일 수 있으며 NOT_GENERATED를 L1 미달이나 0단으로 치환하지 않습니다. • BIG_PROJECT에는 고정 ProjectVerificationConcept를 만들지 않습니다.';

comment on column project_verification_concept.project_concept_id is '검증 개념 항목을 식별하는 기본키이다.';

comment on column project_verification_concept.concept_set_id is '소속 개념 세트를 참조한다.';

comment on column project_verification_concept.org_id is '검증 개념의 기관 경계이다.';

comment on column project_verification_concept.teaches_id is '공통 개념 원장을 참조한다.';

comment on column project_verification_concept.source_mapping_id is '교안 내 개념 위치 매핑을 참조한다.';

comment on column project_verification_concept.sequence_no is '개념 세트 내 표시·문항 순서이다.';

alter table project_verification_concept
    owner to postgres;

grant delete, insert, select, update on project_verification_concept to teamiz_app;

create unique index uq_proj_verif_concept_set_active
    on project_verification_concept_set (project_id)
    where (((status)::text = 'ACTIVE'::text) AND (effective_to IS NULL));

grant delete, insert, select, update on project_verification_concept_set to teamiz_app;

create table manager_assignment
(
    assignment_id     uuid                     default gen_random_uuid()           not null
        constraint pk_manager_assignment
            primary key,
    manager_user_id   uuid                                                         not null
        constraint fk_manager_assignment_manager_user_id
            references app_user
            on delete restrict,
    org_id            uuid                                                         not null
        constraint fk_manager_assignment_org_id
            references organization
            on delete restrict,
    class_id          uuid                                                         not null
        constraint fk_manager_assignment_class_id
            references class
            on delete restrict,
    assigned_at       timestamp with time zone                                     not null,
    unassigned_at     timestamp with time zone,
    status            varchar(30)              default 'ACTIVE'::character varying not null
        constraint ck_manager_assignment_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('ENDED'::character varying)::text])),
    assigned_by       uuid                                                         not null
        constraint fk_manager_assignment_assigned_by
            references app_user
            on delete restrict,
    unassigned_by     uuid
        constraint fk_manager_assignment_unassigned_by
            references app_user
            on delete restrict,
    unassigned_reason varchar(50)
        constraint ck_manager_assignment_unassigned_reason
            check ((unassigned_reason)::text = ANY
                   (ARRAY [('MANUAL_UNASSIGN'::character varying)::text, ('CLASS_CLOSED'::character varying)::text, ('COHORT_CLOSED'::character varying)::text, ('ACCOUNT_INACTIVATED'::character varying)::text, ('ADMIN_CORRECTION'::character varying)::text])),
    created_at        timestamp with time zone default CURRENT_TIMESTAMP           not null,
    constraint ex_manager_assignment_no_overlap
        exclude using gist (manager_user_id with =, class_id with =, tstzrange(assigned_at,
                                                                               COALESCE(unassigned_at, 'infinity'::timestamp with time zone),
                                                                               '[)'::text) with &&),
    constraint ck_manager_assignment_unassigned_pair
        check ((((status)::text = 'ACTIVE'::text) AND (unassigned_at IS NULL) AND (unassigned_by IS NULL) AND
                (unassigned_reason IS NULL)) OR
               (((status)::text = 'ENDED'::text) AND (unassigned_at IS NOT NULL) AND (unassigned_by IS NOT NULL) AND
                (unassigned_reason IS NOT NULL) AND (assigned_at < unassigned_at)))
);

comment on table manager_assignment is '매니저가 특정 반을 관리할 수 있는 권한 범위를 기간형으로 보존합니다. 기수 전체 배정과 오퍼레이터 배정은 만들지 않고 한 매니저의 복수 반과 한 반의 공동 매니저를 허용합니다. | 정의서명: ManagerAssignment | 제약·비고: • 배정 대상은 같은 기관의 Role.code=''MANAGER'' 사용자만 허용합니다. • class_id는 필수이며 담당 기수는 Class.cohort_id에서 파생합니다. • 상태: ACTIVE / ENDED • ACTIVE이면 unassigned_at·unassigned_by·unassigned_reason이 모두 NULL입니다. • ENDED이면 unassigned_at·unassigned_by·unassigned_reason이 필수이고 assigned_at < unassigned_at이어야 합니다. • 같은 manager_user_id + class_id의 유효 기간 중첩을 금지합니다. • 한 매니저의 여러 반과 한 반의 여러 매니저를 허용합니다. • 대상 Class는 ACTIVE여야 하고 매니저·반·기관 경로가 일치해야 합니다. • 계정 INACTIVE 전이 시 활성 배정을 ACCOUNT_INACTIVATED로 종료하며 재활성화해도 자동 복원하지 않습니다. • 해제 사유: MANUAL_UNASSIGN / CLASS_CLOSED / COHORT_CLOSED / ACCOUNT_INACTIVATED / ADMIN_CORRECTION';

comment on column manager_assignment.assignment_id is '매니저 반 배정을 식별하는 기본키이다.';

comment on column manager_assignment.manager_user_id is '담당 매니저 계정을 참조한다.';

comment on column manager_assignment.org_id is '매니저 배정이 속한 기관이다.';

comment on column manager_assignment.class_id is '담당 반을 참조한다.';

comment on column manager_assignment.assigned_at is '매니저 배정이 시작된 시각이다.';

comment on column manager_assignment.unassigned_at is '매니저 배정이 종료된 시각이다.';

comment on column manager_assignment.status is '매니저 배정 상태이다.';

comment on column manager_assignment.assigned_by is '매니저 배정을 생성한 사용자를 참조한다.';

comment on column manager_assignment.unassigned_by is '매니저 배정을 종료한 사용자를 선택적으로 참조한다.';

comment on column manager_assignment.unassigned_reason is '매니저 배정 종료 사유 코드이다.';

comment on column manager_assignment.created_at is '매니저 배정 원장이 생성된 시각이다.';

alter table manager_assignment
    owner to postgres;

grant delete, insert, select, update on manager_assignment to teamiz_app;

create table team
(
    team_id          uuid                     default gen_random_uuid()          not null
        constraint pk_team
            primary key,
    project_id       uuid                                                        not null
        constraint fk_team_project_id
            references project
            on delete restrict,
    class_id         uuid                                                        not null
        constraint fk_team_class_id
            references class
            on delete restrict,
    org_id           uuid                                                        not null
        constraint fk_team_org_id
            references organization
            on delete restrict,
    team_number      text                                                        not null,
    name             varchar(200)                                                not null,
    status           varchar(30)              default 'DRAFT'::character varying not null
        constraint ck_team_status
            check ((status)::text = ANY
                   (ARRAY [('DRAFT'::character varying)::text, ('CONFIRMED'::character varying)::text])),
    min_member_count integer                                                     not null
        constraint ck_team_min_member_count
            check (min_member_count > 0),
    max_member_count integer                                                     not null,
    row_version      integer                  default 0                          not null
        constraint ck_team_row_version
            check (row_version >= 0),
    created_by       uuid                                                        not null
        constraint fk_team_created_by
            references app_user
            on delete restrict,
    created_at       timestamp with time zone default CURRENT_TIMESTAMP          not null,
    updated_at       timestamp with time zone default CURRENT_TIMESTAMP          not null,
    deleted_at       timestamp with time zone,
    constraint uq_team_project_id_class_id_name
        unique (project_id, class_id, name),
    constraint uq_team_project_id_class_id_team_number
        unique (project_id, class_id, team_number),
    constraint ck_team_max_member_count
        check (max_member_count >= min_member_count)
);

comment on table team is '프로젝트·반별 제출 단위와 팀 편성 상태, 안정적인 팀 번호, 인원 범위와 동시 수정 버전을 관리합니다. 프로젝트가 달라지면 같은 팀 번호라도 별개의 team_id와 팀원 구성을 가집니다. | 정의서명: Team | 제약·비고: • team_number > 0 • 활성 범위 UNIQUE(project_id, class_id, team_number), UNIQUE(project_id, class_id, name) • 상태: DRAFT / CONFIRMED • min_member_count > 0, max_member_count >= min_member_count • Project.cohort_id와 Class.cohort_id, 모든 org_id 경로가 일치해야 합니다. • 같은 project_id + class_id의 활성 팀 집합은 정상 상태에서 모두 DRAFT 또는 모두 CONFIRMED여야 하며 혼합 상태를 금지합니다. • 팀 번호는 OP-02 행 슬롯과 정렬에 사용하지만 프로젝트 간 동일 팀 계보나 동일 구성원을 뜻하지 않습니다. • 화면 team_display_key=''TEAM_NO_'' || team_number는 조회 파생값입니다. • 편성 확정은 관련 팀·참여·소속 행을 잠그고 전원 배정·팀명·인원 범위를 재검증한 뒤 모든 팀을 원자적으로 CONFIRMED로 변경합니다. • SUBMISSION_STARTED는 Team.status에 저장하지 않고 유효 Submission 존재 여부에서 파생합니다. • row_version은 팀명·팀원·잠금의 낙관적 동시성 검증에 사용합니다.';

comment on column team.team_id is '프로젝트 팀을 식별하는 기본키이다.';

comment on column team.project_id is '팀이 속한 프로젝트이다.';

comment on column team.class_id is '팀 편성 대상 반을 참조한다.';

comment on column team.org_id is '팀의 기관 경계이다.';

comment on column team.team_number is '팀 번호 값을 저장한다.';

comment on column team.name is '팀 표시 및 업무 식별 명칭이다.';

comment on column team.status is '팀 편성 상태이다.';

comment on column team.min_member_count is '팀의 최소 허용 인원 수이다.';

comment on column team.max_member_count is '팀의 최대 허용 인원 수이다.';

comment on column team.row_version is '동시 편성 변경을 제어하는 낙관적 잠금 버전이다.';

comment on column team.created_by is '팀 생성자를 참조한다.';

comment on column team.created_at is '팀이 생성된 시각이다.';

comment on column team.updated_at is '팀의 최근 수정 시각이다.';

comment on column team.deleted_at is '팀이 논리 삭제된 시각이다.';

alter table team
    owner to postgres;

create table repository
(
    repository_id          uuid                     default gen_random_uuid()           not null
        constraint pk_repository
            primary key,
    project_id             uuid                                                         not null
        constraint fk_repository_project_id
            references project
            on delete restrict,
    team_id                uuid                                                         not null
        constraint fk_repository_team_id
            references team
            on delete restrict,
    org_id                 uuid                                                         not null
        constraint fk_repository_org_id
            references organization
            on delete restrict,
    provider               varchar(100)                                                 not null
        constraint ck_repository_provider
            check ((provider)::text = 'GITHUB'::text),
    external_repository_id uuid,
    owner_login            text,
    repository_name        varchar(200),
    repo_url               text                                                         not null,
    normalized_repo_url    text                                                         not null,
    default_branch         text,
    status                 varchar(30)              default 'ACTIVE'::character varying not null
        constraint ck_repository_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text])),
    created_at             timestamp with time zone default CURRENT_TIMESTAMP           not null,
    updated_at             timestamp with time zone default CURRENT_TIMESTAMP           not null
);

comment on table repository is 'GitHub 방식 팀 제출에 사용하는 외부 저장소의 안정 식별 정보와 기관 GitHub App 연결 경로를 보존합니다. 현재 접근 확인 결과는 저장하지 않고 실행별 RepositoryVerification에서 관리합니다. | 정의서명: Repository | 제약·비고: • provider는 현재 GITHUB만 허용합니다. • 상태: ACTIVE / INACTIVE • Repository.team_id는 필수이고 Project·Team·Organization 경로가 일치해야 합니다. • 팀당 현재 ACTIVE 저장소는 최대 1건입니다. • external_repository_id는 공급자 범위에서 안정 식별자로 사용하고 활성 중복을 금지합니다. • URL 문자열만으로 기관 조직 저장소 여부를 판정하지 않고 OrganizationGitHubIntegration의 외부 조직·installation 범위를 검증합니다. • OrganizationPolicy.allow_github_integration=FALSE이거나 유효 연결이 없으면 신규 GitHub 제출을 허용하지 않습니다. • access_status·checked_at·checked_by는 Repository에 저장하지 않고 RepositoryVerification 실행 이력에서 관리합니다. • ZIP_WITH_GITLOG 제출에는 Repository 행을 요구하지 않습니다. • 토큰·자격증명·서명 URL 원문은 repo_url과 감사 payload에 저장하지 않습니다. • external_repository_id·owner_login·repository_name·default_branch는 GitHub API에서만 얻는 값이라 AI 분석 응답이 제공하지 않으면 NULL입니다(2026-08-06, S-10). 값이 없어도 커밋 귀속과 기여도 산정을 진행할 수 있도록 NOT NULL을 해제했습니다. Repository 행은 GitHub 제출 접수 시점에 생성하고, 같은 팀이 재제출하면 새 행을 만들지 않고 ACTIVE 행의 repo_url·normalized_repo_url을 UPDATE합니다(2026-08-06, S-12). uq_repository_active_per_team이 팀당 ACTIVE 1건을 강제하기 때문입니다. 이때 덮어쓴 이전 URL은 보존되지 않으므로 제출별 URL 이력이 필요하면 RepositoryVerification을 조회합니다.';

comment on column repository.repository_id is '팀 저장소 이력을 식별하는 기본키이다.';

comment on column repository.project_id is '저장소가 속한 프로젝트이다.';

comment on column repository.team_id is '저장소 소유 팀을 참조한다.';

comment on column repository.org_id is '저장소의 기관 경계이다.';

comment on column repository.provider is '공급자 값을 저장한다.';

comment on column repository.external_repository_id is '공급자가 부여한 저장소 고유 식별자다. GitHub 접근 주체가 AI 서버로 확정되어 백엔드가 직접 만들 수 없고, AI 응답이 이 값을 주지 않으면 NULL로 남는다(2026-08-06, S-10). ⚠️ 타입이 UUID인데 GitHub은 정수 id 또는 base64 node_id를 주므로 값 제공이 확정되면 VARCHAR로 변경해야 한다(D-01).';

comment on column repository.owner_login is '저장소 소유 계정 또는 조직 로그인명이다. AI 응답이 주지 않으면 NULL이다(2026-08-06, S-10). 제출 URL에서 파싱할 수도 있으나 소유자 이전·리네임 시 URL과 실제가 어긋나므로 추측해 채우지 않는다.';

comment on column repository.repository_name is '저장소 이름이다. AI 응답이 주지 않으면 NULL이다(2026-08-06, S-10).';

comment on column repository.repo_url is '입력된 저장소 URL이다.';

comment on column repository.normalized_repo_url is '중복 검증에 사용하는 정규화 저장소 URL이다.';

comment on column repository.default_branch is '저장소 기본 브랜치다. 교육생이 브랜치를 지정하지 않았을 때 무엇으로 분석했는지의 근거이며, GitHub API로만 확인할 수 있어 AI 응답이 주지 않으면 NULL이다(2026-08-06, S-10).';

comment on column repository.status is '저장소 이력의 활성 상태이다.';

comment on column repository.created_at is '저장소 이력이 생성된 시각이다.';

comment on column repository.updated_at is '저장소 이력의 최근 수정 시각이다.';

alter table repository
    owner to postgres;

create unique index uq_repository_active_per_team
    on repository (team_id)
    where ((status)::text = 'ACTIVE'::text);

grant delete, insert, select, update on repository to teamiz_app;

create table repository_verification
(
    verification_id          uuid                     default gen_random_uuid() not null
        constraint pk_repository_verification
            primary key,
    org_id                   uuid                                               not null
        constraint fk_repository_verification_org_id
            references organization
            on delete restrict,
    team_id                  uuid                                               not null
        constraint fk_repository_verification_team_id
            references team
            on delete restrict,
    repository_id            uuid
        constraint fk_repository_verification_repository_id
            references repository
            on delete restrict,
    normalized_repo_url      text                                               not null,
    requested_branch         text,
    resolved_branch          text,
    head_commit_sha          varchar(64),
    head_commit_message      text,
    head_commit_committed_at timestamp with time zone,
    status                   varchar(30)                                        not null
        constraint ck_repository_verification_status
            check ((status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('CHECKING'::character varying)::text, ('VERIFIED'::character varying)::text, ('FAILED'::character varying)::text])),
    failure_code             varchar(100)
        constraint ck_repository_verification_failure_code
            check ((failure_code)::text = ANY
                   (ARRAY [('INVALID_REPOSITORY_URL'::character varying)::text, ('REPO_NOT_FOUND'::character varying)::text, ('REPOSITORY_ACCESS_DENIED'::character varying)::text, ('BRANCH_NOT_FOUND'::character varying)::text, ('UNSUPPORTED_HOST'::character varying)::text, ('TEMPORARY_ERROR'::character varying)::text])),
    requested_at             timestamp with time zone default CURRENT_TIMESTAMP not null,
    checked_at               timestamp with time zone,
    expires_at               timestamp with time zone,
    request_idempotency_key  uuid                                               not null
        constraint uq_repository_verification_request_idempotency_key
            unique
);

comment on table repository_verification is 'GitHub 저장소 URL·브랜치·접근 가능 여부와 확인 시점 HEAD 커밋을 실행별로 보존하는 이력 원장입니다. 주소 수정·브랜치 변경·재확인은 새 행으로 저장하여 이전 실패와 성공 결과를 덮어쓰지 않습니다. | 정의서명: RepositoryVerification | 제약·비고: • 상태: PENDING / CHECKING / VERIFIED / FAILED • 주소·브랜치 확인 요청마다 새 행을 생성하며 기존 행을 UPDATE해 이력을 지우지 않습니다. • repository_id는 유효 Repository 생성 전 URL 검증 단계에서는 NULL일 수 있습니다. • repository_id가 있으면 Repository.team_id·org_id·normalized_repo_url이 본 행과 일치해야 합니다. • PENDING·CHECKING에서는 failure_code·checked_at·expires_at이 NULL입니다. • VERIFIED이면 resolved_branch·head_commit_sha·head_commit_message·head_commit_committed_at·checked_at이 필수이고 failure_code는 NULL입니다. • FAILED이면 failure_code·checked_at이 필수이고 성공 HEAD 메타데이터는 NULL이어야 합니다. • 실패 코드: INVALID_REPOSITORY_URL / REPO_NOT_FOUND / REPOSITORY_ACCESS_DENIED / BRANCH_NOT_FOUND / UNSUPPORTED_HOST / TEMPORARY_ERROR • expires_at이 있으면 checked_at보다 이후여야 합니다. • requested_at은 코드 분석이 저장소 확인을 시작한 시각이며 PENDING·CHECKING을 포함한 모든 상태에서 필수입니다. 교육생의 제출 시각은 Submission.submitted_at이 따로 소유하며 이 값과 무관합니다(2026-08-06, S-12). 확인 실행 행은 제출 시점이 아니라 분석 배치가 확인을 시작할 때 생성합니다. • 같은 팀의 최신 확인 실행은 checked_at이 아니라 requested_at 기준으로 선택합니다. • request_idempotency_key는 클라이언트가 보낸 멱등키이며 같은 값의 재요청은 최초 결과를 그대로 재조회하는 데 사용합니다(2026-08-06, S-09). UNIQUE 제약이 동시 재시도의 중복 INSERT를 막습니다. 원문 자격증명은 저장하지 않습니다';

comment on column repository_verification.verification_id is '확인 실행 ID 값을 저장한다.';

comment on column repository_verification.org_id is '기관 ID 값을 저장한다.';

comment on column repository_verification.team_id is '팀 ID 값을 저장한다.';

comment on column repository_verification.repository_id is '저장소 ID 값을 저장한다.';

comment on column repository_verification.normalized_repo_url is '정규화 저장소 URL 값을 저장한다.';

comment on column repository_verification.requested_branch is '요청 브랜치 값을 저장한다.';

comment on column repository_verification.resolved_branch is '확인 브랜치 값을 저장한다.';

comment on column repository_verification.head_commit_sha is 'HEAD 커밋 SHA 값을 저장한다.';

comment on column repository_verification.head_commit_message is 'HEAD 커밋 메시지 값을 저장한다.';

comment on column repository_verification.head_commit_committed_at is 'HEAD 커밋 일시 값을 저장한다.';

comment on column repository_verification.status is '상태 값을 저장한다.';

comment on column repository_verification.failure_code is '실패 코드 값을 저장한다.';

comment on column repository_verification.requested_at is '코드 분석이 저장소 확인을 시작한 시각이다. 확인 완료(checked_at)와 달리 PENDING·CHECKING 상태에서도 항상 존재한다. 제출 접수 시각과는 무관하며 교육생의 제출 시각은 Submission.submitted_at 이 소유한다(2026-08-06, S-12). 저장소 접근 주체가 AI 서버로 확정되어 확인이 마감 후 분석 단계로 이동하면서 두 시각이 며칠 벌어질 수 있게 되었기 때문이다.';

comment on column repository_verification.checked_at is '확인 완료 일시 값을 저장한다.';

comment on column repository_verification.expires_at is '확인 결과 만료 일시 값을 저장한다.';

comment on column repository_verification.request_idempotency_key is '분석 배치가 AI 서버에 저장소 확인·분석을 요청할 때 사용한 멱등키다(submissionId:executionNo). 확인 실행 행이 분석 시점에 생성되므로 이 값도 그때 확정된다. 교육생이 보낸 제출 멱등키는 Submission.request_idempotency_key가 따로 소유한다(2026-08-06, S-13).';

alter table repository_verification
    owner to postgres;

grant delete, insert, select, update on repository_verification to teamiz_app;

create table submission
(
    submission_id              uuid    default gen_random_uuid() not null
        constraint pk_submission
            primary key,
    org_id                     uuid                              not null
        constraint fk_submission_org_id
            references organization
            on delete restrict,
    team_id                    uuid                              not null
        constraint fk_submission_team_id
            references team
            on delete restrict,
    assessment_round_id        uuid                              not null
        constraint fk_submission_assessment_round_id
            references project_assessment_round
            on delete restrict,
    method                     varchar(100)                      not null
        constraint ck_submission_method
            check ((method)::text = ANY
                   (ARRAY [('GITHUB_URL'::character varying)::text, ('ZIP_WITH_GITLOG'::character varying)::text])),
    repository_id              uuid
        constraint fk_submission_repository_id
            references repository
            on delete restrict,
    requested_branch           text,
    resolved_branch            text,
    source_commit_sha          varchar(64),
    source_commit_message      text,
    source_commit_committed_at timestamp with time zone,
    supersedes_submission_id   uuid
        constraint fk_submission_supersedes_submission_id
            references submission
            on delete restrict,
    submitted_by               uuid                              not null
        constraint fk_submission_submitted_by
            references app_user
            on delete restrict,
    status                     varchar(100)                      not null
        constraint ck_submission_status
            check ((status)::text = ANY
                   (ARRAY [('VALIDATING'::character varying)::text, ('ACCEPTED'::character varying)::text, ('FETCH_FAILED'::character varying)::text, ('INVALID'::character varying)::text])),
    submitted_at               timestamp with time zone          not null,
    is_current                 boolean default false             not null,
    failure_reason             text,
    analysis_input_hash        varchar(128),
    git_history                jsonb
        constraint ck_submission_git_history
            check ((git_history IS NULL) OR (jsonb_typeof(git_history) = ANY (ARRAY ['array'::text, 'object'::text]))),
    analysis_input_file_count  integer
        constraint ck_submission_analysis_input_file_count
            check ((analysis_input_file_count IS NULL) OR (analysis_input_file_count >= 0)),
    analysis_input_captured_at timestamp with time zone,
    code_snippets              jsonb
        constraint ck_submission_code_snippets
            check ((code_snippets IS NULL) OR ((jsonb_typeof(code_snippets) = 'array'::text) AND
                                               ((jsonb_array_length(code_snippets) >= 0) AND
                                                (jsonb_array_length(code_snippets) <= 3)))),
    analysis_input_byte_count  bigint
        constraint ck_submission_analysis_input_byte_count
            check ((analysis_input_byte_count IS NULL) OR (analysis_input_byte_count >= 0)),
    repository_verification_id uuid
        constraint fk_submission_repository_verification_id
            references repository_verification
            on delete restrict,
    request_idempotency_key    uuid
        constraint uq_submission_request_idempotency_key
            unique,
    constraint ck_submission_analysis_input_captured_at
        check (((analysis_input_captured_at IS NULL) AND (analysis_input_hash IS NULL) AND (git_history IS NULL) AND
                (code_snippets IS NULL) AND (analysis_input_file_count IS NULL)) OR
               ((analysis_input_captured_at IS NOT NULL) AND (analysis_input_hash IS NOT NULL) AND
                (git_history IS NOT NULL) AND (code_snippets IS NOT NULL) AND (analysis_input_file_count IS NOT NULL))),
    constraint ck_submission_method_2
        check (((method)::text = 'GITHUB_URL'::text) OR
               (((method)::text = 'ZIP_WITH_GITLOG'::text) AND (repository_id IS NULL) AND
                (requested_branch IS NULL) AND (resolved_branch IS NULL) AND (source_commit_sha IS NULL) AND
                (source_commit_message IS NULL) AND (source_commit_committed_at IS NULL))),
    constraint ck_submission_repository_verification_id
        check (((method)::text = 'GITHUB_URL'::text) OR (repository_verification_id IS NULL)),
    constraint ck_submission_status_2
        check ((((status)::text = ANY
                 (ARRAY [('FETCH_FAILED'::character varying)::text, ('INVALID'::character varying)::text])) AND
                (failure_reason IS NOT NULL)) OR (((status)::text = ANY
                                                   (ARRAY [('VALIDATING'::character varying)::text, ('ACCEPTED'::character varying)::text])) AND
                                                  (failure_reason IS NULL))),
    constraint ck_submission_supersedes_submission_id
        check ((supersedes_submission_id IS NULL) OR (supersedes_submission_id <> submission_id))
);

comment on table submission is '프로젝트 회차의 팀 단위 GitHub·ZIP 코드 제출과 재제출 이력, 그리고 해당 제출에서 확정한 불변 분석 입력과 문제별 코드 스니펫 원문을 함께 보존합니다. 개인 수행은 Submission을 소유하지 않고 같은 팀 제출을 source_submission_id로 공유합니다. | 정의서명: Submission | 제약·비고: • method: GITHUB_URL / ZIP_WITH_GITLOG • 상태: VALIDATING / ACCEPTED / FETCH_FAILED / INVALID • 팀·회차별 is_current=TRUE인 제출은 최대 1건입니다. • 재제출은 기존 현재 행을 유지한 채 새 행을 생성하고 supersedes_submission_id로 직전 제출을 참조한 뒤 현재 플래그를 원자 교체합니다. • submitted_by는 submitted_at 시점의 해당 팀 유효 ProjectMembership·TeamMembership 교육생이어야 합니다. • GITHUB_URL이면 repository_id·resolved_branch·source_commit_sha·커밋 메타데이터가 필수입니다. • ZIP_WITH_GITLOG이면 repository_id·브랜치·커밋 SHA는 NULL이며, 업로드 접수 시 status=''VALIDATING''으로 행을 먼저 생성하고 검증 성공 시 ACCEPTED로 전이합니다. status=''ACCEPTED'' 시점에 검증 완료 SubmissionArtifact 정확히 1건이 연결되어야 합니다. submitted_at은 업로드 접수 시각으로 고정합니다. • submitted_at은 회차 submission_due_at 이전이어야 하며 마감 후 신규 제출·재제출을 금지합니다. • 마감 후 분석 배치가 현재 제출을 선택해 analysis_input_hash·git_history·code_snippets·analysis_input_file_count·analysis_input_captured_at을 최초 1회 확정합니다. • 분석 입력 속성은 모두 NULL이거나 모두  • GITHUB_URL 제출은 접수 시 repository_verification_id만 확정하고 repository_id·resolved_branch·source_commit 메타데이터는 AI 분석 성공 후에 채웁니다(2026-08-06, S-01·S-02). • 제출 접수는 URL 형식·호스트 검사만 통과하면 status=ACCEPTED이며 저장소 접근 실패는 분석 단계의 사건으로 AnalysisJob.failure_code에 기록하고 제출 상태를 FETCH_FAILED로 내리지 않습니다.';

comment on column submission.submission_id is '교육생 코드 제출 이력의 개별 레코드를 식별하는 고유 키이다.';

comment on column submission.org_id is '기관 ID 값을 저장한다.';

comment on column submission.team_id is '팀 ID 값을 저장한다.';

comment on column submission.assessment_round_id is '평가 회차 ID 값을 저장한다.';

comment on column submission.method is '제출의 방법을 나타내는 코드이다. 허용값과 의미는 코드 서식 및 CHECK 제약을 따른다.';

comment on column submission.repository_id is '저장소 ID 값을 저장한다.';

comment on column submission.requested_branch is '요청 브랜치 값을 저장한다.';

comment on column submission.resolved_branch is '확인 브랜치 값을 저장한다.';

comment on column submission.source_commit_sha is '원천 커밋 SHA 값을 저장한다.';

comment on column submission.source_commit_message is '원천 커밋 메시지 값을 저장한다.';

comment on column submission.source_commit_committed_at is '원천 커밋 일시 값을 저장한다.';

comment on column submission.supersedes_submission_id is '대체 이전 제출 ID 값을 저장한다.';

comment on column submission.submitted_by is '제출자 ID 값을 저장한다.';

comment on column submission.status is '교육생 코드 제출 이력의 현재 업무 처리 상태를 나타낸다.';

comment on column submission.submitted_at is '교육생이 제출을 요청한 시각이다. 서버가 요청을 받은 시점으로 확정하며 이후 어떤 외부 처리에도 영향받지 않는다. 회차 마감(submission_due_at) 대비 ON_TIME/LATE/MISSED 판정의 기준이므로, 저장소 확인이나 분석에 걸린 시간이 이 값을 밀어서는 안 된다(2026-08-06, S-12).';

comment on column submission.is_current is '제출의 여부 현재을 나타내는 불리언 값이다.';

comment on column submission.failure_reason is '제출의 실패 사유 내용을 기록한다.';

comment on column submission.analysis_input_hash is '분석에 사용할 제출 소스 버전, Git 이력과 문제별 코드 스니펫 원문의 동일성·무결성 검증 해시를 저장한다.';

comment on column submission.git_history is '분석 입력 확정 시 수집한 커밋·작성자·변경 파일·라인 통계 등의 Git 이력 원본을 구조화하여 저장한다.';

comment on column submission.analysis_input_file_count is '코드 분석 입력에 포함된 고유 파일 수를 기록한다.';

comment on column submission.analysis_input_captured_at is '현재 제출을 불변 분석 입력으로 정규화하여 확정한 시각을 기록한다.';

comment on column submission.code_snippets is '실제 문제 출제에 사용한 문제별 대표 코드 스니펫 원문과 식별 키·문제 번호·언어·파일 경로·라인 범위·해시를 최대 3건의 배열로 저장한다.';

comment on column submission.analysis_input_byte_count is '분석 입력 스냅샷의 전체 바이트 수이다.';

comment on column submission.repository_verification_id is '이 제출의 저장소 URL·브랜치 확인 실행을 가리키는 식별자이다. repository_id가 AI 분석 성공 전까지 NULL이므로 제출된 URL과 요청 브랜치는 이 확인 실행 행에서만 확인된다. ZIP 제출에는 연결하지 않는다. RepositoryVerification에는 assessment_round_id가 없어 과거 회차 제출의 URL 추적도 이 참조로만 가능하다.';

comment on column submission.request_idempotency_key is '클라이언트가 Idempotency-Key 헤더로 보낸 제출 요청의 멱등키다. GITHUB_URL·ZIP_WITH_GITLOG 양쪽이 같은 자리를 쓴다. 같은 값의 재요청은 새 제출을 만들지 않고 최초 결과를 반환한다. 재시도해도 값이 바뀌지 않는다는 점에서 요청마다 새로 만드는 추적 ID와 다르다. 이 컬럼은 2026-08-06(S-13)에 신설했으므로 그 이전에 접수된 행은 NULL이다.';

alter table submission
    owner to postgres;

create index ix_submission_repository_verification_id
    on submission (repository_verification_id)
    where (repository_verification_id IS NOT NULL);

create unique index uq_submission_current
    on submission (team_id, assessment_round_id)
    where (is_current = true);

grant delete, insert, select, update on submission to teamiz_app;

create table submission_artifact
(
    artifact_id               uuid default gen_random_uuid() not null
        constraint pk_submission_artifact
            primary key,
    submission_id             uuid
        constraint uq_submission_artifact_submission_id
            unique
        constraint fk_submission_artifact_submission_id
            references submission
            on delete restrict,
    artifact_type             varchar(100)                   not null,
    original_file_name        varchar(255)                   not null,
    content_type              varchar(100)                   not null,
    storage_uri               text                           not null,
    safe_extract_uri          text,
    content_hash              varchar(128)                   not null,
    file_size_bytes           bigint                         not null
        constraint ck_submission_artifact_file_size_bytes
            check (file_size_bytes >= 0),
    applied_max_file_bytes    bigint                         not null
        constraint ck_submission_artifact_applied_max_file_bytes
            check (applied_max_file_bytes > 0),
    validation_status         varchar(100)                   not null
        constraint ck_submission_artifact_validation_status
            check ((validation_status)::text = ANY
                   (ARRAY [('VALIDATING'::character varying)::text, ('VERIFIED'::character varying)::text, ('FAILED'::character varying)::text])),
    validation_failure_code   varchar(100)
        constraint ck_submission_artifact_validation_failure_code
            check ((validation_failure_code)::text = ANY
                   (ARRAY [('FILE_TOO_LARGE'::character varying)::text, ('ARCHIVE_INVALID'::character varying)::text, ('EMPTY_CODE'::character varying)::text, ('PROHIBITED_FILE'::character varying)::text, ('GIT_LOG_MISSING'::character varying)::text])),
    validated_at              timestamp with time zone,
    extraction_policy_version integer,
    constraint ck_submission_artifact_validation_status_2
        check ((((validation_status)::text = 'VALIDATING'::text) AND (validated_at IS NULL) AND
                (validation_failure_code IS NULL) AND (safe_extract_uri IS NULL)) OR
               (((validation_status)::text = 'VERIFIED'::text) AND (validated_at IS NOT NULL) AND
                (validation_failure_code IS NULL) AND (safe_extract_uri IS NOT NULL) AND
                (file_size_bytes <= applied_max_file_bytes)) OR
               (((validation_status)::text = 'FAILED'::text) AND (validated_at IS NOT NULL) AND
                (validation_failure_code IS NOT NULL)))
);

comment on table submission_artifact is 'ZIP 원본·안전 추출본·Git 로그를 묶은 업로드 검증 원장입니다. 검증 중·실패 이력은 Submission 없이 존재할 수 있고 검증 성공 후 ZIP 제출에 최대 1건 연결됩니다. | 정의서명: SubmissionArtifact | 제약·비고: • submission_id는 검증 중 NULL을 허용하고 연결된 경우 UNIQUE입니다. • GitHub 제출에는 연결하지 않으며 ZIP_WITH_GITLOG 제출당 검증 완료 산출물 최대 1건입니다. • file_size_bytes >= 0, applied_max_file_bytes > 0 • validation_status=''VERIFIED''이면 validated_at·safe_extract_uri가 필수이고 file_size_bytes <= applied_max_file_bytes여야 합니다. • 검증 실패이면 validation_failure_code와 validated_at이 필수이며 새 Submission·AnalysisJob을 만들지 않습니다. • 용량 초과·손상 압축·빈 코드·금지 파일·Git 로그 누락을 구분하고 실패 원본은 즉시 삭제 또는 만료 처리합니다. • 압축 경로 이동·심볼릭 링크 탈출·실행 파일 자동 실행을 금지하고 원본과 분석용 안전 추출본을 분리합니다. • request_idempotency_key는 ZIP 업로드 재시도의 멱등키이며 UNIQUE입니다. 2026-08-06(S-09)에 신설했으며 그 이전 행은 NULL입니다.';

comment on column submission_artifact.artifact_id is '제출물 파일·코드 스냅샷의 개별 레코드를 식별하는 고유 키이다.';

comment on column submission_artifact.submission_id is '제출와의 업무 관계를 연결하는 외래 키이다.';

comment on column submission_artifact.artifact_type is '제출물 파일·코드 스냅샷에서 관리하는 산출물유형 정보이다.';

comment on column submission_artifact.original_file_name is '원본 파일명 값을 저장한다.';

comment on column submission_artifact.content_type is '콘텐츠 유형 값을 저장한다.';

comment on column submission_artifact.storage_uri is '제출물 파일·코드 스냅샷에서 관리하는 저장URI 정보이다.';

comment on column submission_artifact.safe_extract_uri is '안전 추출 URI 값을 저장한다.';

comment on column submission_artifact.content_hash is '제출 산출물의 원문을 저장하지 않고 비교·무결성 검증에 사용하는 해시 값이다.';

comment on column submission_artifact.file_size_bytes is '파일 크기(바이트) 값을 저장한다.';

comment on column submission_artifact.applied_max_file_bytes is '적용 최대 파일 크기 값을 저장한다.';

comment on column submission_artifact.validation_status is '검증 상태 값을 저장한다.';

comment on column submission_artifact.validation_failure_code is '검증 실패 코드 값을 저장한다.';

comment on column submission_artifact.validated_at is '검증 완료 일시 값을 저장한다.';

comment on column submission_artifact.extraction_policy_version is '추출 정책 버전 값을 저장한다.';

alter table submission_artifact
    owner to postgres;

grant delete, insert, select, update on submission_artifact to teamiz_app;

create table team_membership
(
    membership_id         uuid                     default gen_random_uuid() not null
        constraint pk_team_membership
            primary key,
    team_id               uuid                                               not null
        constraint fk_team_membership_team_id
            references team
            on delete restrict,
    project_membership_id uuid                                               not null
        constraint fk_team_membership_project_membership_id
            references project_membership
            on delete restrict,
    org_id                uuid                                               not null
        constraint fk_team_membership_org_id
            references organization
            on delete restrict,
    assignment_method     varchar(30)                                        not null
        constraint ck_team_membership_assignment_method
            check ((assignment_method)::text = ANY
                   (ARRAY [('AUTO'::character varying)::text, ('MANUAL'::character varying)::text, ('TRANSFER'::character varying)::text])),
    from_at               timestamp with time zone                           not null,
    to_at                 timestamp with time zone,
    assigned_by           uuid
        constraint fk_team_membership_assigned_by
            references app_user
            on delete restrict,
    created_at            timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint ck_team_membership_period_order
        check ((to_at IS NULL) OR (from_at < to_at))
);

comment on table team_membership is '프로젝트 참여자가 어느 팀에 속했는지 기간형으로 보존하는 이력입니다. 팀 이동은 기존 행 종료와 새 행 생성으로 처리하며 과거 제출·분석·결과 귀속을 현재 팀으로 바꾸지 않습니다. | 정의서명: TeamMembership | 제약·비고: • assignment_method: AUTO / MANUAL / TRANSFER • to_at IS NULL인 행을 현재 활성 팀 소속으로 봅니다. • to_at이 있으면 from_at < to_at이어야 합니다. • 같은 프로젝트에서 하나의 project_membership_id는 활성 TeamMembership을 최대 1건만 가집니다. • Team.project_id·class_id와 ProjectMembership.project_id·class_id가 일치해야 합니다. • AUTO이면 assigned_by NULL을 허용하고 MANUAL·TRANSFER는 처리자를 기록합니다. • TRANSFER는 기존 활성 행 종료와 신규 행 생성을 한 트랜잭션으로 처리합니다. • 첫 제출 이후 제출 완료 팀의 기존 팀원 제거·팀명 변경·팀 삭제를 제한하고 미배정자 추가만 허용합니다. • 과거 팀 귀속은 source_submission_id와 당시 유효 TeamMembership으로 재현합니다.';

comment on column team_membership.membership_id is '팀 소속 이력을 식별하는 기본키이다.';

comment on column team_membership.team_id is '소속 팀을 참조한다.';

comment on column team_membership.project_membership_id is '팀에 배정된 프로젝트 참여자를 참조한다.';

comment on column team_membership.org_id is '팀 소속 이력의 기관 경계이다.';

comment on column team_membership.assignment_method is '팀 배정 방식이다.';

comment on column team_membership.from_at is '팀 소속이 시작된 시각이다.';

comment on column team_membership.to_at is '팀 소속이 종료된 시각이다.';

comment on column team_membership.assigned_by is '팀 배정자를 선택적으로 참조한다.';

comment on column team_membership.created_at is '팀 소속 이력이 생성된 시각이다.';

alter table team_membership
    owner to postgres;

create unique index uq_team_membership_active
    on team_membership (project_membership_id)
    where (to_at IS NULL);

grant delete, insert, select, update on team_membership to teamiz_app;

create table interview_candidate
(
    candidate_id          uuid                     default gen_random_uuid() not null
        constraint pk_interview_candidate
            primary key,
    org_id                uuid                                               not null
        constraint fk_interview_candidate_org_id
            references organization
            on delete restrict,
    cohort_id             uuid                                               not null
        constraint fk_interview_candidate_cohort_id
            references cohort
            on delete restrict,
    class_id              uuid                                               not null
        constraint fk_interview_candidate_class_id
            references class
            on delete restrict,
    user_id               uuid                                               not null
        constraint fk_interview_candidate_user_id
            references app_user
            on delete restrict,
    project_id            uuid                                               not null
        constraint fk_interview_candidate_project_id
            references project
            on delete restrict,
    assessment_round_id   uuid                                               not null
        constraint fk_interview_candidate_assessment_round_id
            references project_assessment_round
            on delete restrict,
    team_id               uuid
        constraint fk_interview_candidate_team_id
            references team
            on delete restrict,
    project_membership_id uuid
        constraint fk_interview_candidate_project_membership_id
            references project_membership
            on delete restrict,
    team_membership_id    uuid
        constraint fk_interview_candidate_team_membership_id
            references team_membership
            on delete restrict,
    source_submission_id  uuid
        constraint fk_interview_candidate_source_submission_id
            references submission
            on delete restrict,
    status                varchar(100)                                       not null
        constraint ck_interview_candidate_status
            check ((status)::text = ANY
                   (ARRAY [('ELIGIBLE'::character varying)::text, ('EXCLUDED'::character varying)::text, ('INTERVIEW_CREATED'::character varying)::text])),
    exclusion_reason_code text,
    exclusion_note        text,
    excluded_by           uuid
        constraint fk_interview_candidate_excluded_by
            references app_user
            on delete restrict,
    excluded_at           timestamp with time zone,
    detected_at           timestamp with time zone                           not null,
    created_at            timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at            timestamp with time zone default CURRENT_TIMESTAMP not null,
    row_version           integer                  default 0                 not null,
    constraint uq_interview_candidate_org_id_assessment_round_id_user_id
        unique (org_id, assessment_round_id, user_id),
    constraint ck_interview_candidate_status_2
        check ((((status)::text = ANY
                 (ARRAY [('ELIGIBLE'::character varying)::text, ('INTERVIEW_CREATED'::character varying)::text])) AND
                (exclusion_reason_code IS NULL) AND (exclusion_note IS NULL) AND (excluded_by IS NULL) AND
                (excluded_at IS NULL)) OR
               (((status)::text = 'EXCLUDED'::text) AND (exclusion_reason_code IS NOT NULL) AND
                (excluded_by IS NOT NULL) AND (excluded_at IS NOT NULL)))
);

comment on table interview_candidate is '교육생×프로젝트 회차 단위의 면담 후보 현재 상태와 판정 당시 반·팀·참여·제출 귀속을 보존합니다. 복수 위험 사유는 InterviewCandidateReason이 소유합니다. | 정의서명: InterviewCandidate | 제약·비고: • UNIQUE(org_id, assessment_round_id, user_id) • status: ELIGIBLE / EXCLUDED / INTERVIEW_CREATED • 허용 전이는 ELIGIBLE→EXCLUDED, EXCLUDED→ELIGIBLE, ELIGIBLE→INTERVIEW_CREATED, INTERVIEW_CREATED→EXCLUDED, EXCLUDED→INTERVIEW_CREATED입니다. • 매니저가 브리프를 만들어 본 뒤 이번 회차 대상이 아니라고 판단하는 흐름을 지원하기 위해 INTERVIEW_CREATED에서도 제외를 허용합니다. 다만 연결된 면담이 PENDING인 후보로 한정하고 IN_PROGRESS·COMPLETED 면담이 있는 후보는 제외하지 않습니다. • INTERVIEW_CREATED에서 제외하더라도 연결된 Interview와 그 브리프·원천 행은 삭제하지 않고 그대로 보존하며 candidate_id UNIQUE 연결도 유지합니다. • EXCLUDED 재포함은 연결된 면담이 남아 있으면 INTERVIEW_CREATED로, 없으면 ELIGIBLE로 복귀시키고 어느 쪽이든 제외 속성을 모두 NULL로 되돌립니다. • 위험 사유 해소만으로 후보를 자동 EXCLUDED로 전환하지 않습니다. • 판정 당시 Project·Class·Team·ProjectMembership·TeamMembership·Submission 귀속을 고정하고 현재 소속 변경으로 과거 후보를 재귀속하지 않습니다. • ELIGIBLE·INTERVIEW_CREATED에서는 제외 속성이 모두 NULL입니다. • EXCLUDED에서는 exclusion_reason_code·excluded_by·excluded_at이 필수입니다. • 면담 생성 성공 시에만 INTERVIEW_CREATED로 전환하고 candidate_id UNIQUE 면담과 원자적으로 연결합니다. • 후보 제외·재포함은 row_version·request_id·잠금 재검증 후 본체와 상태 이력·AuditLog를 같은 트랜잭션으로 갱신합니다. • 활성 일치 사유가 없더라도 과거 후보 발생 사실을 삭제하지 않으며 현재 자격은 View에서 파생합니다.';

comment on column interview_candidate.candidate_id is '면담 후보 레코드를 식별하는 기본키이다.';

comment on column interview_candidate.org_id is '면담 후보가 참조하는 기관 ID 외래키 후보이다.';

comment on column interview_candidate.cohort_id is '면담 후보가 참조하는 기수 ID 외래키 후보이다.';

comment on column interview_candidate.class_id is '면담 후보가 참조하는 반 ID 외래키 후보이다.';

comment on column interview_candidate.user_id is '면담 후보가 참조하는 사용자 ID 외래키 후보이다.';

comment on column interview_candidate.project_id is '프로젝트 ID 값을 저장한다.';

comment on column interview_candidate.assessment_round_id is '면담 후보가 참조하는 평가 회차 ID 외래키 후보이다.';

comment on column interview_candidate.team_id is '팀 ID 값을 저장한다.';

comment on column interview_candidate.project_membership_id is '프로젝트 참여 ID 값을 저장한다.';

comment on column interview_candidate.team_membership_id is '팀 소속 ID 값을 저장한다.';

comment on column interview_candidate.source_submission_id is '원천 제출 ID 값을 저장한다.';

comment on column interview_candidate.status is '면담 후보의 상태 값을 기록한다.';

comment on column interview_candidate.exclusion_reason_code is '면담 후보의 제외 사유 코드 값을 기록한다.';

comment on column interview_candidate.exclusion_note is '면담 후보의 제외 메모 값을 기록한다.';

comment on column interview_candidate.excluded_by is '면담 후보가 참조하는 제외 처리자 외래키 후보이다.';

comment on column interview_candidate.excluded_at is '면담 후보의 제외 일시 값을 기록한다.';

comment on column interview_candidate.detected_at is '탐지 일시 값을 저장한다.';

comment on column interview_candidate.created_at is '생성 일시 값을 저장한다.';

comment on column interview_candidate.updated_at is '수정 일시 값을 저장한다.';

comment on column interview_candidate.row_version is '행 버전 값을 저장한다.';

alter table interview_candidate
    owner to postgres;

create table interview
(
    interview_id        uuid                     default gen_random_uuid() not null
        constraint pk_interview
            primary key,
    candidate_id        uuid                                               not null
        constraint uq_interview_candidate_id
            unique
        constraint fk_interview_candidate_id
            references interview_candidate
            on delete restrict,
    org_id              uuid                                               not null
        constraint fk_interview_org_id
            references organization
            on delete restrict,
    cohort_id           uuid                                               not null
        constraint fk_interview_cohort_id
            references cohort
            on delete restrict,
    class_id            uuid                                               not null
        constraint fk_interview_class_id
            references class
            on delete restrict,
    target_user_id      uuid                                               not null
        constraint fk_interview_target_user_id
            references app_user
            on delete restrict,
    assessment_round_id uuid                                               not null
        constraint fk_interview_assessment_round_id
            references project_assessment_round
            on delete restrict,
    assignee_id         uuid                                               not null
        constraint fk_interview_assignee_id
            references app_user
            on delete restrict,
    status              varchar(100)                                       not null
        constraint ck_interview_status
            check ((status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('COMPLETED'::character varying)::text])),
    planned_at          timestamp with time zone,
    started_at          timestamp with time zone,
    started_by          uuid
        constraint fk_interview_started_by
            references app_user
            on delete restrict,
    completed_at        timestamp with time zone,
    completed_by        uuid
        constraint fk_interview_completed_by
            references app_user
            on delete restrict,
    result_summary      text,
    created_by          uuid                                               not null
        constraint fk_interview_created_by
            references app_user
            on delete restrict,
    created_at          timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at          timestamp with time zone default CURRENT_TIMESTAMP not null,
    row_version         integer                  default 0                 not null,
    constraint ck_interview_status_2
        check ((((status)::text = 'PENDING'::text) AND (started_at IS NULL) AND (started_by IS NULL) AND
                (completed_at IS NULL) AND (completed_by IS NULL)) OR
               (((status)::text = 'IN_PROGRESS'::text) AND (started_at IS NOT NULL) AND (started_by IS NOT NULL) AND
                (completed_at IS NULL) AND (completed_by IS NULL)) OR
               (((status)::text = 'COMPLETED'::text) AND (started_at IS NOT NULL) AND (started_by IS NOT NULL) AND
                (completed_at IS NOT NULL) AND (completed_by IS NOT NULL) AND (completed_at >= started_at)))
);

comment on table interview is '매니저가 면담 후보에서 확정한 교육생 면담의 담당자·예정·시작·종결 상태를 관리하는 원장입니다. | 정의서명: Interview | 제약·비고: • InterviewCandidate 1:0..1이며 candidate_id UNIQUE입니다. • status: PENDING / IN_PROGRESS / COMPLETED • PENDING은 시작·종결 시각과 처리자가 NULL입니다. • IN_PROGRESS는 started_at·started_by가 필수이고 종결 속성은 NULL입니다. • COMPLETED는 시작·종결 시각과 started_by·completed_by가 필수입니다. • 후보가 ELIGIBLE이고 현재 자격·배정 범위가 유효할 때만 생성하며 후보 상태 INTERVIEW_CREATED와 원자적으로 커밋합니다. • 화면은 진행 중 단계를 노출하지 않고 [저장하고 종결] 한 번으로 면담을 마치므로 PENDING→COMPLETED 직행 전이를 허용합니다. IN_PROGRESS는 건너뛸 수 있는 선택 상태입니다. • 직행 종결 시에도 ck_interview_status_2의 COMPLETED 조건을 만족해야 하므로 started_at·started_by를 같은 트랜잭션에서 함께 채웁니다. started_at은 매니저가 브리프를 연 시각을 알 수 없으면 completed_at과 같은 값으로 기록하고 started_by는 completed_by와 같습니다. • 직행 종결은 InterviewStatusHistory에 PENDING→COMPLETED 1행으로 남기며 존재하지 않은 IN_PROGRESS 전이를 만들어 넣지 않습니다. • InterviewSource·InterviewBrief·InterviewActivity·InterviewCause의 부모입니다.';

comment on column interview.interview_id is '면담 레코드를 식별하는 기본키이다.';

comment on column interview.candidate_id is '후보 ID 값을 저장한다.';

comment on column interview.org_id is '면담가 참조하는 기관 ID 외래키 후보이다.';

comment on column interview.cohort_id is '면담가 참조하는 기수 ID 외래키 후보이다.';

comment on column interview.class_id is '면담가 참조하는 반 ID 외래키 후보이다.';

comment on column interview.target_user_id is '면담가 참조하는 대상 사용자 ID 외래키 후보이다.';

comment on column interview.assessment_round_id is '면담가 참조하는 평가 회차 ID 외래키 후보이다.';

comment on column interview.assignee_id is '면담가 참조하는 담당자 ID 외래키 후보이다.';

comment on column interview.status is '면담의 상태 값을 기록한다.';

comment on column interview.planned_at is '면담의 예정 일시 값을 기록한다.';

comment on column interview.started_at is '면담의 시작 일시 값을 기록한다.';

comment on column interview.started_by is '시작 처리자 ID 값을 저장한다.';

comment on column interview.completed_at is '면담의 완료 일시 값을 기록한다.';

comment on column interview.completed_by is '종결 처리자 ID 값을 저장한다.';

comment on column interview.result_summary is '면담의 결과 요약 값을 기록한다.';

comment on column interview.created_by is '생성 처리자 ID 값을 저장한다.';

comment on column interview.created_at is '생성 일시 값을 저장한다.';

comment on column interview.updated_at is '수정 일시 값을 저장한다.';

comment on column interview.row_version is '행 버전 값을 저장한다.';

alter table interview
    owner to postgres;

grant delete, insert, select, update on interview to teamiz_app;

create table interview_activity
(
    activity_id   uuid                     default gen_random_uuid() not null
        constraint pk_interview_activity
            primary key,
    interview_id  uuid                                               not null
        constraint fk_interview_activity_interview_id
            references interview
            on delete restrict,
    author_id     uuid                                               not null
        constraint fk_interview_activity_author_id
            references app_user
            on delete restrict,
    activity_type varchar(100)                                       not null,
    content       text                                               not null,
    occurred_at   timestamp with time zone                           not null,
    next_action   text,
    updated_at    timestamp with time zone default CURRENT_TIMESTAMP not null
);

comment on table interview_activity is '면담 진행 메모·확인 사항·후속 조치를 발생 시각 순으로 보존합니다. | 정의서명: InterviewActivity | 제약·비고: • Interview 1:N APPEND-ONLY 업무 기록입니다. • interview_id는 필수이며 면담과 무관한 일반 메모는 ObservationNote에 저장합니다. • 활동 본문 수정이 허용되면 AuditLog 또는 별도 변경 이력으로 전후 값을 보존합니다. • activity_type 폐쇄형 목록은 요구사항에서 별도 확정 전 임의 DB CHECK로 고정하지 않습니다. • 면담 결과 원인 분류 7종은 InterviewCause가 소유하며 activity_type·content에 코드값으로 넣지 않습니다. 이 테이블은 매니저가 타이핑하는 상세 사유(content)와 추후 계획(next_action)만 보존합니다. • 종결된 면담을 다시 열어 매니저 입력을 고치는 경우에도 기존 행을 수정하지 않고 새 행을 덧붙이며 최신 행이 현재 값입니다.';

comment on column interview_activity.activity_id is '면담 활동 레코드를 식별하는 기본키이다.';

comment on column interview_activity.interview_id is '면담 활동가 참조하는 면담 ID 외래키 후보이다.';

comment on column interview_activity.author_id is '면담 활동가 참조하는 작성자 ID 외래키 후보이다.';

comment on column interview_activity.activity_type is '면담 활동의 활동 유형 값을 기록한다.';

comment on column interview_activity.content is '면담 활동의 내용 값을 기록한다.';

comment on column interview_activity.occurred_at is '면담 활동의 발생 일시 값을 기록한다.';

comment on column interview_activity.next_action is '면담 활동의 후속 조치 값을 기록한다.';

comment on column interview_activity.updated_at is '면담 활동의 수정 일시 값을 기록한다.';

alter table interview_activity
    owner to postgres;

grant delete, insert, select, update on interview_activity to teamiz_app;

create table interview_brief
(
    brief_id                        uuid                     default gen_random_uuid() not null
        constraint pk_interview_brief
            primary key,
    interview_id                    uuid                                               not null
        constraint fk_interview_brief_interview_id
            references interview
            on delete restrict,
    org_id                          uuid                                               not null
        constraint fk_interview_brief_org_id
            references organization
            on delete restrict,
    cohort_id                       uuid                                               not null
        constraint fk_interview_brief_cohort_id
            references cohort
            on delete restrict,
    user_id                         uuid                                               not null
        constraint fk_interview_brief_user_id
            references app_user
            on delete restrict,
    assessment_round_id             uuid                                               not null
        constraint fk_interview_brief_assessment_round_id
            references project_assessment_round
            on delete restrict,
    brief_type                      varchar(100)                                       not null
        constraint ck_interview_brief_brief_type
            check ((brief_type)::text = ANY
                   (ARRAY [('STANDARD'::character varying)::text, ('INVALID_ATTEMPT'::character varying)::text])),
    version_no                      integer                                            not null
        constraint ck_interview_brief_version_no
            check (version_no > 0),
    is_first_interview              boolean                                            not null,
    brief_generation_policy_version integer                                            not null,
    status                          varchar(100)                                       not null
        constraint ck_interview_brief_status
            check ((status)::text = ANY
                   (ARRAY [('DRAFT'::character varying)::text, ('CONFIRMED'::character varying)::text, ('SUPERSEDED'::character varying)::text])),
    manager_note                    text,
    opening_remark_text             text,
    opening_remark_generated_at     timestamp with time zone,
    created_by                      uuid                                               not null
        constraint fk_interview_brief_created_by
            references app_user
            on delete restrict,
    created_at                      timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_by                      uuid
        constraint fk_interview_brief_updated_by
            references app_user
            on delete restrict,
    updated_at                      timestamp with time zone default CURRENT_TIMESTAMP not null,
    confirmed_by                    uuid
        constraint fk_interview_brief_confirmed_by
            references app_user
            on delete restrict,
    confirmed_at                    timestamp with time zone,
    row_version                     integer                  default 0                 not null,
    last_request_id                 uuid,
    last_request_fingerprint        varchar(128),
    constraint uq_interview_brief_interview_id_version_no
        unique (interview_id, version_no),
    constraint ck_interview_brief_last_request_id
        check (((last_request_id IS NULL) AND (last_request_fingerprint IS NULL)) OR
               ((last_request_id IS NOT NULL) AND (last_request_fingerprint IS NOT NULL))),
    constraint ck_interview_brief_opening_remark_text
        check (((opening_remark_text IS NULL) AND (opening_remark_generated_at IS NULL)) OR
               ((opening_remark_text IS NOT NULL) AND (opening_remark_generated_at IS NOT NULL))),
    constraint ck_interview_brief_status_2
        check ((((status)::text = 'DRAFT'::text) AND (confirmed_by IS NULL) AND (confirmed_at IS NULL)) OR
               (((status)::text = 'CONFIRMED'::text) AND (confirmed_by IS NOT NULL) AND (confirmed_at IS NOT NULL)) OR
               ((status)::text = 'SUPERSEDED'::text))
);

comment on table interview_brief is '면담 여는 말과 확인 질문 집합·첫 면담 여부·생성 정책과 저장·확정 버전을 관리합니다. Report와 독립된 INTV 원장입니다. | 정의서명: InterviewBrief | 제약·비고: • UNIQUE(interview_id, version_no), version_no > 0 • status: DRAFT / CONFIRMED / SUPERSEDED • 면담별 현재 DRAFT와 CONFIRMED는 각각 최대 1건입니다. • DRAFT는 confirmed_by·confirmed_at이 NULL이고 CONFIRMED는 둘 다 필수입니다. • 새 DRAFT 생성만으로 기존 CONFIRMED를 대체하지 않으며 새 버전 확정 성공 시에만 이전 CONFIRMED를 SUPERSEDED로 전환합니다. • PENDING 면담에서만 초기화·저장·확정을 허용하고 IN_PROGRESS·COMPLETED에서는 읽기 전용입니다. 단 PENDING→COMPLETED 직행 종결 트랜잭션 안에서는 상태 전이 직전에 마지막 확정을 허용합니다. 종결이 커밋된 뒤에는 예외 없이 읽기 전용입니다. • 면담 종결 후에는 새 DRAFT·새 버전을 만들지 않습니다. 종결된 면담을 다시 열어 수정하는 대상은 브리프(여는 말·확인 질문)가 아니라 면담 기록인 InterviewCause와 InterviewActivity이며, 브리프 버전은 종결 시점 그대로 고정됩니다. • 면담 시작 또는 직행 종결 직전 같은 면담의 CONFIRMED 브리프 정확히 1건과 선택 항목 1건 이상, 원천 정합성을 잠금 재검증합니다. • 첫 면담 여부와 브리프 생성 정책 버전을 생성 시점에 고정합니다. • 여는 말과 확인 질문은 같은 AI 호출 1회로 생성하며 AiUsage 1행과 브리프 1버전이 1:1 대응합니다. • opening_remark_text와 opening_remark_generated_at은 함께 존재하거나 함께 NULL입니다. • org_id·cohort_id·user_id·assessment_round_id는 RLS와 조회 성능을 위한 의도적 비정규화이며 Interview 경로와 일치해야 합니다. • 면담에서 난이도를 기록하지 않으므로 난이도 척도 버전을 보유하지 않습니다. • 요청 ID와 지문으로 저장·확정 멱등성을 검증하며 같은 ID의 다른 payload를 거부합니다.';

comment on column interview_brief.brief_id is '면담 준비 브리프 행을 유일하게 식별하는 기본키이다.';

comment on column interview_brief.interview_id is '면담 브리프가 참조하는 면담 ID 외래키 후보이다.';

comment on column interview_brief.org_id is '면담 준비 브리프가 참조하는 organization.org_id의 식별자이다.';

comment on column interview_brief.cohort_id is '면담 준비 브리프가 참조하는 cohort.cohort_id의 식별자이다.';

comment on column interview_brief.user_id is '면담 준비 브리프가 참조하는 app_user.user_id의 식별자이다.';

comment on column interview_brief.assessment_round_id is '면담 브리프가 참조하는 평가 회차 ID 외래키 후보이다.';

comment on column interview_brief.brief_type is '면담 브리프의 브리프 유형 값을 기록한다.';

comment on column interview_brief.version_no is '면담 브리프의 버전 번호 값을 기록한다.';

comment on column interview_brief.is_first_interview is '면담 브리프의 최초 면담 여부 값을 기록한다.';

comment on column interview_brief.brief_generation_policy_version is '브리프 생성 정책 버전 값을 저장한다.';

comment on column interview_brief.status is '면담 준비 브리프의 상태을 나타내는 코드이다. 허용값과 의미는 코드 서식 및 CHECK 제약을 따른다.';

comment on column interview_brief.manager_note is '매니저 메모 값을 저장한다.';

comment on column interview_brief.opening_remark_text is '매니저가 면담을 시작하며 건네는 도입 멘트 원문을 저장한다. AI가 생성하며 매니저는 수정하지 않는다.';

comment on column interview_brief.opening_remark_generated_at is '여는 말이 생성된 시각을 저장한다.';

comment on column interview_brief.created_by is '면담 브리프가 참조하는 생성 처리자 외래키 후보이다.';

comment on column interview_brief.created_at is '면담 브리프의 생성 일시 값을 기록한다.';

comment on column interview_brief.updated_by is '수정 처리자 ID 값을 저장한다.';

comment on column interview_brief.updated_at is '수정 일시 값을 저장한다.';

comment on column interview_brief.confirmed_by is '면담 브리프가 참조하는 확정 처리자 ID 외래키 후보이다.';

comment on column interview_brief.confirmed_at is '면담 브리프의 확정 일시 값을 기록한다.';

comment on column interview_brief.row_version is '행 버전 값을 저장한다.';

comment on column interview_brief.last_request_id is '최근 요청 ID 값을 저장한다.';

comment on column interview_brief.last_request_fingerprint is '최근 요청 지문 값을 저장한다.';

alter table interview_brief
    owner to postgres;

create unique index uq_interview_brief_current_confirmed
    on interview_brief (interview_id)
    where ((status)::text = 'CONFIRMED'::text);

create unique index uq_interview_brief_current_draft
    on interview_brief (interview_id)
    where ((status)::text = 'DRAFT'::text);

grant delete, insert, select, update on interview_brief to teamiz_app;

grant delete, insert, select, update on interview_candidate to teamiz_app;

create table interview_candidate_status_history
(
    candidate_history_id  uuid default gen_random_uuid() not null
        constraint pk_interview_candidate_status_history
            primary key,
    candidate_id          uuid                           not null
        constraint fk_interview_candidate_status_history_candidate_id
            references interview_candidate
            on delete restrict,
    from_status           varchar(100)                   not null,
    to_status             varchar(100)                   not null,
    changed_by            uuid                           not null
        constraint fk_interview_candidate_status_history_changed_by
            references app_user
            on delete restrict,
    changed_at            timestamp with time zone       not null,
    reason_code           varchar(100),
    reason_note           text,
    before_snapshot       jsonb,
    after_snapshot        jsonb,
    candidate_row_version integer                        not null,
    request_id            uuid                           not null,
    constraint uq_interview_candidate_status_history_candidate_id_request_i
        unique (candidate_id, request_id)
);

comment on table interview_candidate_status_history is '후보 제외·재포함·면담 생성 등 성공한 후보 상태 전이를 APPEND-ONLY로 보존합니다. | 정의서명: InterviewCandidateStatusHistory | 제약·비고: • InterviewCandidate 1:N APPEND-ONLY • 제외·재포함·면담 생성의 구조화 사유와 본체 전후 스냅샷을 보존합니다. • 동일 candidate_id + request_id의 중복 상태 이력 생성을 금지합니다. • 롤백된 요청과 화면 임시 선택은 저장하지 않습니다.';

comment on column interview_candidate_status_history.candidate_history_id is '면담 후보 상태 이력 레코드를 식별하는 기본키이다.';

comment on column interview_candidate_status_history.candidate_id is '면담 후보 상태 이력가 참조하는 후보 ID 외래키 후보이다.';

comment on column interview_candidate_status_history.from_status is '면담 후보 상태 이력의 변경 전 상태 값을 기록한다.';

comment on column interview_candidate_status_history.to_status is '면담 후보 상태 이력의 변경 후 상태 값을 기록한다.';

comment on column interview_candidate_status_history.changed_by is '면담 후보 상태 이력가 참조하는 변경 처리자 ID 외래키 후보이다.';

comment on column interview_candidate_status_history.changed_at is '면담 후보 상태 이력의 변경 일시 값을 기록한다.';

comment on column interview_candidate_status_history.reason_code is '사유 코드 값을 저장한다.';

comment on column interview_candidate_status_history.reason_note is '사유 메모 값을 저장한다.';

comment on column interview_candidate_status_history.before_snapshot is '변경 전 스냅샷 값을 저장한다.';

comment on column interview_candidate_status_history.after_snapshot is '변경 후 스냅샷 값을 저장한다.';

comment on column interview_candidate_status_history.candidate_row_version is '후보 행 버전 값을 저장한다.';

comment on column interview_candidate_status_history.request_id is '면담 후보 상태 이력의 요청 ID 값을 기록한다.';

alter table interview_candidate_status_history
    owner to postgres;

grant delete, insert, select, update on interview_candidate_status_history to teamiz_app;

create table interview_cause
(
    interview_cause_id uuid                     default gen_random_uuid() not null
        constraint pk_interview_cause
            primary key,
    interview_id       uuid                                               not null
        constraint fk_interview_cause_interview_id
            references interview
            on delete restrict,
    cause_code         varchar(100)                                       not null
        constraint ck_interview_cause_cause_code
            check ((cause_code)::text = ANY
                   (ARRAY [('CONCEPT_GAP'::character varying)::text, ('OUT_OF_SCOPE'::character varying)::text, ('TIME_SHORTAGE'::character varying)::text, ('EXPRESSION'::character varying)::text, ('DIFFICULTY_UP'::character varying)::text, ('TEAM_DEPENDENCE'::character varying)::text, ('CONDITION'::character varying)::text])),
    recorded_by        uuid                                               not null
        constraint fk_interview_cause_recorded_by
            references app_user
            on delete restrict,
    recorded_at        timestamp with time zone                           not null,
    created_at         timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at         timestamp with time zone default CURRENT_TIMESTAMP not null,
    row_version        integer                  default 0                 not null,
    constraint uq_interview_cause_interview_id_cause_code
        unique (interview_id, cause_code)
);

comment on table interview_cause is '면담 결과로 매니저가 선택한 원인 분류를 보존합니다. 복수 선택이며 재저장 시 갱신됩니다. | 정의서명: InterviewCause | 제약·비고: • Interview 1:N, UNIQUE(interview_id, cause_code) • cause_code: CONCEPT_GAP / OUT_OF_SCOPE / TIME_SHORTAGE / EXPRESSION / DIFFICULTY_UP / TEAM_DEPENDENCE / CONDITION • 시스템이 판정한 위험 사유(InterviewCandidateReason)와 구분됩니다 — 이쪽은 면담 후 사람이 고른 값입니다. • 브리프 재저장 시 선택 집합 전체를 대체합니다(APPEND-ONLY가 아닙니다). 저장은 같은 interview_id의 기존 행 삭제와 새 선택 삽입을 한 트랜잭션으로 처리합니다. • 선택에서 파생되는 조치(라우팅 목적지)는 화면이 계산하며 저장하지 않습니다. • 자유 서술(상세 사유·추후 계획)은 InterviewActivity가 소유합니다. • 브리프와 달리 면담 종결 후에도 기록·수정할 수 있습니다. Interview.status와 무관하게 쓰기가 허용되는 면담 기록입니다. • 선택 0건은 허용하며 행이 없는 상태로 표현합니다.';

comment on column interview_cause.interview_cause_id is '면담 원인 선택 행을 유일하게 식별하는 기본키이다.';

comment on column interview_cause.interview_id is '면담 원인이 참조하는 면담 ID 외래키 후보이다.';

comment on column interview_cause.cause_code is '매니저가 면담 결과로 선택한 원인 분류 코드 값을 기록한다.';

comment on column interview_cause.recorded_by is '면담 원인이 참조하는 기록자 ID 외래키 후보이다.';

comment on column interview_cause.recorded_at is '원인 선택을 기록한 일시 값을 저장한다.';

comment on column interview_cause.created_at is '생성 일시 값을 저장한다.';

comment on column interview_cause.updated_at is '수정 일시 값을 저장한다.';

comment on column interview_cause.row_version is '행 버전 값을 저장한다.';

alter table interview_cause
    owner to postgres;

grant delete, insert, select, update on interview_cause to teamiz_app;

create table interview_status_history
(
    status_history_id uuid default gen_random_uuid() not null
        constraint pk_interview_status_history
            primary key,
    interview_id      uuid                           not null
        constraint fk_interview_status_history_interview_id
            references interview
            on delete restrict,
    from_status       varchar(100)                   not null,
    to_status         varchar(100)                   not null,
    changed_by        uuid                           not null
        constraint fk_interview_status_history_changed_by
            references app_user
            on delete restrict,
    changed_at        timestamp with time zone       not null,
    reason            text,
    request_id        uuid                           not null,
    constraint uq_interview_status_history_interview_id_request_id
        unique (interview_id, request_id)
);

comment on table interview_status_history is '면담 시작·종결 등 성공한 상태 전이를 APPEND-ONLY로 보존합니다. | 정의서명: InterviewStatusHistory | 제약·비고: • Interview 1:N APPEND-ONLY • 동일 interview_id + request_id의 중복 상태 이력을 금지합니다. • 면담 본체 상태 갱신·이력·AuditLog를 같은 트랜잭션으로 처리합니다.';

comment on column interview_status_history.status_history_id is '면담 상태 이력 레코드를 식별하는 기본키이다.';

comment on column interview_status_history.interview_id is '면담 상태 이력가 참조하는 면담 ID 외래키 후보이다.';

comment on column interview_status_history.from_status is '면담 상태 이력의 변경 전 상태 값을 기록한다.';

comment on column interview_status_history.to_status is '면담 상태 이력의 변경 후 상태 값을 기록한다.';

comment on column interview_status_history.changed_by is '면담 상태 이력가 참조하는 변경 처리자 ID 외래키 후보이다.';

comment on column interview_status_history.changed_at is '면담 상태 이력의 변경 일시 값을 기록한다.';

comment on column interview_status_history.reason is '면담 상태 이력의 사유 값을 기록한다.';

comment on column interview_status_history.request_id is '면담 상태 이력의 요청 ID 값을 기록한다.';

alter table interview_status_history
    owner to postgres;

grant delete, insert, select, update on interview_status_history to teamiz_app;

grant delete, insert, select, update on team to teamiz_app;

create table report_generation_run
(
    generation_run_id   uuid default gen_random_uuid() not null
        constraint pk_report_generation_run
            primary key,
    report_id           uuid                           not null
        constraint fk_report_generation_run_report_id
            references report
            on delete restrict,
    trigger_type        varchar(100)                   not null,
    idempotency_key     text                           not null
        constraint uq_report_generation_run_idempotency_key
            unique,
    calculation_version integer                        not null
        constraint ck_report_generation_run_calculation_version
            check (calculation_version > 0),
    status              varchar(100)                   not null
        constraint ck_report_generation_run_status
            check ((status)::text = ANY
                   (ARRAY [('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('COMPLETED'::character varying)::text, ('PARTIAL'::character varying)::text, ('FAILED'::character varying)::text, ('RETRYING'::character varying)::text])),
    failure_reason      text,
    execution_no        integer                        not null
        constraint ck_report_generation_run_execution_no
            check (execution_no > 0),
    started_at          timestamp with time zone,
    completed_at        timestamp with time zone,
    request_fingerprint char(64)                       not null
        constraint ck_report_generation_run_request_fingerprint
            check (request_fingerprint ~ '^[0-9a-f]{64}$'::text),
    constraint ck_report_generation_run_status_2
        check ((((status)::text = 'QUEUED'::text) AND (started_at IS NULL) AND (completed_at IS NULL) AND
                (failure_reason IS NULL)) OR (((status)::text = ANY
                                               (ARRAY [('RUNNING'::character varying)::text, ('RETRYING'::character varying)::text])) AND
                                              (started_at IS NOT NULL) AND (completed_at IS NULL)) OR
               (((status)::text = ANY
                 (ARRAY [('COMPLETED'::character varying)::text, ('PARTIAL'::character varying)::text])) AND
                (started_at IS NOT NULL) AND (completed_at IS NOT NULL) AND (failure_reason IS NULL)) OR
               (((status)::text = 'FAILED'::text) AND (completed_at IS NOT NULL) AND (failure_reason IS NOT NULL)))
);

comment on table report_generation_run is '리포트 생성·재생성·재시도를 APPEND-ONLY 실행 이력으로 보존합니다. 생성 완료와 실제 발행·교육생 공개는 서로 다른 상태입니다. | 정의서명: ReportGenerationRun | 제약·비고: • 상태: QUEUED / RUNNING / COMPLETED / PARTIAL / FAILED / RETRYING • execution_no > 0이며 같은 report_id·trigger_type·업무 키 안에서 불변 증가합니다. • 재시도는 기존 행의 retry_count를 갱신하지 않고 execution_no+1 새 행으로 생성합니다. • 같은 report_id의 같은 생성 업무에서 QUEUED/RUNNING/RETRYING 활성 실행은 최대 1건입니다. • COMPLETED·PARTIAL이면 completed_at이 필수이고 FAILED이면 failure_reason이 필수입니다. • idempotency_key는 같은 요청 지문 하나에만 대응하며 동일 키·다른 지문은 거부합니다. • 원천 진행 중에는 공식 생성 실행을 만들지 않습니다. • 문제별 /reports 응답은 같은 생성 실행 안에서 버퍼링하고 모든 문제 종료 후 ReportSnapshot 1건으로 확정합니다. • reportMarkdown·narrative·versions(modelCode·promptVersion·rubricVersion)는 생성 실행 컬럼에 중복 저장하지 않고 ReportSnapshot.summary_payload에 보존합니다. • scheduled_publish_at·published_at·교육생 공개 상태를 저장하지 않습니다.';

comment on column report_generation_run.generation_run_id is '리포트 생성·재시도 실행 이력의 개별 레코드를 식별하는 고유 키이다.';

comment on column report_generation_run.report_id is '리포트와의 업무 관계를 연결하는 외래 키이다.';

comment on column report_generation_run.trigger_type is '리포트 생성·재시도 실행 이력에서 관리하는 트리거유형 정보이다.';

comment on column report_generation_run.idempotency_key is '리포트 생성 실행 생성 요청의 중복 처리를 방지하는 멱등성 키이다.';

comment on column report_generation_run.calculation_version is '리포트 생성 실행 산출에 사용한 계산 버전을 기록하여 재현성과 감사 가능성을 보장한다.';

comment on column report_generation_run.status is '리포트 생성·재시도 실행 이력의 현재 업무 처리 상태를 나타낸다.';

comment on column report_generation_run.failure_reason is '리포트 생성 실행의 실패 사유 내용을 기록한다.';

comment on column report_generation_run.execution_no is '실행 순번 값을 저장한다.';

comment on column report_generation_run.started_at is '업무 처리가 시작된 시각이다.';

comment on column report_generation_run.completed_at is '리포트 생성·재시도 실행 이력에서 관리하는 완료일시 정보이다.';

comment on column report_generation_run.request_fingerprint is '동일 idempotency_key에 대한 요청 동일성 판별용 SHA-256 지문이다.';

alter table report_generation_run
    owner to postgres;

create unique index uq_report_generation_run_active
    on report_generation_run (report_id, trigger_type)
    where ((status)::text = ANY
           (ARRAY [('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('RETRYING'::character varying)::text]));

grant delete, insert, select, update on report_generation_run to teamiz_app;

create table report_snapshot
(
    snapshot_id            uuid    default gen_random_uuid() not null
        constraint pk_report_snapshot
            primary key,
    org_id                 uuid                              not null
        constraint fk_report_snapshot_org_id
            references organization
            on delete restrict,
    report_id              uuid                              not null
        constraint fk_report_snapshot_report_id
            references report
            on delete restrict,
    snapshot_version       integer                           not null
        constraint ck_report_snapshot_snapshot_version
            check (snapshot_version > 0),
    as_of_at               timestamp with time zone          not null,
    calculation_version    integer                           not null
        constraint ck_report_snapshot_calculation_version
            check (calculation_version > 0),
    summary_payload        jsonb                             not null,
    completion_status      varchar(100)                      not null
        constraint ck_report_snapshot_completion_status
            check ((completion_status)::text = ANY
                   (ARRAY [('FULL'::character varying)::text, ('PARTIAL'::character varying)::text])),
    sample_count           integer default 0                 not null
        constraint ck_report_snapshot_sample_count
            check (sample_count >= 0),
    missing_count          integer default 0                 not null,
    payload_hash           varchar(128)                      not null,
    is_active              boolean                           not null,
    generation_run_id      uuid                              not null
        constraint uq_report_snapshot_generation_run_id
            unique
        constraint fk_report_snapshot_generation_run_id
            references report_generation_run
            on delete restrict,
    payload_schema_version integer default 1                 not null
        constraint ck_report_snapshot_payload_schema_version
            check (payload_schema_version > 0),
    retry_target_count     integer default 0                 not null,
    constraint uq_report_snapshot_report_id_snapshot_version
        unique (report_id, snapshot_version),
    constraint ck_report_snapshot_missing_count
        check ((missing_count >= 0) AND (missing_count <= sample_count)),
    constraint ck_report_snapshot_retry_target_count
        check ((retry_target_count >= 0) AND (retry_target_count <= sample_count))
);

comment on table report_snapshot is '발행 가능한 집계 결과와 교육생 표시 문구를 계산 기준 시각·버전별로 불변 보존하는 스냅샷입니다. | 정의서명: ReportSnapshot | 제약·비고: • completion_status: FULL / PARTIAL • snapshot_version > 0, UNIQUE(report_id, snapshot_version) • 리포트별 is_active=TRUE 스냅샷은 최대 1건입니다. • sample_count·missing_count는 0 이상입니다. • as_of_at은 포함 원천 데이터 기준 종료 시각이며 생성 완료·발행 시각과 다릅니다. • generation_run_id로 실제 생성 실행과 1:1 연결하며 payload_schema_version으로 JSON 계약 버전을 고정합니다. • summary_payload는 reportMarkdown, narrative(summary·strengths·gaps·autonomyNote), versions(modelCode·promptVersion·rubricVersion)를 구조화하여 보존합니다. • 발행되거나 활성화된 스냅샷은 수정하지 않고 새 버전으로 교체합니다. • AI 응답 narrativeFailed=TRUE이면 completion_status=''PARTIAL''로 저장합니다. • PARTIAL은 중단·결측을 포함한 내부·매니저 결과에 사용할 수 있으나 교육생 상세 자동 공개를 금지합니다. • 교육생 개인 스냅샷은 발행 당시 개념명·표시 순서·학생용 요약·확인 축을 고정합니다. • 질문·답변 원문은 중복 저장하지 않고 ProblemStage를 참조합니다. • status·final_grade·curriculum_version_id·source_score_version을 저장하지 않습니다.';

comment on column report_snapshot.snapshot_id is '불변 집계 결과·화면 조회 스냅샷의 개별 레코드를 식별하는 고유 키이다.';

comment on column report_snapshot.org_id is '기관 ID 값을 저장한다.';

comment on column report_snapshot.report_id is '리포트와의 업무 관계를 연결하는 외래 키이다.';

comment on column report_snapshot.snapshot_version is '리포트 스냅샷 산출에 사용한 스냅샷 버전을 기록하여 재현성과 감사 가능성을 보장한다.';

comment on column report_snapshot.as_of_at is '불변 집계 결과·화면 조회 스냅샷에서 관리하는 기준일시 정보이다.';

comment on column report_snapshot.calculation_version is '리포트 스냅샷 산출에 사용한 계산 버전을 기록하여 재현성과 감사 가능성을 보장한다.';

comment on column report_snapshot.summary_payload is '리포트 발행본의 구조화된 표시 콘텐츠를 저장한다. reportMarkdown, narrative.summary·strengths·gaps·autonomyNote, versions.modelCode·promptVersion·rubricVersion을 포함한다.';

comment on column report_snapshot.completion_status is '완성 상태 값을 저장한다.';

comment on column report_snapshot.sample_count is '리포트 스냅샷의 표본 수을 수치로 기록한다.';

comment on column report_snapshot.missing_count is '리포트 스냅샷의 결측 수을 수치로 기록한다.';

comment on column report_snapshot.payload_hash is '요약 페이로드 내용의 무결성을 검증하는 해시 값이다.';

comment on column report_snapshot.is_active is '리포트 스냅샷의 활성 여부 값을 기록한다.';

comment on column report_snapshot.generation_run_id is '해당 불변 리포트 스냅샷을 생성한 리포트 생성 실행을 식별한다.';

comment on column report_snapshot.payload_schema_version is 'summary_payload JSON 구조의 스키마 버전을 기록한다.';

comment on column report_snapshot.retry_target_count is '이 스냅샷에서 다시 보기 대상인 개념 수이다. 도달 단계 2단 미만이며 발행 시점에 확정한다. 2026-08-21: 컬럼+제약만 선적용, 백필은 evidence_category 판정 기준 확인 후 별도 실행 예정.';

alter table report_snapshot
    owner to postgres;

create table report_metric
(
    metric_id                       uuid    default gen_random_uuid() not null
        constraint pk_report_metric
            primary key,
    snapshot_id                     uuid                              not null
        constraint fk_report_metric_snapshot_id
            references report_snapshot
            on delete restrict,
    section_code                    varchar(100)                      not null
        constraint ck_report_metric_section_code
            check ((section_code)::text = ANY
                   (ARRAY [('CURRICULUM_DIAGNOSIS_SUMMARY'::character varying)::text, ('CONCEPT_REACH_DISTRIBUTION'::character varying)::text, ('ROUND_CONCEPT_RESULT'::character varying)::text, ('COHORT_OUTCOME_SUMMARY'::character varying)::text, ('COHORT_GROWTH_TRANSITION'::character varying)::text, ('EXCELLENT_TRAINEE'::character varying)::text, ('CLASS_RISK'::character varying)::text, ('GROUP_UNDERPERFORMANCE'::character varying)::text])),
    metric_grain_code               varchar(100)                      not null
        constraint ck_report_metric_metric_grain_code
            check ((metric_grain_code)::text = ANY
                   (ARRAY [('REPORT'::character varying)::text, ('CURRICULUM'::character varying)::text, ('CONCEPT'::character varying)::text, ('CONCEPT_REACHED_LEVEL'::character varying)::text, ('ROUND'::character varying)::text, ('ROUND_CONCEPT'::character varying)::text, ('ROUND_CONCEPT_REACHED_LEVEL'::character varying)::text, ('TRANSITION_CATEGORY'::character varying)::text, ('CLASS'::character varying)::text, ('CLASS_CONCEPT'::character varying)::text])),
    metric_code                     varchar(100)                      not null,
    dimension_key                   text,
    dimension_value                 numeric(18, 6),
    project_id                      uuid
        constraint fk_report_metric_project_id
            references project
            on delete restrict,
    source_assessment_round_id      uuid
        constraint fk_report_metric_source_assessment_round_id
            references project_assessment_round
            on delete restrict,
    project_verification_concept_id uuid
        constraint fk_report_metric_project_verification_concept_id
            references project_verification_concept
            on delete restrict,
    class_id                        uuid
        constraint fk_report_metric_class_id
            references class
            on delete restrict,
    curriculum_material_id          uuid
        constraint fk_report_metric_curriculum_material_id
            references curriculum_material
            on delete restrict,
    curriculum_version_id           uuid
        constraint fk_report_metric_curriculum_version_id
            references curriculum_version
            on delete restrict,
    curriculum_section_id           uuid
        constraint fk_report_metric_curriculum_section_id
            references curriculum_section
            on delete restrict,
    teaches_id                      uuid
        constraint fk_report_metric_teaches_id
            references teaches
            on delete restrict,
    reached_level                   integer
        constraint ck_report_metric_reached_level
            check ((reached_level IS NULL) OR ((reached_level >= 0) AND (reached_level <= 4))),
    metric_value                    numeric(18, 6),
    numerator                       numeric(18, 6)
        constraint ck_report_metric_numerator
            check ((numerator IS NULL) OR (numerator >= (0)::numeric)),
    denominator                     numeric(18, 6)
        constraint ck_report_metric_denominator
            check ((denominator IS NULL) OR (denominator >= (0)::numeric)),
    missing_count                   integer default 0
        constraint ck_report_metric_missing_count
            check (missing_count >= 0),
    policy_version                  integer
        constraint ck_report_metric_policy_version
            check ((policy_version IS NULL) OR (policy_version > 0)),
    aggregation_status              varchar(20)
        constraint ck_report_metric_aggregation_status
            check ((aggregation_status)::text = ANY
                   (ARRAY [('SINGLE_SOURCE'::character varying)::text, ('MULTIPLE_SOURCE_ROUNDS'::character varying)::text, ('CALCULATED'::character varying)::text, ('POLICY_REQUIRED'::character varying)::text, ('FAILED'::character varying)::text])),
    aggregation_policy_version      integer
        constraint ck_report_metric_aggregation_policy_version
            check ((aggregation_policy_version IS NULL) OR (aggregation_policy_version > 0)),
    policy_parameter_snapshot       jsonb,
    dimension_snapshot              jsonb,
    display_order                   integer
        constraint ck_report_metric_display_order
            check ((display_order IS NULL) OR (display_order > 0)),
    source_snapshot_version         integer                           not null
        constraint ck_report_metric_source_snapshot_version
            check (source_snapshot_version > 0),
    display_scope                   varchar(100)                      not null
        constraint ck_report_metric_display_scope
            check ((display_scope)::text = ANY
                   (ARRAY [('TRAINEE'::character varying)::text, ('MANAGER'::character varying)::text, ('OPERATOR'::character varying)::text, ('INTERNAL'::character varying)::text])),
    constraint uq_report_metric_grain
        unique (snapshot_id, section_code, metric_grain_code, metric_code, project_id, source_assessment_round_id,
                project_verification_concept_id, class_id, curriculum_material_id, curriculum_version_id,
                curriculum_section_id, teaches_id, reached_level, dimension_key),
    constraint ck_report_metric_metric_grain_code_2
        check ((((metric_grain_code)::text = 'REPORT'::text) AND
                (num_nonnulls(project_id, source_assessment_round_id, project_verification_concept_id, class_id,
                              curriculum_material_id, curriculum_version_id, curriculum_section_id, teaches_id,
                              reached_level) = 0)) OR
               (((metric_grain_code)::text = 'CURRICULUM'::text) AND (curriculum_version_id IS NOT NULL)) OR
               (((metric_grain_code)::text = ANY
                 (ARRAY [('CONCEPT'::character varying)::text, ('CONCEPT_REACHED_LEVEL'::character varying)::text])) AND
                (num_nonnulls(project_verification_concept_id, teaches_id) = 1)) OR
               (((metric_grain_code)::text = 'ROUND'::text) AND (source_assessment_round_id IS NOT NULL)) OR
               (((metric_grain_code)::text = ANY
                 (ARRAY [('ROUND_CONCEPT'::character varying)::text, ('ROUND_CONCEPT_REACHED_LEVEL'::character varying)::text])) AND
                (source_assessment_round_id IS NOT NULL) AND
                (num_nonnulls(project_verification_concept_id, teaches_id) = 1)) OR
               (((metric_grain_code)::text = 'TRANSITION_CATEGORY'::text) AND (dimension_key IS NOT NULL)) OR
               (((metric_grain_code)::text = 'CLASS'::text) AND (class_id IS NOT NULL)) OR
               (((metric_grain_code)::text = 'CLASS_CONCEPT'::text) AND (class_id IS NOT NULL) AND
                (num_nonnulls(project_verification_concept_id, teaches_id) = 1)))
);

comment on table report_metric is '리포트 스냅샷의 수업 진단·단계 분포·성장·반 위험·집단 미달·결측 지표를 명시적 섹션·grain·차원 FK와 정책 스냅샷으로 보존합니다. | 정의서명: ReportMetric | 제약·비고: • display_scope: TRAINEE / MANAGER / OPERATOR / INTERNAL • 수업 진단 section_code: CURRICULUM_DIAGNOSIS_SUMMARY / CONCEPT_REACH_DISTRIBUTION / ROUND_CONCEPT_RESULT • 기수 결산 section_code: COHORT_OUTCOME_SUMMARY / COHORT_GROWTH_TRANSITION / EXCELLENT_TRAINEE / CLASS_RISK / GROUP_UNDERPERFORMANCE • metric_grain_code: REPORT / CURRICULUM / CONCEPT / CONCEPT_REACHED_LEVEL / ROUND / ROUND_CONCEPT / ROUND_CONCEPT_REACHED_LEVEL / TRANSITION_CATEGORY / CLASS / CLASS_CONCEPT • 문자열 이름만으로 차원을 식별하지 않고 가능한 경우 정식 FK를 사용합니다. • 발행 당시 표시 이름·순서는 dimension_snapshot에 고정합니다. • 단계 분포 denominator는 L0~L4 합계와 일치해야 하며 저단계 수는 L0+L1+L2로 계산합니다. • 정책 경계·임계치는 policy_parameter_snapshot에 고정합니다. • 공식 결과 지표는 INITIAL 수행만 사용하고 미응시·무효·중단·기술 결측을 별도 결측으로 보존합니다. • metric_value·denominator·missing_count는 아직 미계산 또는 집계 실패 시 NULL을 ';

comment on column report_metric.metric_id is '리포트 개별 지표의 개별 레코드를 식별하는 고유 키이다.';

comment on column report_metric.snapshot_id is '스냅샷와의 업무 관계를 연결하는 외래 키이다.';

comment on column report_metric.section_code is '섹션 코드 값을 저장한다.';

comment on column report_metric.metric_grain_code is '지표 grain 코드 값을 저장한다.';

comment on column report_metric.metric_code is '리포트 지표의 지표 코드을 나타내는 코드이다. 허용값과 의미는 코드 서식 및 CHECK 제약을 따른다.';

comment on column report_metric.dimension_key is '차원 키 값을 저장한다.';

comment on column report_metric.dimension_value is '차원 값 값을 저장한다.';

comment on column report_metric.project_id is '프로젝트 ID 값을 저장한다.';

comment on column report_metric.source_assessment_round_id is '원천 평가 회차 ID 값을 저장한다.';

comment on column report_metric.project_verification_concept_id is '프로젝트 검증 개념 ID 값을 저장한다.';

comment on column report_metric.class_id is '반 ID 값을 저장한다.';

comment on column report_metric.curriculum_material_id is '교안 ID 값을 저장한다.';

comment on column report_metric.curriculum_version_id is '교안 버전 ID 값을 저장한다.';

comment on column report_metric.curriculum_section_id is '교안 구간 ID 값을 저장한다.';

comment on column report_metric.teaches_id is '개념 ID 값을 저장한다.';

comment on column report_metric.reached_level is '도달 단계 값을 저장한다.';

comment on column report_metric.metric_value is '리포트 개별 지표에서 관리하는 지표값 정보이다.';

comment on column report_metric.numerator is '리포트 지표 업무에서 사용하는 분자 값이다.';

comment on column report_metric.denominator is '리포트 개별 지표에서 관리하는 분모 정보이다.';

comment on column report_metric.missing_count is '리포트 지표의 결측 수을 수치로 기록한다.';

comment on column report_metric.policy_version is '정책 버전 값을 저장한다.';

comment on column report_metric.aggregation_status is '집계 상태 값을 저장한다.';

comment on column report_metric.aggregation_policy_version is '집계 정책 버전 값을 저장한다.';

comment on column report_metric.policy_parameter_snapshot is '정책 파라미터 스냅샷 값을 저장한다.';

comment on column report_metric.dimension_snapshot is '차원 표시 스냅샷 값을 저장한다.';

comment on column report_metric.display_order is '표시 순서 값을 저장한다.';

comment on column report_metric.source_snapshot_version is '리포트 지표 산출에 사용한 원천 스냅샷 버전을 기록하여 재현성과 감사 가능성을 보장한다.';

comment on column report_metric.display_scope is '리포트 지표의 표시 범위을 나타내는 코드이다. 허용값과 의미는 코드 서식 및 CHECK 제약을 따른다.';

alter table report_metric
    owner to postgres;

grant delete, insert, select, update on report_metric to teamiz_app;

create unique index uq_report_snapshot_active
    on report_snapshot (report_id)
    where (is_active = true);

grant delete, insert, select, update on report_snapshot to teamiz_app;

create table project_requirement
(
    requirement_id  uuid                     default gen_random_uuid() not null
        constraint pk_project_requirement
            primary key,
    project_id      uuid                                               not null
        constraint fk_project_requirement_project_id
            references project
            on delete restrict,
    org_id          uuid                                               not null
        constraint fk_project_requirement_org_id
            references organization
            on delete restrict,
    requirement_key varchar(100)                                       not null,
    version_no      integer                                            not null
        constraint ck_project_requirement_version_no
            check (version_no > 0),
    sequence_no     integer                                            not null
        constraint ck_project_requirement_sequence_no
            check (sequence_no > 0),
    title           varchar(200)                                       not null,
    description     text                                               not null,
    active          boolean                  default true              not null,
    effective_from  timestamp with time zone default CURRENT_TIMESTAMP not null,
    effective_to    timestamp with time zone,
    created_by      uuid                                               not null
        constraint fk_project_requirement_created_by
            references app_user
            on delete restrict,
    created_at      timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at      timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint uq_project_requirement_project_id_requirement_key_version_no
        unique (project_id, requirement_key, version_no),
    constraint ck_project_requirement_effective_to
        check ((effective_to IS NULL) OR (effective_to > effective_from))
);

comment on table project_requirement is '프로젝트 생성·구성에서 입력하는 구현 요구사항을 버전별로 보존합니다. 교안 개념과 PLAN 질문 초점과는 별개의 P/F 판정 대상이며 과거 판정이 참조한 버전은 수정·삭제하지 않습니다. | 정의서명: ProjectRequirement | 제약·비고: • version_no > 0, sequence_no > 0 • UNIQUE(project_id, requirement_key, version_no) • 같은 project_id + requirement_key의 현재 활성 버전은 최대 1건입니다. • active=TRUE이면 일반적으로 effective_to IS NULL이고 종료 버전은 effective_to가 effective_from보다 이후여야 합니다. • 요구사항 변경은 기존 행 수정 대신 기존 활성 버전 종료와 신규 version_no 생성을 한 트랜잭션으로 처리합니다. • ProjectRequirementAssessment가 존재하는 버전은 내용 수정·물리 삭제를 금지합니다. • 요구사항은 교안·검증 개념·ProjectAssessmentRound 질문 초점과 분리합니다.';

comment on column project_requirement.requirement_id is '요구사항 버전 행을 식별하는 기본키이다.';

comment on column project_requirement.project_id is '요구사항이 속한 프로젝트이다.';

comment on column project_requirement.org_id is '요구사항의 기관 경계이다.';

comment on column project_requirement.requirement_key is '동일 논리 요구사항의 버전을 묶는 안정 키이다.';

comment on column project_requirement.version_no is '요구사항 키별 증가하는 버전 번호이다.';

comment on column project_requirement.sequence_no is '프로젝트 내 요구사항 표시 순서이다.';

comment on column project_requirement.title is '요구사항 제목이다.';

comment on column project_requirement.description is '요구사항의 상세 설명이다.';

comment on column project_requirement.active is '현재 활성 요구사항 버전 여부이다.';

comment on column project_requirement.effective_from is '요구사항 버전 적용 시작 시각이다.';

comment on column project_requirement.effective_to is '요구사항 버전 적용 종료 시각이다.';

comment on column project_requirement.created_by is '요구사항 버전 생성자를 참조한다.';

comment on column project_requirement.created_at is '요구사항 버전 생성 시각이다.';

comment on column project_requirement.updated_at is '요구사항 행의 상태·종료 시각 최근 변경 시각이다.';

alter table project_requirement
    owner to postgres;

create unique index uq_project_requirement_active_version
    on project_requirement (project_id, requirement_key)
    where (active = true);

grant delete, insert, select, update on project_requirement to teamiz_app;

create table user_invitation
(
    invitation_id              uuid                     default gen_random_uuid()            not null
        constraint pk_user_invitation
            primary key,
    org_id                     uuid
        constraint fk_user_invitation_org_id
            references organization
            on delete restrict,
    target_email               citext                                                        not null,
    target_email_normalized    varchar(320)                                                  not null,
    target_role_code           varchar(30)                                                   not null
        constraint ck_user_invitation_target_role_code
            check ((target_role_code)::text = ANY
                   (ARRAY [('SUPER_ADMIN'::character varying)::text, ('OPERATOR'::character varying)::text, ('MANAGER'::character varying)::text, ('TRAINEE'::character varying)::text])),
    target_cohort_id           uuid
        constraint fk_user_invitation_target_cohort_id
            references cohort
            on delete restrict,
    target_class_id            uuid
        constraint fk_user_invitation_target_class_id
            references class
            on delete restrict,
    status                     varchar(30)              default 'PENDING'::character varying not null
        constraint ck_user_invitation_status
            check ((status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('SENT'::character varying)::text, ('DELIVERY_FAILED'::character varying)::text, ('ACCEPTED'::character varying)::text, ('EXPIRED'::character varying)::text, ('CANCELLED'::character varying)::text])),
    invited_by                 uuid                                                          not null
        constraint fk_user_invitation_invited_by
            references app_user
            on delete restrict,
    invited_at                 timestamp with time zone default CURRENT_TIMESTAMP            not null,
    sent_at                    timestamp with time zone,
    accepted_at                timestamp with time zone,
    expired_at                 timestamp with time zone,
    cancelled_at               timestamp with time zone,
    cancelled_by               uuid
        constraint fk_user_invitation_cancelled_by
            references app_user
            on delete restrict,
    failure_stage              varchar(30)
        constraint ck_user_invitation_failure_stage
            check ((failure_stage)::text = ANY
                   (ARRAY [('TOKEN_GENERATION'::character varying)::text, ('MAIL_DELIVERY'::character varying)::text])),
    failure_code               varchar(100)
        constraint ck_user_invitation_failure_code
            check ((failure_code)::text = ANY
                   (ARRAY [('INVITE_TOKEN_FAILED'::character varying)::text, ('INVITE_MAIL_FAILED'::character varying)::text])),
    failure_reason             text,
    failed_at                  timestamp with time zone,
    current_token_id           uuid,
    accepted_user_id           uuid
        constraint fk_user_invitation_accepted_user_id
            references app_user
            on delete restrict,
    resend_count               integer                  default 0                            not null
        constraint ck_user_invitation_resend_count
            check (resend_count >= 0),
    last_resend_at             timestamp with time zone,
    create_idempotency_key     uuid
        constraint uq_user_invitation_create_idempotency_key
            unique,
    create_request_fingerprint char(64)
        constraint ck_user_invitation_create_request_fingerprint
            check ((create_request_fingerprint IS NULL) OR (create_request_fingerprint ~ '^[0-9a-f]{64}$'::text)),
    created_at                 timestamp with time zone default CURRENT_TIMESTAMP            not null,
    updated_at                 timestamp with time zone default CURRENT_TIMESTAMP            not null,
    batch_request_id           text,
    mail_claimed_at            timestamp with time zone,
    constraint ck_user_invitation_create_idempotency_key
        check (((create_idempotency_key IS NULL) AND (create_request_fingerprint IS NULL)) OR
               ((create_idempotency_key IS NOT NULL) AND (create_request_fingerprint IS NOT NULL))),
    constraint ck_user_invitation_org_id
        check ((((target_role_code)::text = 'SUPER_ADMIN'::text) AND (org_id IS NULL)) OR
               (((target_role_code)::text <> 'SUPER_ADMIN'::text) AND (org_id IS NOT NULL))),
    constraint ck_user_invitation_target_class_id
        check ((((target_role_code)::text = 'MANAGER'::text) AND
                ((target_class_id IS NULL) OR (target_cohort_id IS NOT NULL))) OR (((target_role_code)::text = ANY
                                                                                    (ARRAY [('SUPER_ADMIN'::character varying)::text, ('OPERATOR'::character varying)::text, ('TRAINEE'::character varying)::text])) AND
                                                                                   (target_class_id IS NULL))),
    constraint ck_user_invitation_target_cohort_id
        check ((((target_role_code)::text = ANY
                 (ARRAY [('SUPER_ADMIN'::character varying)::text, ('OPERATOR'::character varying)::text])) AND
                (target_cohort_id IS NULL)) OR (((target_role_code)::text = ANY
                                                 (ARRAY [('MANAGER'::character varying)::text, ('TRAINEE'::character varying)::text])) AND
                                                (target_cohort_id IS NOT NULL))),
    constraint ck_user_invitation_updated_at
        check ((((status)::text = 'SENT'::text) AND (sent_at IS NOT NULL)) OR ((status)::text <> 'SENT'::text)),
    constraint ck_user_invitation_updated_at_2
        check ((((status)::text = 'ACCEPTED'::text) AND (accepted_at IS NOT NULL) AND (accepted_user_id IS NOT NULL)) OR
               ((status)::text <> 'ACCEPTED'::text)),
    constraint ck_user_invitation_updated_at_3
        check ((((status)::text = 'EXPIRED'::text) AND (expired_at IS NOT NULL)) OR
               ((status)::text <> 'EXPIRED'::text)),
    constraint ck_user_invitation_updated_at_4
        check ((((status)::text = 'CANCELLED'::text) AND (cancelled_at IS NOT NULL) AND (cancelled_by IS NOT NULL)) OR
               ((status)::text <> 'CANCELLED'::text)),
    constraint ck_user_invitation_updated_at_5
        check ((((status)::text = 'DELIVERY_FAILED'::text) AND ((failure_stage)::text = 'MAIL_DELIVERY'::text) AND
                ((failure_code)::text = 'INVITE_MAIL_FAILED'::text) AND (failed_at IS NOT NULL)) OR
               ((status)::text <> 'DELIVERY_FAILED'::text)),
    constraint ck_user_invitation_updated_at_6
        check ((((failure_stage)::text = 'TOKEN_GENERATION'::text) AND ((status)::text = 'PENDING'::text) AND
                ((failure_code)::text = 'INVITE_TOKEN_FAILED'::text) AND (failed_at IS NOT NULL)) OR
               ((failure_stage)::text IS DISTINCT FROM 'TOKEN_GENERATION'::text))
);

comment on table user_invitation is '슈퍼어드민·오퍼레이터·매니저·교육생 초대 한 건의 대상 기관·이메일·역할·기수/반 범위와 발송·실패·수락·취소 상태를 보존하는 업무 원장입니다. 토큰 재발급이 반복되어도 초대 행은 유지하고 현재 사용 가능한 토큰만 교체합니다. | 정의서명: UserInvitation | 제약·비고: • 대상 역할: SUPER_ADMIN / OPERATOR / MANAGER / TRAINEE • SUPER_ADMIN은 기관에 소속되지 않으므로 org_id·target_cohort_id·target_class_id가 모두 NULL이며, org_id는 SUPER_ADMIN일 때만 NULL이고 그 외 역할은 필수입니다. • 상태: PENDING / SENT / DELIVERY_FAILED / ACCEPTED / EXPIRED / CANCELLED • 동일 org_id·target_email_normalized·target_role_code의 미완료 초대는 최대 1건이며 org_id가 NULL인 SUPER_ADMIN 초대도 NULLS NOT DISTINCT로 동일하게 제한합니다. • 초대 상태는 UserInvitation이, 토큰 사용 가능 여부는 OneTimeToken이 각각 소유합니다. • SUPER_ADMIN·OPERATOR는 target_cohort_id·target_class_id가 모두 NULL입니다. • MANAGER는 target_cohort_id가 필수이고 target_class_id는 선택값입니다. 반이 없으면 ACTIVE·미배정 계정으로 가입합니다. • TRAINEE는 target_cohort_id가 필수이고 target_class_id는 NULL이며 명단의 PENDING 계정과 기관·기수·정규화 이메일 경로가 일치해야 합니다. • PENDING에서 토큰 생성 실패가 발생하면 failure_stage=TOKEN_GENERATION, failure_code=INVITE_TOKEN_FAILED, current_token_id=NULL을 보존합니다. • DELIVERY_FAILED이면 failure_stage=MAIL_DELIVERY, failure_code=INVITE_MAIL_FAILED, failure_reason·failed_a';

comment on column user_invitation.invitation_id is '사용자 초대 업무 원장의 기본키이다.';

comment on column user_invitation.org_id is '초대 대상 기관이다. SUPER_ADMIN 초대는 기관에 소속되지 않으므로 NULL이고 그 외 역할은 필수이다.';

comment on column user_invitation.target_email is '초대 메일을 발송할 대상 이메일이다.';

comment on column user_invitation.target_email_normalized is '미완료 초대 중복 판정용 정규화 이메일이다.';

comment on column user_invitation.target_role_code is '초대 완료 후 부여할 역할 코드이다. app_user.role_code와 동일한 값 집합을 가진다.';

comment on column user_invitation.target_cohort_id is '초대 시 지정하는 선택적 대상 기수이다.';

comment on column user_invitation.target_class_id is '초대 시 지정하는 선택적 대상 반이다.';

comment on column user_invitation.status is '초대 업무 상태이다.';

comment on column user_invitation.invited_by is '초대를 생성한 사용자이다.';

comment on column user_invitation.invited_at is '초대 업무가 생성된 시각이다.';

comment on column user_invitation.sent_at is '가장 최근 초대 메일 발송 성공 시각이다.';

comment on column user_invitation.accepted_at is '초대가 수락된 시각이다.';

comment on column user_invitation.expired_at is '초대 업무가 만료 상태가 된 시각이다.';

comment on column user_invitation.cancelled_at is '초대 업무가 취소된 시각이다.';

comment on column user_invitation.cancelled_by is '초대를 취소한 사용자이다.';

comment on column user_invitation.failure_stage is '초대 처리 실패가 발생한 단계를 나타낸다.';

comment on column user_invitation.failure_code is '최근 초대 처리 실패의 안정 코드이다.';

comment on column user_invitation.failure_reason is '최근 초대 처리 실패의 상세 사유이다.';

comment on column user_invitation.failed_at is '최근 초대 처리 실패 시각이다.';

comment on column user_invitation.current_token_id is '현재 초대 링크에 사용되는 토큰이다.';

comment on column user_invitation.accepted_user_id is '초대를 수락하여 생성·활성화된 사용자이다.';

comment on column user_invitation.resend_count is '초대 메일 재발송 성공·시도 횟수이다.';

comment on column user_invitation.last_resend_at is '동일 초대 원장에서 마지막으로 재발송을 시도한 시각이다.';

comment on column user_invitation.create_idempotency_key is '초대 생성 요청을 멱등 처리하는 사용자 의도 식별자이다.';

comment on column user_invitation.create_request_fingerprint is '정규화한 초대 생성 요청의 SHA-256 지문이다.';

comment on column user_invitation.created_at is '초대 원장 생성 시각이다.';

comment on column user_invitation.updated_at is '초대 상태·발송 정보 최근 수정 시각이다.';

comment on column user_invitation.batch_request_id is 'CSV·직접 입력 일괄 등록 1건을 묶는 키이며 진행률 폴링의 식별자로 씁니다. 단건 초대(슈퍼어드민·오퍼레이터·매니저)는 NULL입니다. X-Request-Id 헤더값이며 생략 시 서버가 만들어 응답에 담습니다.';

comment on column user_invitation.mail_claimed_at is '아웃박스 스케줄러가 이 초대의 발송을 집은 시각입니다. 인스턴스가 여러 대라 클레임 없이 PENDING을 조회하면 같은 초대가 중복 발송됩니다. 발송 중 인스턴스가 죽으면 이 값이 갱신되지 않은 채 남아 스톨 감지를 겸합니다.';

alter table user_invitation
    owner to postgres;

create index ix_user_invitation_batch_request_id
    on user_invitation (batch_request_id)
    where (batch_request_id IS NOT NULL);

create index ix_user_invitation_mail_outbox
    on user_invitation (mail_claimed_at, invited_at)
    where ((status)::text = 'PENDING'::text);

create unique index uq_user_invitation_accepted_user_not_null
    on user_invitation (accepted_user_id)
    where (accepted_user_id IS NOT NULL);

create unique index uq_user_invitation_current_token_not_null
    on user_invitation (current_token_id)
    where (current_token_id IS NOT NULL);

create unique index uq_user_invitation_incomplete
    on user_invitation (org_id, target_email_normalized, target_role_code)
    where ((status)::text = ANY
           (ARRAY [('PENDING'::character varying)::text, ('SENT'::character varying)::text, ('DELIVERY_FAILED'::character varying)::text, ('EXPIRED'::character varying)::text]));

grant delete, insert, select, update on user_invitation to teamiz_app;

create table observation_note
(
    note_id     uuid                     default gen_random_uuid() not null
        constraint pk_observation_note
            primary key,
    org_id      uuid                                               not null
        constraint fk_observation_note_org_id
            references organization
            on delete restrict,
    cohort_id   uuid                                               not null
        constraint fk_observation_note_cohort_id
            references cohort
            on delete restrict,
    user_id     uuid                                               not null
        constraint fk_observation_note_user_id
            references app_user
            on delete restrict,
    author_id   uuid                                               not null
        constraint fk_observation_note_author_id
            references app_user
            on delete restrict,
    content     text                                               not null,
    occurred_at timestamp with time zone                           not null,
    visibility  varchar(30)                                        not null
        constraint ck_observation_note_visibility
            check ((visibility)::text = 'MANAGER_ONLY'::text),
    updated_at  timestamp with time zone default CURRENT_TIMESTAMP not null,
    deleted_at  timestamp with time zone
);

comment on table observation_note is '면담과 직접 연결되지 않은 매니저의 일반 교육생 관찰 메모를 기관·기수 범위로 관리합니다. | 정의서명: ObservationNote | 제약·비고: • Interview FK를 두지 않으며 필요한 경우 InterviewSource.observation_note_id로 면담 근거에 선택 연결합니다. • 사용자·작성자·기수는 같은 기관 경로여야 합니다. • deleted_at을 이용한 논리 삭제 후 과거 면담 원천 참조는 유지합니다. • visibility는 MANAGER_ONLY 폐쇄형입니다. 보존·마스킹·다운로드 범위는 OPEN 정책 확정 전 임의 DB CHECK로 고정하지 않습니다.';

comment on column observation_note.note_id is '개입과 무관한 매니저 관찰 메모의 개별 레코드를 식별하는 고유 키이다.';

comment on column observation_note.org_id is '개입과 무관한 매니저 관찰 메모의 소속 기관을 식별하며 테넌트 격리와 RLS 필터에 사용한다.';

comment on column observation_note.cohort_id is '관찰 메모가 참조하는 cohort.cohort_id의 식별자이다.';

comment on column observation_note.user_id is '사용자와의 업무 관계를 연결하는 외래 키이다.';

comment on column observation_note.author_id is '관찰 메모가 참조하는 app_user.user_id의 식별자이다.';

comment on column observation_note.content is '관찰 메모의 내용 값을 기록한다.';

comment on column observation_note.occurred_at is '감사 대상 행위가 실제로 발생한 시각이다.';

comment on column observation_note.visibility is '관찰 메모의 공개범위을 나타내는 코드이다. 허용값은 MANAGER_ONLY이며 코드 서식 및 CHECK 제약을 따른다.';

comment on column observation_note.updated_at is '레코드가 마지막으로 변경된 시각이다.';

comment on column observation_note.deleted_at is '소프트 삭제 처리된 시각이며 NULL이면 유효 레코드이다.';

alter table observation_note
    owner to postgres;

grant delete, insert, select, update on observation_note to teamiz_app;

create table one_time_token
(
    token_id                uuid                     default gen_random_uuid() not null
        constraint pk_one_time_token
            primary key,
    org_id                  uuid
        constraint fk_one_time_token_org_id
            references organization
            on delete restrict,
    user_id                 uuid
        constraint fk_one_time_token_user_id
            references app_user
            on delete restrict,
    invitation_id           uuid
        constraint fk_one_time_token_invitation_id
            references user_invitation
            on delete restrict,
    target_email            citext                                             not null,
    target_email_normalized varchar(320)                                       not null,
    purpose                 varchar(50)                                        not null
        constraint ck_one_time_token_purpose
            check ((purpose)::text = ANY
                   (ARRAY [('INVITE_SUPER_ADMIN'::character varying)::text, ('INVITE_OPERATOR_MANAGER'::character varying)::text, ('INVITE_TRAINEE'::character varying)::text, ('EMAIL_VERIFY'::character varying)::text, ('PASSWORD_RESET'::character varying)::text])),
    token_hash              varchar(128)                                       not null
        constraint uq_one_time_token_token_hash
            unique,
    payload                 jsonb                                              not null,
    issued_at               timestamp with time zone default CURRENT_TIMESTAMP not null,
    expires_at              timestamp with time zone                           not null,
    used_at                 timestamp with time zone,
    invalidated_at          timestamp with time zone,
    invalidated_reason      text
        constraint ck_one_time_token_invalidated_reason
            check (invalidated_reason = ANY
                   (ARRAY ['REPLACED'::text, 'INVITATION_CANCELLED'::text, 'ORGANIZATION_SUSPENDED'::text, 'ORGANIZATION_DELETION_PENDING'::text, 'ACCOUNT_INACTIVATED'::text, 'PASSWORD_CHANGED'::text, 'SECURITY_ACTION'::text, 'ADMIN_REVOKED'::text])),
    replaced_by_token_id    uuid
        constraint fk_one_time_token_replaced_by_token_id
            references one_time_token
            on delete restrict,
    issued_by               uuid
        constraint fk_one_time_token_issued_by
            references app_user
            on delete restrict,
    issued_request_id       text                                               not null,
    used_request_id         text,
    created_at              timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint ck_one_time_token_created_at
        check (NOT ((used_at IS NOT NULL) AND (invalidated_at IS NOT NULL))),
    constraint ck_one_time_token_expires_at
        check (expires_at > issued_at),
    constraint ck_one_time_token_invalidated_reason_2
        check (((invalidated_at IS NULL) AND (invalidated_reason IS NULL)) OR
               ((invalidated_at IS NOT NULL) AND (invalidated_reason IS NOT NULL))),
    constraint ck_one_time_token_replaced_by_token_id
        check ((replaced_by_token_id IS NULL) OR (invalidated_at IS NOT NULL)),
    constraint ck_one_time_token_used_request_id
        check (((used_at IS NULL) AND (used_request_id IS NULL)) OR
               ((used_at IS NOT NULL) AND (used_request_id IS NOT NULL)))
);

comment on table one_time_token is '초대 수락, 이메일 소유 확인, 비밀번호 재설정에 사용하는 1회성 자격 증명 원장입니다. 원문 대신 해시를 저장하고 발급·사용·무효화·대체 관계를 분리하여 만료·재사용·재발송을 추적합니다. | 정의서명: OneTimeToken | 제약·비고: • token_hash는 전체 UNIQUE이며 일회성 토큰 원문은 DB·로그·감사 payload에 저장하지 않습니다. • 목적: INVITE_SUPER_ADMIN / INVITE_OPERATOR_MANAGER / INVITE_TRAINEE / EMAIL_VERIFY / PASSWORD_RESET • expires_at은 issued_at보다 이후여야 합니다. • INVITE_SUPER_ADMIN·INVITE_OPERATOR_MANAGER·INVITE_TRAINEE는 invitation_id가 필수이고 EMAIL_VERIFY·PASSWORD_RESET은 invitation_id가 NULL입니다. • 초대 목적 토큰은 연결된 UserInvitation의 org_id·target_email_normalized·대상 역할·기수/반 범위와 일치해야 합니다. • 가입 전 SUPER_ADMIN·OPERATOR·MANAGER 초대는 user_id가 NULL일 수 있고 TRAINEE 초대는 기존 PENDING 교육생 계정·명단 경로를 함께 검증합니다. • 기관 사용자 초대·재설정은 org_id가 필수이며 기관 미소속 SUPER_ADMIN의 INVITE_SUPER_ADMIN·PASSWORD_RESET만 org_id NULL을 허용합니다. • PASSWORD_RESET은 ACTIVE 대상 user_id가 필수입니다. • used_at과 used_request_id는 동시 NULL 또는 동시 NOT NULL이며 사용 완료와 무효화는 동시에 기록하지 않습니다. • invalidated_at이 있으면 invalidated_reason이 필수입니다. • 무효화 사유: REPLACED / INVIT';

comment on column one_time_token.token_id is '일회성 토큰 기본키이다.';

comment on column one_time_token.org_id is '토큰이 속한 기관이다. 기관 미소속 SUPER_ADMIN의 INVITE_SUPER_ADMIN·PASSWORD_RESET만 NULL을 허용한다.';

comment on column one_time_token.user_id is '이메일 인증·비밀번호 재설정 대상 사용자이다.';

comment on column one_time_token.invitation_id is '초대 목적 토큰이 연결되는 초대 업무 원장이다.';

comment on column one_time_token.target_email is '토큰 사용 대상 이메일이다.';

comment on column one_time_token.target_email_normalized is '토큰 대상 이메일 정규화 값이다.';

comment on column one_time_token.purpose is '일회성 토큰 목적이다. INVITE_SUPER_ADMIN·INVITE_OPERATOR_MANAGER·INVITE_TRAINEE는 invitation_id가 필수이다.';

comment on column one_time_token.token_hash is '원문 대신 저장하는 일회성 토큰 해시이다.';

comment on column one_time_token.payload is '초대 역할·대상 범위 등 토큰 사용 결과를 재현하는 구조화 페이로드이다.';

comment on column one_time_token.issued_at is '토큰 발급 시각이다.';

comment on column one_time_token.expires_at is '토큰 만료 시각이다.';

comment on column one_time_token.used_at is '토큰이 성공적으로 사용된 시각이다.';

comment on column one_time_token.invalidated_at is '사용 전 토큰이 무효화된 시각이다.';

comment on column one_time_token.invalidated_reason is '토큰 무효화 사유이다.';

comment on column one_time_token.replaced_by_token_id is '재발급으로 대체된 후속 토큰이다.';

comment on column one_time_token.issued_by is '관리자·운영자에 의해 발급된 경우의 발급 사용자이다.';

comment on column one_time_token.issued_request_id is '토큰 발급 요청 추적 식별자이다.';

comment on column one_time_token.used_request_id is '토큰 사용 요청 추적 식별자이다.';

comment on column one_time_token.created_at is '일회성 토큰 행 생성 시각이다.';

alter table one_time_token
    owner to postgres;

alter table user_invitation
    add constraint fk_user_invitation_current_token_id
        foreign key (current_token_id) references one_time_token
            on delete restrict;

create unique index uq_one_time_token_invitation_request
    on one_time_token (invitation_id, issued_request_id)
    where (invitation_id IS NOT NULL);

create unique index uq_one_time_token_replaced_by_not_null
    on one_time_token (replaced_by_token_id)
    where (replaced_by_token_id IS NOT NULL);

grant delete, insert, select, update on one_time_token to teamiz_app;

grant delete, insert, select, update on organization_policy to teamiz_app;

create table organization_usage_snapshot
(
    usage_snapshot_id           uuid                     default gen_random_uuid() not null
        constraint pk_organization_usage_snapshot
            primary key,
    org_id                      uuid                                               not null
        constraint fk_organization_usage_snapshot_org_id
            references organization
            on delete restrict,
    period_type                 varchar(20)                                        not null
        constraint ck_organization_usage_snapshot_period_type
            check ((period_type)::text = ANY
                   (ARRAY [('DAILY'::character varying)::text, ('MONTHLY'::character varying)::text, ('CUSTOM'::character varying)::text])),
    period_start_at             timestamp with time zone                           not null,
    period_end_at               timestamp with time zone                           not null,
    as_of_at                    timestamp with time zone                           not null,
    active_trainee_count        integer
        constraint ck_organization_usage_snapshot_active_trainee_count_min
            check (active_trainee_count >= 0),
    completed_session_count     integer
        constraint ck_organization_usage_snapshot_completed_session_count_min
            check (completed_session_count >= 0),
    grading_execution_count     integer
        constraint ck_organization_usage_snapshot_grading_execution_count_min
            check (grading_execution_count >= 0),
    published_report_count      integer
        constraint ck_organization_usage_snapshot_published_report_count_min
            check (published_report_count >= 0),
    ai_call_count               bigint
        constraint ck_organization_usage_snapshot_ai_call_count_min
            check (ai_call_count >= 0),
    input_token_count           bigint
        constraint ck_organization_usage_snapshot_input_token_count_min
            check (input_token_count >= 0),
    output_token_count          bigint
        constraint ck_organization_usage_snapshot_output_token_count_min
            check (output_token_count >= 0),
    cached_token_count          bigint
        constraint ck_organization_usage_snapshot_cached_token_count_min
            check (cached_token_count >= 0),
    unpriced_call_count         bigint
        constraint ck_organization_usage_snapshot_unpriced_call_count_min
            check (unpriced_call_count >= 0),
    unpriced_input_token_count  bigint
        constraint ck_organization_usage_snapshot_unpriced_input_token_count_min
            check (unpriced_input_token_count >= 0),
    unpriced_output_token_count bigint
        constraint ck_organization_usage_snapshot_unpriced_output_token_count_min
            check (unpriced_output_token_count >= 0),
    cost_completeness_status    varchar(30)
        constraint ck_organization_usage_snapshot_cost_completeness_status
            check ((cost_completeness_status)::text = ANY
                   (ARRAY [('COMPLETE'::character varying)::text, ('PARTIAL_UNPRICED'::character varying)::text])),
    estimated_cost              numeric(18, 6)
        constraint ck_organization_usage_snapshot_estimated_cost_min
            check (estimated_cost >= (0)::numeric),
    actual_cost                 numeric(18, 6)
        constraint ck_organization_usage_snapshot_actual_cost_min
            check (actual_cost >= (0)::numeric),
    effective_cost              numeric(18, 6),
    currency_code               varchar(3)               default 'USD'::character varying
        constraint ck_organization_usage_snapshot_currency_code
            check ((currency_code)::text = 'USD'::text),
    aggregation_status          varchar(20)                                        not null
        constraint ck_organization_usage_snapshot_aggregation_status
            check ((aggregation_status)::text = ANY
                   (ARRAY [('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])),
    failure_stage               varchar(100)
        constraint ck_organization_usage_snapshot_failure_stage
            check ((failure_stage)::text = ANY
                   (ARRAY [('STORAGE_MEASUREMENT'::character varying)::text, ('ACTIVITY_AGGREGATION'::character varying)::text, ('AI_COST_AGGREGATION'::character varying)::text, ('SNAPSHOT_COMPOSITION'::character varying)::text, ('CURRENCY_VALIDATION'::character varying)::text])),
    failure_code                varchar(100)
        constraint ck_org_usage_snapshot_failure_code_format
            check ((failure_code IS NULL) OR ((failure_code)::text ~ '^[A-Z][A-Z0-9_]{0,99}$'::text)),
    failure_reason              text,
    failed_at                   timestamp with time zone,
    is_retryable                boolean,
    source_watermark            text,
    calculation_version         integer                                            not null
        constraint ck_organization_usage_snapshot_calculation_version_min
            check (calculation_version >= 1),
    created_at                  timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint ck_org_usage_snapshot_failed_fields
        check ((((aggregation_status)::text = 'FAILED'::text) AND (failure_stage IS NOT NULL) AND
                (failure_code IS NOT NULL) AND (failed_at IS NOT NULL) AND (is_retryable IS NOT NULL)) OR
               (((aggregation_status)::text <> 'FAILED'::text) AND (failure_code IS NULL) AND (failed_at IS NULL) AND
                (is_retryable IS NULL))),
    constraint ck_org_usage_snapshot_period_order
        check (period_end_at > period_start_at),
    constraint ck_org_usage_snapshot_succeeded_fields
        check ((((aggregation_status)::text = 'SUCCEEDED'::text) AND (effective_cost IS NOT NULL) AND
                ((currency_code)::text = 'USD'::text) AND (cost_completeness_status IS NOT NULL)) OR
               ((aggregation_status)::text <> 'SUCCEEDED'::text))
);

comment on table organization_usage_snapshot is '특정 기간과 시점의 기관 사용량을 사진처럼 저장한 집계 기록입니다. 활성 교육생 수, 세션 수, AI 호출·토큰·비용을 모아 두며, 집계에 성공했는지 실패했는지도 함께 남깁니다. | 정의서명: OrganizationUsageSnapshot | 제약·비고: • 기간 유형: DAILY / MONTHLY / CUSTOM • 집계 상태: SUCCEEDED / FAILED • 비용 완전성: COMPLETE / PARTIAL_UNPRICED • 성공 스냅샷은 기관·기간·기준 시각별 중복 금지 • SUCCEEDED이면 필수 집계 수치·effective_cost·currency_code=''USD''·비용 완전성 상태 보유 • FAILED이면 집계 수치·비용·통화는 NULL이고 failure_stage·failure_code·failed_at·is_retryable 필수 • 실패 단계: STORAGE_MEASUREMENT / ACTIVITY_AGGREGATION / AI_COST_AGGREGATION / SNAPSHOT_COMPOSITION / CURRENCY_VALIDATION • 모든 수치·비용은 0 이상 • failure_code는 SYS.ORGANIZATION_USAGE_FAILURE_CODE 영문 대문자·숫자·밑줄 1~100자의 확장형 서비스 코드 카탈로그를 사용 • SUCCEEDED에서 실제 사용이 없으면 숫자 0을 저장할 수 있지만, FAILED의 NULL은 ‘0’이 아니라 ‘계산하지 못함’을 뜻합니다. • UNPRICED 호출은 0원으로 합산하지 않고 호출·토큰 수와 PARTIAL_UNPRICED 상태를 남깁니다. • effective_cost는 각 호출에서 실제 비용이 있으면 actual_cost, 없으면 estimated_cost를 선택하여 계산하며 둘을 중복 합산하지 않습니다. • ';

comment on column organization_usage_snapshot.usage_snapshot_id is '기관 사용량 집계 행을 식별한다.';

comment on column organization_usage_snapshot.org_id is '집계 대상 기관이다.';

comment on column organization_usage_snapshot.period_type is '집계 기간 유형이다.';

comment on column organization_usage_snapshot.period_start_at is '집계 시작 시각이다.';

comment on column organization_usage_snapshot.period_end_at is '집계 종료 시각이다.';

comment on column organization_usage_snapshot.as_of_at is '원천 반영 기준 시각이다.';

comment on column organization_usage_snapshot.active_trainee_count is '활성 교육생 수다.';

comment on column organization_usage_snapshot.completed_session_count is '완료 세션 수다.';

comment on column organization_usage_snapshot.grading_execution_count is '답변 판정 실행 수다.';

comment on column organization_usage_snapshot.published_report_count is '선택 기간에 실제 발행된 고유 리포트 수다.';

comment on column organization_usage_snapshot.ai_call_count is 'AI 호출 수다.';

comment on column organization_usage_snapshot.input_token_count is '입력 토큰 합계다.';

comment on column organization_usage_snapshot.output_token_count is '출력 토큰 합계다.';

comment on column organization_usage_snapshot.cached_token_count is '캐시 입력 토큰 합계다.';

comment on column organization_usage_snapshot.unpriced_call_count is '단가 미설정 상태로 기록된 AI 호출 수다.';

comment on column organization_usage_snapshot.unpriced_input_token_count is '단가 미설정 호출의 입력 토큰 합계다.';

comment on column organization_usage_snapshot.unpriced_output_token_count is '단가 미설정 호출의 출력 토큰 합계다.';

comment on column organization_usage_snapshot.cost_completeness_status is '비용 합계가 모든 호출을 포함하는지 나타낸다.';

comment on column organization_usage_snapshot.estimated_cost is '추정 비용 합계다.';

comment on column organization_usage_snapshot.actual_cost is '실제 비용 합계다.';

comment on column organization_usage_snapshot.effective_cost is '호출별 실제 비용 우선, 없으면 추정 비용으로 계산한 표시 비용이다.';

comment on column organization_usage_snapshot.currency_code is '성공 비용 스냅샷의 플랫폼 공통 통화다.';

comment on column organization_usage_snapshot.aggregation_status is '집계 최종 상태다.';

comment on column organization_usage_snapshot.failure_stage is '실패한 집계 단계다.';

comment on column organization_usage_snapshot.failure_code is '`SYS.ORGANIZATION_USAGE_FAILURE_CODE` 확장형 서비스 코드 카탈로그다.';

comment on column organization_usage_snapshot.failure_reason is '실패 사유다.';

comment on column organization_usage_snapshot.failed_at is '집계 실패 시각이다.';

comment on column organization_usage_snapshot.is_retryable is '동일 기간 집계를 재시도할 수 있는지 나타낸다.';

comment on column organization_usage_snapshot.source_watermark is '원천 반영 범위다.';

comment on column organization_usage_snapshot.calculation_version is '집계 계산 버전이다.';

comment on column organization_usage_snapshot.created_at is '생성 시각이다.';

alter table organization_usage_snapshot
    owner to postgres;

create unique index uq_org_usage_snapshot_succeeded_grain
    on organization_usage_snapshot (org_id, period_type, as_of_at)
    where ((aggregation_status)::text = 'SUCCEEDED'::text);

grant delete, insert, select, update on organization_usage_snapshot to teamiz_app;

create table platform_ai_tier_model_policy
(
    tier_policy_id uuid                     default gen_random_uuid() not null
        constraint pk_platform_ai_tier_model_policy
            primary key,
    feature_code   varchar(100)                                       not null
        constraint ck_platform_ai_tier_model_policy_feature_code
            check ((feature_code)::text = 'CODE_SESSION'::text),
    tier_code      varchar(30)                                        not null
        constraint ck_platform_ai_tier_model_policy_tier_code
            check ((tier_code)::text = ANY
                   (ARRAY [('ACCURACY_FIRST'::character varying)::text, ('BALANCED'::character varying)::text, ('COST_FIRST'::character varying)::text])),
    model_id       uuid                                               not null
        constraint fk_platform_ai_tier_model_policy_model_id
            references ai_model
            on delete restrict,
    policy_version integer                                            not null
        constraint ck_platform_ai_tier_model_policy_policy_version_min
            check (policy_version >= 1),
    status         varchar(30)                                        not null
        constraint ck_platform_ai_tier_model_policy_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('SUPERSEDED'::character varying)::text])),
    effective_from timestamp with time zone                           not null,
    effective_to   timestamp with time zone,
    changed_by     uuid                                               not null
        constraint fk_platform_ai_tier_model_policy_changed_by
            references app_user
            on delete restrict,
    change_reason  text,
    created_at     timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at     timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint uq_platform_ai_tier_model_policy_feature_code_tier_code_poli
        unique (feature_code, tier_code, policy_version),
    constraint ck_platform_ai_tier_model_policy_effective_to
        check ((effective_to IS NULL) OR (effective_to > effective_from))
);

comment on table platform_ai_tier_model_policy is '코드 세션 질문 생성 기능의 ‘정확도 우선·균형·비용 우선’ 선택을 실제 AI 모델에 연결하는 플랫폼 공통 표입니다. 기관은 어려운 모델 ID 대신 티어를 고르고, 플랫폼이 그 티어에 맞는 모델을 결정합니다. | 정의서명: PlatformAiTierModelPolicy | 제약·비고: • 기능: CODE_SESSION • 티어: ACCURACY_FIRST / BALANCED / COST_FIRST • 상태: ACTIVE / SUPERSEDED • UNIQUE(feature_code, tier_code, policy_version) • 같은 기능·티어의 활성 기간 중첩 금지 • 대상 모델은 ACTIVE이며 단가 설정이 완결되어야 함 • 정책 변경은 과거 질문·요약·AiUsage를 수정하지 않음 • 상태·유효 종료 일시 변경 시 updated_at을 갱신하며 deleted_at은 사용하지 않음 • 같은 feature_code+tier_code에서 활성 유효 기간이 서로 겹치면 안 됩니다. • 대상 모델은 ACTIVE이고 단가가 완결되어야 합니다. • AiUsage의 feature_code·tier_code·tier_policy_id·model_code는 참조 정책의 값과 모두 일치해야 합니다. • 정책 변경은 이미 생성된 질문·요약·리포트와 과거 AiUsage를 수정하지 않습니다.';

comment on column platform_ai_tier_model_policy.tier_policy_id is '티어 모델 정책 식별자';

comment on column platform_ai_tier_model_policy.feature_code is '적용 AI 기능';

comment on column platform_ai_tier_model_policy.tier_code is '기관이 선택하는 모델 티어';

comment on column platform_ai_tier_model_policy.model_id is '해당 기능·티어의 실제 논리 모델';

comment on column platform_ai_tier_model_policy.policy_version is '기능·티어별 정책 버전';

comment on column platform_ai_tier_model_policy.status is '정책 상태';

comment on column platform_ai_tier_model_policy.effective_from is '신규 실행 적용 시작';

comment on column platform_ai_tier_model_policy.effective_to is '적용 종료';

comment on column platform_ai_tier_model_policy.changed_by is '변경자';

comment on column platform_ai_tier_model_policy.change_reason is '변경 사유';

comment on column platform_ai_tier_model_policy.created_at is '생성 시각';

comment on column platform_ai_tier_model_policy.updated_at is '상태·유효 종료 시각의 최근 변경 시각';

alter table platform_ai_tier_model_policy
    owner to postgres;

grant delete, insert, select, update on platform_ai_tier_model_policy to teamiz_app;

create table platform_grading_model_policy
(
    grading_policy_id uuid                     default gen_random_uuid() not null
        constraint pk_platform_grading_model_policy
            primary key,
    policy_version    integer                                            not null
        constraint uq_platform_grading_model_policy_policy_version
            unique
        constraint ck_platform_grading_model_policy_policy_version_min
            check (policy_version >= 1),
    model_id          uuid                                               not null
        constraint fk_platform_grading_model_policy_model_id
            references ai_model
            on delete restrict,
    status            varchar(30)                                        not null
        constraint ck_platform_grading_model_policy_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('SUPERSEDED'::character varying)::text])),
    effective_from    timestamp with time zone                           not null,
    effective_to      timestamp with time zone,
    changed_by        uuid                                               not null
        constraint fk_platform_grading_model_policy_changed_by
            references app_user
            on delete restrict,
    change_reason     text,
    created_at        timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at        timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint ck_platform_grading_model_policy_effective_to
        check ((effective_to IS NULL) OR (effective_to > effective_from))
);

comment on table platform_grading_model_policy is '교육생 답변을 채점할 때 어떤 논리 AI 모델을 사용할지 정하는 플랫폼 공통 규칙의 버전 기록입니다. 규칙이 바뀌어도 이미 시작한 채점은 시작 당시 규칙을 사용하고, 새 채점부터 새 버전을 사용합니다. | 정의서명: PlatformGradingModelPolicy | 제약·비고: • 상태: ACTIVE / SUPERSEDED • ACTIVE·effective_to IS NULL 행은 플랫폼 전체 최대 1건 • effective_to는 NULL 또는 effective_from보다 이후 • 대상 모델은 ACTIVE이며 단가 설정이 완결되어야 함 • 정책은 논리 모델을 직접 선택하고 실행 원장은 실제 사용한 model_id를 기록 • 변경은 신규 실행부터 적용하며 진행 중 실행에 소급하지 않음 • 상태·유효 종료 일시 변경 시 updated_at을 갱신하며 deleted_at은 사용하지 않음 • 플랫폼 전체에서 status=''ACTIVE'' AND effective_to IS NULL인 정책은 최대 1건입니다. • 새 정책, 새 캘리브레이션 버전, 기관별 PENDING 행과 감사 기록을 하나의 트랜잭션으로 생성합니다. • 진행 중 세션과 해당 세션의 재시도는 시작할 때 고정한 기존 정책을 끝까지 사용합니다. • ANSWER_EVALUATION AiUsage의 model_id는 이 정책이 가리키는 model_id와 일치해야 합니다.';

comment on column platform_grading_model_policy.grading_policy_id is '채점 모델 정책 식별자';

comment on column platform_grading_model_policy.policy_version is '정책 버전';

comment on column platform_grading_model_policy.model_id is '기본 채점 논리 모델';

comment on column platform_grading_model_policy.status is '정책 상태';

comment on column platform_grading_model_policy.effective_from is '신규 채점 적용 시작';

comment on column platform_grading_model_policy.effective_to is '적용 종료';

comment on column platform_grading_model_policy.changed_by is '변경자';

comment on column platform_grading_model_policy.change_reason is '변경 사유';

comment on column platform_grading_model_policy.created_at is '생성 시각';

comment on column platform_grading_model_policy.updated_at is '상태·유효 종료 시각의 최근 변경 시각';

alter table platform_grading_model_policy
    owner to postgres;

create table platform_grading_calibration_version
(
    calibration_version_id uuid                     default gen_random_uuid() not null
        constraint pk_platform_grading_calibration_version
            primary key,
    grading_policy_id      uuid                                               not null
        constraint fk_platform_grading_calibration_version_grading_policy_id
            references platform_grading_model_policy
            on delete restrict,
    version_code           varchar(100)                                       not null
        constraint uq_platform_grading_calibration_version_version_code
            unique,
    status                 varchar(30)                                        not null
        constraint ck_platform_grading_calibration_version_status
            check ((status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('RUNNING'::character varying)::text, ('ACTIVE'::character varying)::text, ('FAILED'::character varying)::text, ('SUPERSEDED'::character varying)::text])),
    started_at             timestamp with time zone,
    completed_at           timestamp with time zone,
    created_by             uuid                                               not null
        constraint fk_platform_grading_calibration_version_created_by
            references app_user
            on delete restrict,
    failure_code           varchar(100)
        constraint ck_platform_grading_calib_ver_failure_code_format
            check ((failure_code IS NULL) OR ((failure_code)::text ~ '^[A-Z][A-Z0-9_]{0,99}$'::text)),
    failure_reason         text,
    failed_at              timestamp with time zone,
    created_at             timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at             timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint ck_platform_grading_calib_ver_failed_fields
        check (((status)::text <> 'FAILED'::text) OR ((failure_code IS NOT NULL) AND (failed_at IS NOT NULL)))
);

comment on table platform_grading_calibration_version is '채점 모델이 바뀔 때 점수 기준이 갑자기 달라지지 않도록 다시 맞추는 작업의 버전입니다. 자의 길이가 달라지면 이전 측정과 비교하기 어려운 것처럼, 어떤 채점 기준으로 나온 결과인지 구분하는 비교 기준이 됩니다. | 정의서명: PlatformGradingCalibrationVersion | 제약·비고: • 상태: PENDING / RUNNING / ACTIVE / FAILED / SUPERSEDED • 신규 채점 정책 생성과 같은 트랜잭션에서 PENDING 버전 생성 • FAILED이면 failure_code·failed_at 필수 • ACTIVE는 필수 대상 기관의 OrganizationGradingCalibration 성공 후에만 허용 • 서로 다른 calibration_version_id 결과는 기본 비교·추이에 직접 혼합하지 않음 • version_code UNIQUE • 상태 전이·시작·완료·실패 정보 변경 시 updated_at을 갱신하며 deleted_at은 사용하지 않음 • failure_code는 SYS.PLATFORM_GRADING_CALIBRATION_FAILURE_CODE 영문 대문자·숫자·밑줄 1~100자의 확장형 서비스 코드 카탈로그를 사용 • RUNNING이면 started_at, ACTIVE이면 completed_at, FAILED이면 failure_code와 failed_at이 필수입니다. • 모든 필수 기관의 OrganizationGradingCalibration이 성공한 뒤에만 ACTIVE로 전이합니다. • 새 버전이 ACTIVE가 되기 전까지 이전 ACTIVE 버전을 비교 기준으로 유지합니다. • 서로 다른 calibration_version_id의 결과는 단계 하락·성장 추이에 직접 섞지 않습니다.';

comment on column platform_grading_calibration_version.calibration_version_id is '캘리브레이션 버전 식별자';

comment on column platform_grading_calibration_version.grading_policy_id is '대상 채점 모델 정책';

comment on column platform_grading_calibration_version.version_code is '화면·비교에 사용하는 불변 버전 코드';

comment on column platform_grading_calibration_version.status is '버전 상태';

comment on column platform_grading_calibration_version.started_at is '재캘리브레이션 시작 시각';

comment on column platform_grading_calibration_version.completed_at is '전체 완료·활성화 시각';

comment on column platform_grading_calibration_version.created_by is '모델 변경 명령자';

comment on column platform_grading_calibration_version.failure_code is '`SYS.PLATFORM_GRADING_CALIBRATION_FAILURE_CODE` 확장형 서비스 코드';

comment on column platform_grading_calibration_version.failure_reason is '실패 사유';

comment on column platform_grading_calibration_version.failed_at is '실패 시각';

comment on column platform_grading_calibration_version.created_at is '생성 시각';

comment on column platform_grading_calibration_version.updated_at is '상태·시작·완료·실패 정보의 최근 변경 시각';

alter table platform_grading_calibration_version
    owner to postgres;

create table ai_usage
(
    usage_id                      uuid                     default gen_random_uuid()                   not null
        constraint pk_ai_usage
            primary key,
    org_id                        uuid                                                                 not null
        constraint fk_ai_usage_org_id
            references organization
            on delete restrict,
    model_code                    varchar(100)                                                         not null
        constraint fk_ai_usage_model_code
            references ai_model (model_code)
            on update cascade on delete restrict,
    actor_user_id                 uuid
        constraint fk_ai_usage_actor_user_id
            references app_user
            on delete restrict,
    cohort_id                     uuid
        constraint fk_ai_usage_cohort_id
            references cohort
            on delete restrict,
    class_id                      uuid
        constraint fk_ai_usage_class_id
            references class
            on delete restrict,
    project_id                    uuid
        constraint fk_ai_usage_project_id
            references project
            on delete restrict,
    feature_code                  varchar(100)                                                         not null
        constraint ck_ai_usage_feature_code
            check ((feature_code)::text = ANY
                   (ARRAY [('CODE_ANALYSIS'::character varying)::text, ('CURRICULUM_ANALYSIS'::character varying)::text, ('CODE_SESSION'::character varying)::text, ('ANSWER_EVALUATION'::character varying)::text, ('INTERVIEW_BRIEF_GENERATION'::character varying)::text, ('REPORT_GENERATION'::character varying)::text])),
    context_type                  varchar(100)                                                         not null
        constraint ck_ai_usage_context_type
            check ((context_type)::text = ANY
                   (ARRAY [('SUBMISSION'::character varying)::text, ('CURRICULUM_VERSION'::character varying)::text, ('CURRICULUM_ANALYSIS'::character varying)::text, ('ANALYSIS_JOB'::character varying)::text, ('CODE_ANALYSIS'::character varying)::text, ('ASSESSMENT_SESSION'::character varying)::text, ('ASSESSMENT_PROBLEM'::character varying)::text, ('PROBLEM_STAGE'::character varying)::text, ('INTERVIEW_BRIEF'::character varying)::text, ('REPORT_GENERATION_ITEM'::character varying)::text, ('REPORT_SNAPSHOT'::character varying)::text, ('INTERVENTION'::character varying)::text])),
    context_id                    text,
    trigger_type                  varchar(30)                                                          not null
        constraint ck_ai_usage_trigger_type
            check ((trigger_type)::text = ANY
                   (ARRAY [('USER'::character varying)::text, ('SCHEDULED'::character varying)::text, ('EVENT'::character varying)::text, ('RETRY'::character varying)::text, ('BATCH'::character varying)::text])),
    tier_code                     varchar(30)
        constraint ck_ai_usage_tier_code
            check ((tier_code)::text = ANY
                   (ARRAY [('ACCURACY_FIRST'::character varying)::text, ('BALANCED'::character varying)::text, ('COST_FIRST'::character varying)::text])),
    tier_policy_id                uuid
        constraint fk_ai_usage_tier_policy_id
            references platform_ai_tier_model_policy
            on delete restrict,
    grading_policy_id             uuid
        constraint fk_ai_usage_grading_policy_id
            references platform_grading_model_policy
            on delete restrict,
    calibration_version_id        uuid
        constraint fk_ai_usage_calibration_version_id
            references platform_grading_calibration_version
            on delete restrict,
    attribution_status            varchar(30)                                                          not null
        constraint ck_ai_usage_attribution_status
            check ((attribution_status)::text = ANY
                   (ARRAY [('ALLOCATED'::character varying)::text, ('PARTIALLY_ALLOCATED'::character varying)::text, ('UNALLOCATED'::character varying)::text])),
    unallocated_reason_code       varchar(100)
        constraint ck_ai_usage_unallocated_reason_code
            check ((unallocated_reason_code)::text = ANY
                   (ARRAY [('ORG_COMMON'::character varying)::text, ('COHORT_UNRESOLVED'::character varying)::text, ('PROJECT_UNRESOLVED'::character varying)::text, ('CONTEXT_UNRESOLVED'::character varying)::text])),
    class_attribution_status      varchar(30)              default 'NOT_APPLICABLE'::character varying not null
        constraint ck_ai_usage_class_attribution_status
            check ((class_attribution_status)::text = ANY
                   (ARRAY [('ALLOCATED'::character varying)::text, ('NOT_APPLICABLE'::character varying)::text, ('UNALLOCATED'::character varying)::text])),
    class_unallocated_reason_code varchar(100)
        constraint ck_ai_usage_class_unallocated_reason_code
            check ((class_unallocated_reason_code)::text = ANY
                   (ARRAY [('CLASS_MEMBERSHIP_NOT_FOUND'::character varying)::text, ('CLASS_CONTEXT_UNRESOLVED'::character varying)::text, ('CLASS_PATH_CONFLICT'::character varying)::text])),
    request_id                    text                                                                 not null,
    trace_id                      text                                                                 not null,
    idempotency_key               text                                                                 not null
        constraint uq_ai_usage_idempotency_key
            unique,
    input_token_count             bigint                   default 0                                   not null
        constraint ck_ai_usage_input_token_count_min
            check (input_token_count >= 0),
    output_token_count            bigint                   default 0                                   not null
        constraint ck_ai_usage_output_token_count_min
            check (output_token_count >= 0),
    cached_token_count            bigint                   default 0                                   not null,
    pricing_status                varchar(30)                                                          not null
        constraint ck_ai_usage_pricing_status
            check ((pricing_status)::text = ANY
                   (ARRAY [('PRICED'::character varying)::text, ('UNPRICED'::character varying)::text, ('ACTUAL_COST_ONLY'::character varying)::text])),
    input_unit_price              numeric(18, 6)
        constraint ck_ai_usage_input_unit_price_min
            check (input_unit_price >= (0)::numeric),
    output_unit_price             numeric(18, 6)
        constraint ck_ai_usage_output_unit_price_min
            check (output_unit_price >= (0)::numeric),
    cached_input_unit_price       numeric(18, 6)
        constraint ck_ai_usage_cached_input_unit_price_min
            check (cached_input_unit_price >= (0)::numeric),
    currency_code                 varchar(3)
        constraint ck_ai_usage_currency_code
            check ((currency_code)::text = 'USD'::text),
    estimated_cost                numeric(18, 6)
        constraint ck_ai_usage_estimated_cost_min
            check (estimated_cost >= (0)::numeric),
    actual_cost                   numeric(18, 6)
        constraint ck_ai_usage_actual_cost_min
            check (actual_cost >= (0)::numeric),
    status                        varchar(30)                                                          not null
        constraint ck_ai_usage_status
            check ((status)::text = ANY
                   (ARRAY [('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text, ('PARTIAL'::character varying)::text])),
    failure_code                  varchar(100)
        constraint ck_ai_usage_failure_code
            check ((failure_code)::text = ANY
                   (ARRAY [('TIMEOUT'::character varying)::text, ('RATE_LIMITED'::character varying)::text, ('PROVIDER_ERROR'::character varying)::text, ('INVALID_JSON'::character varying)::text, ('CONTEXT_OVERFLOW'::character varying)::text, ('NO_AVAILABLE_MODEL_INSTANCE'::character varying)::text, ('MODEL_INSTANCE_QUOTA_EXHAUSTED'::character varying)::text, ('MODEL_CREDENTIAL_INVALID'::character varying)::text])),
    latency_ms                    integer                                                              not null
        constraint ck_ai_usage_latency_ms_min
            check (latency_ms >= 0),
    occurred_at                   timestamp with time zone                                             not null,
    created_at                    timestamp with time zone default CURRENT_TIMESTAMP                   not null,
    constraint ck_ai_usage_cached_token_count_range
        check ((cached_token_count >= 0) AND (cached_token_count <= input_token_count)),
    constraint ck_ai_usage_feature_code_2
        check (((feature_code)::text <> 'CODE_SESSION'::text) OR
               ((tier_code IS NOT NULL) AND (tier_policy_id IS NOT NULL))),
    constraint ck_ai_usage_feature_code_3
        check (((feature_code)::text <> ALL
                (ARRAY [('INTERVIEW_BRIEF_GENERATION'::character varying)::text, ('REPORT_GENERATION'::character varying)::text])) OR
               ((tier_code IS NULL) AND (tier_policy_id IS NULL)))
);

comment on table ai_usage is 'AI를 한 번 호출할 때마다 남기는 영수증 같은 실행 원장입니다. 어느 기관에서 어떤 기능을 어떤 논리 모델 코드로 실행했는지, 토큰과 비용, 성공·실패, 기수·반·프로젝트 귀속을 호출 당시 모습으로 보존합니다. | 정의서명: AiUsage | 제약·비고: • APPEND-ONLY 원장, idempotency_key UNIQUE • feature_code: CODE_ANALYSIS / CURRICULUM_ANALYSIS / CODE_SESSION / ANSWER_EVALUATION / INTERVIEW_BRIEF_GENERATION / REPORT_GENERATION • trigger_type: USER / SCHEDULED / EVENT / RETRY / BATCH • attribution_status: ALLOCATED / PARTIALLY_ALLOCATED / UNALLOCATED • class_attribution_status: ALLOCATED / NOT_APPLICABLE / UNALLOCATED • pricing_status: PRICED / UNPRICED / ACTUAL_COST_ONLY • CODE_SESSION이면 tier_code·tier_policy_id 필수이며 INTERVIEW_BRIEF_GENERATION·REPORT_GENERATION이면 둘 다 NULL • ANSWER_EVALUATION이면 grading_policy_id·calibration_version_id 필수 • PRICED이면 입력·출력 단가·currency_code=''USD''·estimated_cost 필수 • UNPRICED이면 단가·통화·추정 비용 NULL, 호출·토큰 원장은 보존 • class_id·project_id가 있으면 cohort_id 필수이며 기관·기수 경로 일치 • cla';

comment on column ai_usage.usage_id is '개별 AI 호출 비용 원장 행이다. 정정은 후속 보정 행으로 처리한다.';

comment on column ai_usage.org_id is '호출 비용의 소속 기관이다.';

comment on column ai_usage.model_code is '실제 호출한 논리 모델의 코드이다.';

comment on column ai_usage.actor_user_id is '사용자 요청이면 행위자를 연결하며 시스템·배치 호출은 NULL을 허용한다.';

comment on column ai_usage.cohort_id is '기수별 비용 조회를 위한 직접 귀속 FK다.';

comment on column ai_usage.class_id is 'AI 호출 발생 시점의 반 비용 귀속 FK다. 이후 반 배정 변경으로 갱신하지 않는다.';

comment on column ai_usage.project_id is '프로젝트별 비용 조회를 위한 직접 귀속 FK다.';

comment on column ai_usage.feature_code is 'AI가 **무슨 기능을 수행했는지** 나타낸다.';

comment on column ai_usage.context_type is 'AI 호출의 처리 대상 업무 엔터티 유형이다.';

comment on column ai_usage.context_id is '처리 대상 업무 엔터티의 PK다. 업무 맥락이 식별되지 않은 실패 기록은 NULL을 허용한다.';

comment on column ai_usage.trigger_type is '이번 실행을 직접 시작한 방식이다. 사용자 요청·스케줄러·업무 이벤트·재시도·다건 일괄 작업을 구분한다.';

comment on column ai_usage.tier_code is '코드 세션 질문 생성 실행 시 기관이 선택한 티어 스냅샷이다.';

comment on column ai_usage.tier_policy_id is '코드 세션 질문 생성 실행 당시 티어 정책이다.';

comment on column ai_usage.grading_policy_id is '답변 통과 판정 실행 당시 적용된 모델 정책이다.';

comment on column ai_usage.calibration_version_id is '답변 통과 판정 결과의 비교 기준 캘리브레이션 버전이다.';

comment on column ai_usage.attribution_status is '비용이 기수·프로젝트 범위에 얼마나 귀속됐는지 나타낸다.';

comment on column ai_usage.unallocated_reason_code is '미귀속 또는 부분 귀속 사유다.';

comment on column ai_usage.class_attribution_status is '반 비용 귀속 상태다. 기수·프로젝트 귀속 상태와 별도로 관리한다.';

comment on column ai_usage.class_unallocated_reason_code is '반 귀속이 필요하지만 확정하지 못한 사유다.';

comment on column ai_usage.request_id is '최초 요청을 식별한다.';

comment on column ai_usage.trace_id is '서비스 간 호출 흐름을 추적한다.';

comment on column ai_usage.idempotency_key is '동일 AI 호출 결과의 중복 원장 저장을 방지한다.';

comment on column ai_usage.input_token_count is '입력 토큰 수다.';

comment on column ai_usage.output_token_count is '출력 토큰 수다.';

comment on column ai_usage.cached_token_count is '캐시 입력 토큰 수다.';

comment on column ai_usage.pricing_status is '호출 비용 산정 완결 상태다.';

comment on column ai_usage.input_unit_price is '호출 시점 입력 단위 단가 스냅샷이다.';

comment on column ai_usage.output_unit_price is '호출 시점 출력 단위 단가 스냅샷이다.';

comment on column ai_usage.cached_input_unit_price is '호출 시점 캐시 입력 단위 단가다.';

comment on column ai_usage.currency_code is '가격이 확정된 호출의 플랫폼 공통 통화다.';

comment on column ai_usage.estimated_cost is '호출 시점 단가와 토큰으로 계산한 추정 비용이다.';

comment on column ai_usage.actual_cost is '공급자 정산 등으로 확인된 실제 비용이다.';

comment on column ai_usage.status is 'AI 호출 처리 상태다.';

comment on column ai_usage.failure_code is '재시도·통계용 안정 실패 코드다.';

comment on column ai_usage.latency_ms is '호출 지연 시간이다.';

comment on column ai_usage.occurred_at is 'AI 호출이 실제 발생한 시각이다.';

comment on column ai_usage.created_at is '원장 기록 시각이다.';

alter table ai_usage
    owner to postgres;

grant delete, insert, select, update on ai_usage to teamiz_app;

create table organization_grading_calibration
(
    organization_calibration_id uuid                     default gen_random_uuid() not null
        constraint pk_organization_grading_calibration
            primary key,
    calibration_version_id      uuid                                               not null
        constraint fk_organization_grading_calibration_calibration_version_id
            references platform_grading_calibration_version
            on delete restrict,
    org_id                      uuid                                               not null
        constraint fk_organization_grading_calibration_org_id
            references organization
            on delete restrict,
    status                      varchar(30)                                        not null
        constraint ck_organization_grading_calibration_status
            check ((status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])),
    started_at                  timestamp with time zone,
    completed_at                timestamp with time zone,
    failure_code                varchar(100)
        constraint ck_org_grading_calibration_failure_code_format
            check ((failure_code IS NULL) OR ((failure_code)::text ~ '^[A-Z][A-Z0-9_]{0,99}$'::text)),
    failure_reason              text,
    failed_at                   timestamp with time zone,
    retry_count                 integer                  default 0                 not null
        constraint ck_organization_grading_calibration_retry_count
            check (retry_count >= 0),
    created_at                  timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at                  timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint uq_organization_grading_calibration_calibration_version_id_o
        unique (calibration_version_id, org_id),
    constraint ck_org_grading_calibration_completed_at
        check (((status)::text <> 'SUCCEEDED'::text) OR (completed_at IS NOT NULL)),
    constraint ck_org_grading_calibration_failed_fields
        check (((status)::text <> 'FAILED'::text) OR ((failure_code IS NOT NULL) AND (failed_at IS NOT NULL)))
);

comment on table organization_grading_calibration is '하나의 플랫폼 캘리브레이션 작업을 기관별로 나누어 진행한 상태 기록입니다. 각 기관이 대기·진행·성공·실패 중 어디에 있는지와 재시도 횟수를 따로 관리합니다. | 정의서명: OrganizationGradingCalibration | 제약·비고: • UNIQUE(calibration_version_id, org_id) • 상태: PENDING / RUNNING / SUCCEEDED / FAILED • SUCCEEDED이면 completed_at 필수 • FAILED이면 failure_code·failed_at 필수 • retry_count >= 0 • 파기 완료 기관은 생성 대상에서 제외 • 기관별 상태를 독립 조회·재시도할 수 있도록 org_id 직접 RLS 적용 • created_at은 기관별 대상 행 생성 시각, updated_at은 최신 상태 변경 시각이며 deleted_at은 사용하지 않음 • failure_code는 SYS.ORGANIZATION_GRADING_CALIBRATION_FAILURE_CODE 영문 대문자·숫자·밑줄 1~100자의 확장형 서비스 코드 카탈로그를 사용 • RUNNING이면 started_at, SUCCEEDED이면 completed_at, FAILED이면 failure_code와 failed_at이 필수입니다. • UNIQUE(calibration_version_id, org_id)로 같은 버전·기관의 중복 작업 행을 막습니다. • 이미 파기 완료된 기관은 새 캘리브레이션 대상에서 제외합니다. • 일부 기관 행 생성에 실패하면 플랫폼 채점 정책 변경 전체를 롤백하여 반쪽짜리 대상 목록을 남기지 않습니다.';

comment on column organization_grading_calibration.organization_calibration_id is '기관별 실행 식별자';

comment on column organization_grading_calibration.calibration_version_id is '플랫폼 캘리브레이션 버전';

comment on column organization_grading_calibration.org_id is '대상 기관';

comment on column organization_grading_calibration.status is '기관별 실행 상태';

comment on column organization_grading_calibration.started_at is '시작 시각';

comment on column organization_grading_calibration.completed_at is '성공 완료 시각';

comment on column organization_grading_calibration.failure_code is '`SYS.ORGANIZATION_GRADING_CALIBRATION_FAILURE_CODE` 확장형 서비스 코드';

comment on column organization_grading_calibration.failure_reason is '실패 사유';

comment on column organization_grading_calibration.failed_at is '실패 시각';

comment on column organization_grading_calibration.retry_count is '재시도 횟수';

comment on column organization_grading_calibration.created_at is '기관별 대상 실행 행 생성 시각';

comment on column organization_grading_calibration.updated_at is '마지막 상태 변경 시각';

alter table organization_grading_calibration
    owner to postgres;

grant delete, insert, select, update on organization_grading_calibration to teamiz_app;

grant delete, insert, select, update on platform_grading_calibration_version to teamiz_app;

grant delete, insert, select, update on platform_grading_model_policy to teamiz_app;

create table project_extraction_scope
(
    extraction_scope_id    uuid                     default gen_random_uuid() not null
        constraint pk_project_extraction_scope
            primary key,
    project_id             uuid                                               not null
        constraint fk_project_extraction_scope_project_id
            references project
            on delete restrict,
    assessment_round_id    uuid                                               not null
        constraint fk_project_extraction_scope_assessment_round_id
            references project_assessment_round
            on delete restrict,
    org_id                 uuid                                               not null
        constraint fk_project_extraction_scope_org_id
            references organization
            on delete restrict,
    scope_code             varchar(100)                                       not null
        constraint ck_project_extraction_scope_scope_code
            check ((scope_code)::text = ANY
                   (ARRAY [('TOTAL'::character varying)::text, ('OWN_COMMIT'::character varying)::text])),
    inherited_from_default boolean                  default false             not null,
    version_no             integer                                            not null
        constraint ck_project_extraction_scope_version_no
            check (version_no > 0),
    effective_from         timestamp with time zone                           not null,
    effective_to           timestamp with time zone,
    changed_by             uuid                                               not null
        constraint fk_project_extraction_scope_changed_by
            references app_user
            on delete restrict,
    created_at             timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint ex_project_extraction_scope_no_overlap
        exclude using gist (assessment_round_id with =, tstzrange(effective_from,
                                                                  COALESCE(effective_to, 'infinity'::timestamp with time zone),
                                                                  '[)'::text) with &&),
    constraint uq_project_extraction_scope_assessment_round_id_version_no
        unique (assessment_round_id, version_no),
    constraint ck_project_extraction_scope_effective_to
        check ((effective_to IS NULL) OR (effective_to > effective_from))
);

comment on table project_extraction_scope is '프로젝트 회차에서 코드 분석에 적용할 실제 추출 범위를 버전·유효 기간별로 명시 저장합니다. 회차 생성 시 프로젝트 기본값을 상속한 최초 행을 만들고 분석 실행은 정확한 extraction_scope_id를 고정 참조합니다. | 정의서명: ProjectExtractionScope | 제약·비고: • 범위 코드: TOTAL / OWN_COMMIT • 회차별 version_no는 양수이며 유일합니다. • 동일 회차의 유효 기간 중첩을 금지하고 effective_to IS NULL인 현재 행은 최대 1건입니다. • 활성 회차마다 현재 행 정확히 1건 존재 여부는 회차 생성·범위 변경 트랜잭션에서 잠금 검증합니다. • 최초 행은 version_no=1, inherited_from_default=TRUE이고 프로젝트 기본 범위를 복사합니다. • 명시 변경은 기존 현재 행 종료와 inherited_from_default=FALSE인 다음 버전 생성을 원자 처리합니다.';

comment on column project_extraction_scope.extraction_scope_id is '프로젝트 추출 범위 행을 유일하게 식별하는 기본키이다.';

comment on column project_extraction_scope.project_id is '추출 범위가 속한 프로젝트 ID이다.';

comment on column project_extraction_scope.assessment_round_id is '추출 범위가 적용되는 평가 회차 ID이다.';

comment on column project_extraction_scope.org_id is '추출 범위가 속한 기관 ID이다.';

comment on column project_extraction_scope.scope_code is '코드 분석에 적용할 추출 범위 코드이다.';

comment on column project_extraction_scope.inherited_from_default is '프로젝트 기본 추출 범위를 상속하여 생성한 버전인지 나타낸다.';

comment on column project_extraction_scope.version_no is '회차 내 추출 범위 버전 번호이다.';

comment on column project_extraction_scope.effective_from is '추출 범위 버전의 효력 시작 일시이다.';

comment on column project_extraction_scope.effective_to is '추출 범위 버전의 효력 종료 일시이다.';

comment on column project_extraction_scope.changed_by is '프로젝트 추출 범위가 참조하는 app_user.user_id의 식별자이다.';

comment on column project_extraction_scope.created_at is '프로젝트 추출 범위가 생성된 시각이다.';

alter table project_extraction_scope
    owner to postgres;

create table code_analysis
(
    analysis_id              uuid                     default gen_random_uuid() not null
        constraint pk_code_analysis
            primary key,
    org_id                   uuid                                               not null
        constraint fk_code_analysis_org_id
            references organization
            on delete restrict,
    assessment_round_id      uuid                                               not null
        constraint fk_code_analysis_assessment_round_id
            references project_assessment_round
            on delete restrict,
    team_id                  uuid                                               not null
        constraint fk_code_analysis_team_id
            references team
            on delete restrict,
    source_submission_id     uuid                                               not null
        constraint fk_code_analysis_source_submission_id
            references submission
            on delete restrict,
    extraction_scope_id      uuid                                               not null
        constraint fk_code_analysis_extraction_scope_id
            references project_extraction_scope
            on delete restrict,
    status                   varchar(100)                                       not null
        constraint ck_code_analysis_status
            check ((status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('SUPERSEDED'::character varying)::text, ('INVALID'::character varying)::text])),
    analysis_document        jsonb                                              not null
        constraint ck_code_analysis_analysis_document
            check (jsonb_typeof(analysis_document) = 'object'::text),
    scope_fallback           boolean                  default false             not null,
    fallback_reason          text,
    created_at               timestamp with time zone default CURRENT_TIMESTAMP not null,
    external_snapshot_id     uuid
        constraint uq_code_analysis_external_snapshot_id
            unique,
    applied_scope_code       varchar(100)
        constraint ck_code_analysis_applied_scope_code
            check ((applied_scope_code IS NULL) OR ((applied_scope_code)::text = ANY
                                                    (ARRAY [('TOTAL'::character varying)::text, ('OWN_COMMIT'::character varying)::text]))),
    resolved_branch          text,
    head_commit_sha          varchar(64),
    head_commit_message      text,
    head_commit_committed_at timestamp with time zone,
    git_history_source       varchar(30)
        constraint ck_code_analysis_git_history_source
            check ((git_history_source IS NULL) OR ((git_history_source)::text = ANY
                                                    (ARRAY [('BACKEND_SUPPLIED'::character varying)::text, ('EMBEDDED_GIT'::character varying)::text, ('REMOTE_DEEPEN'::character varying)::text, ('NONE'::character varying)::text]))),
    history_truncated        boolean
);

comment on table code_analysis is '팀의 확정 Submission 분석 입력을 특정 분석 문서에 따라 분석하여 커밋·파일 귀속과 문제 생성의 공통 기준이 되는 성공 결과 루트입니다. 실행 실패·재시도는 AnalysisJob이 소유합니다. | 정의서명: CodeAnalysis | 제약·비고: • 상태: ACTIVE / SUPERSEDED / INVALID • 동일 assessment_round_id + team_id + source_submission_id의 ACTIVE 결과는 최대 1건입니다. • ProjectExtractionScope.assessment_round_id·project_id·org_id는 제출·팀·회차 경로와 일치해야 합니다. • extraction_scope_id는 요청 범위를 보존하고 scope_fallback·fallback_reason은 실제 분석 범위가 요청보다 확대되었는지를 보존합니다. • scope_fallback=TRUE인 개인 커밋 분석 결과는 본인 커밋 기준 결과로 간주하지 않고 API·화면에서 경고합니다. • analysis_document는 AI 서버가 실제 분석에 사용한 구조화 문서를 JSONB로 불변 저장하며 schema_version을 포함해야 합니다. • 미니프로젝트는 선택된 검증 개념 3건에 대응하는 TEAM_SHARED_PROBLEM 문제 슬롯 3건을 기록합니다. 코드 근거를 찾은 슬롯만 GENERATED 문제 콘텐츠를 가지며 근거를 찾지 못한 슬롯은 NOT_GENERATED로 남깁니다. • 문제 출제에 선택된 대표 코드 스니펫 원문은 source_submission_id가 가리키는 Submission.code_snippets에 최대 3건으로 확정하고 AssessmentProblem은 source_snippet_key와 위치·해시로 참조합니다. • 팀원 N명은 같은 AC • applied_scope_code는 AI 응답의 appliedScope를 보존하며 요청 범위(extraction_scope_id)와 다르면 scope_fallback=TRUE입니다(2026-08-06, S-08).';

comment on column code_analysis.analysis_id is '코드 분석·기여 분석 결과의 개별 레코드를 식별하는 고유 키이다.';

comment on column code_analysis.org_id is '기관 ID 값을 저장한다.';

comment on column code_analysis.assessment_round_id is '평가 회차 ID 값을 저장한다.';

comment on column code_analysis.team_id is '팀 ID 값을 저장한다.';

comment on column code_analysis.source_submission_id is '코드 분석에 사용한 불변 Submission 분석 입력을 참조한다.';

comment on column code_analysis.extraction_scope_id is '적용 추출 범위 ID 값을 저장한다.';

comment on column code_analysis.status is '코드 분석·기여 분석 결과의 현재 업무 처리 상태를 나타낸다.';

comment on column code_analysis.analysis_document is '코드 분석 결과로 생성된 코드베이스 개요, 구조, 주요 의사결정 지점, 위험 요소와 관련 교안 근거를 버전형 JSON 문서로 저장한다.';

comment on column code_analysis.scope_fallback is '요청한 코드 추출 범위를 지키지 못해 더 넓은 범위로 분석했는지 여부이다.';

comment on column code_analysis.fallback_reason is '코드 분석 범위 폴백이 발생한 원인과 실제 적용 범위를 설명한다.';

comment on column code_analysis.created_at is '레코드가 최초 생성된 시각이다.';

comment on column code_analysis.external_snapshot_id is 'AI 서버 응답의 result.snapshotId를 보관한다.';

comment on column code_analysis.applied_scope_code is 'AI 서버가 실제로 적용한 추출 범위다. 요청 범위는 extraction_scope_id가 보존하며 두 값이 다르면 scope_fallback=TRUE다. 값 집합은 ProjectExtractionScope.scope_code와 동일하게 유지한다.';

comment on column code_analysis.resolved_branch is 'AI 서버 응답의 result.resolvedBranch를 보관한다. GITHUB_URL·ZIP_WITH_GITLOG 제출 모두에 채울 수 있다(2026-08-07, S-20).';

comment on column code_analysis.head_commit_sha is 'AI 서버 응답의 result.headCommit.sha를 보관한다(2026-08-07, S-20).';

comment on column code_analysis.head_commit_message is 'AI 서버 응답의 result.headCommit.message를 보관한다(2026-08-07, S-20).';

comment on column code_analysis.head_commit_committed_at is 'AI 서버 응답의 result.headCommit.committedAt을 보관한다(2026-08-07, S-20).';

comment on column code_analysis.git_history_source is '커밋 이력을 어디서 가져왔는지다(2026-08-09, v08). AI 응답 gitHistorySource 를 그대로 보존한다. NONE 이면 이력이 없다는 뜻이며, 개인 기여도를 산정할 때 이 값과 history_truncated 를 함께 봐야 커밋 수를 그대로 믿어도 되는지 판단할 수 있다. 이 컬럼이 생기기 전에 만들어진 분석 결과는 NULL 이다.';

comment on column code_analysis.history_truncated is '커밋 이력이 일부만 수집됐는지다(2026-08-09, v08). TRUE 이면 commit_attribution 행 수가 실제 커밋 수보다 적으므로 기여도를 "최소값"으로만 읽어야 한다. 이 컬럼이 생기기 전에 만들어진 분석 결과는 NULL 이며, FALSE(=잘리지 않음)로 읽으면 안 된다.';

alter table code_analysis
    owner to postgres;

create table analysis_job
(
    job_id                    uuid                     default gen_random_uuid() not null
        constraint pk_analysis_job
            primary key,
    org_id                    uuid                                               not null
        constraint fk_analysis_job_org_id
            references organization
            on delete restrict,
    assessment_round_id       uuid                                               not null
        constraint fk_analysis_job_assessment_round_id
            references project_assessment_round
            on delete restrict,
    team_id                   uuid                                               not null
        constraint fk_analysis_job_team_id
            references team
            on delete restrict,
    submission_id             uuid                                               not null
        constraint fk_analysis_job_submission_id
            references submission
            on delete restrict,
    analysis_id               uuid
        constraint fk_analysis_job_analysis_id
            references code_analysis
            on delete restrict,
    question_focus_version_no integer,
    batch_key                 text                                               not null,
    job_type                  varchar(100)                                       not null,
    execution_no              integer                                            not null
        constraint ck_analysis_job_execution_no
            check (execution_no > 0),
    status                    varchar(100)                                       not null
        constraint ck_analysis_job_status
            check ((status)::text = ANY
                   (ARRAY [('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('PARTIAL'::character varying)::text, ('FAILED'::character varying)::text])),
    started_at                timestamp with time zone,
    completed_at              timestamp with time zone,
    failure_reason            text,
    trace_id                  text                                               not null,
    external_job_id           uuid
        constraint uq_analysis_job_external_job_id
            unique,
    extraction_scope_id       uuid
        constraint fk_analysis_job_extraction_scope_id
            references project_extraction_scope
            on delete restrict,
    requested_model_id        uuid
        constraint fk_analysis_job_requested_model_id
            references ai_model
            on delete restrict,
    question_budget           smallint
        constraint ck_analysis_job_question_budget
            check ((question_budget IS NULL) OR (question_budget > 0)),
    request_payload           jsonb
        constraint ck_analysis_job_request_payload
            check ((request_payload IS NULL) OR (jsonb_typeof(request_payload) = 'object'::text)),
    request_payload_hash      char(64)
        constraint ck_analysis_job_request_payload_hash
            check ((request_payload_hash IS NULL) OR (request_payload_hash ~ '^[0-9a-f]{64}$'::text)),
    payload_schema_version    integer
        constraint ck_analysis_job_payload_schema_version
            check ((payload_schema_version IS NULL) OR (payload_schema_version >= 1)),
    failure_code              varchar(100)
        constraint ck_analysis_job_failure_code_2
            check ((failure_code IS NULL) OR ((failure_code)::text = ANY
                                              (ARRAY [('SOURCE_UNREACHABLE'::character varying)::text, ('UNSUPPORTED_LANGUAGE'::character varying)::text, ('ANALYSIS_TIMEOUT'::character varying)::text, ('MODEL_ERROR'::character varying)::text, ('TEMPORARY_ERROR'::character varying)::text, ('INVALID_REPOSITORY_URL'::character varying)::text, ('REPO_NOT_FOUND'::character varying)::text, ('REPOSITORY_ACCESS_DENIED'::character varying)::text, ('BRANCH_NOT_FOUND'::character varying)::text, ('UNSUPPORTED_HOST'::character varying)::text, ('FILE_TOO_LARGE'::character varying)::text, ('ARCHIVE_INVALID'::character varying)::text, ('EMPTY_CODE'::character varying)::text, ('PROHIBITED_FILE'::character varying)::text, ('GIT_LOG_MISSING'::character varying)::text]))),
    created_at                timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint uq_analysis_job_batch_key_job_type_execution_no
        unique (batch_key, job_type, execution_no),
    constraint ck_analysis_job_failure_code
        check (((status)::text <> 'FAILED'::text) OR (failure_code IS NOT NULL)),
    constraint ck_analysis_job_status_2
        check ((((status)::text = 'QUEUED'::text) AND (started_at IS NULL) AND (completed_at IS NULL) AND
                (failure_reason IS NULL)) OR
               (((status)::text = 'RUNNING'::text) AND (started_at IS NOT NULL) AND (completed_at IS NULL) AND
                (failure_reason IS NULL)) OR (((status)::text = ANY
                                               (ARRAY [('SUCCEEDED'::character varying)::text, ('PARTIAL'::character varying)::text])) AND
                                              (started_at IS NOT NULL) AND (completed_at IS NOT NULL) AND
                                              (failure_reason IS NULL)) OR
               (((status)::text = 'FAILED'::text) AND (started_at IS NOT NULL) AND (completed_at IS NOT NULL) AND
                (failure_reason IS NOT NULL)))
);

comment on table analysis_job is '제출 마감 후 현재 Submission의 확정 분석 입력·귀속·공통 문제 또는 개인 기여도·질문 생성을 단계별로 추적하는 실행·재시도 원장입니다. | 정의서명: AnalysisJob | 제약·비고: • 상태: QUEUED / RUNNING / SUCCEEDED / PARTIAL / FAILED • execution_no > 0, UNIQUE(batch_key, job_type, execution_no) • 같은 batch_key + job_type의 QUEUED/RUNNING 활성 실행은 최대 1건입니다. • 재시도는 기존 행을 갱신하지 않고 execution_no+1의 새 행으로 보존합니다. • batch_key는 assessment_round_id·team_id·submission_id와 실행 목적을 안정적으로 포함합니다. • 작업 생성 시 회차의 현재 유효 질문 초점 전체 집합 version_no를 고정하고 재시도는 원본 버전을 사용합니다. • analysis_id는 성공 결과가 생성된 작업에서만 연결될 수 있으며 같은 회차·팀·source_submission_id 경로와 일치해야 합니다. • 실행 상태·시작·완료·실패는 AnalysisJob이 소유하고 CodeAnalysis에는 실행 상태를 중복 저장하지 않습니다. • AiUsage는 context_type=ANALYSIS_JOB, context_id=job_id 또는 작업 결과 맥락으로 논리 연결합니다. • failure_code는 분석 실행 실패 6종과 저장소 접근 실패 5종, ZIP 검증 실패 5종을 합한 16종입니다. 저장소 접근 주체가 AI 서버로 확정되어 저장소 사유를 별도 필드로 받지 않고 분석 실패 코드에 통합했고(2026-08-06, S-03), ZIP 검증 실패 5종도 같은 이유로 추가했습니다(2026-08-07, S-15). 저장소 5종은 RepositoryVerification.failure_code와, ZIP 5종은 SubmissionArtifact.validation_failure_code와 문자열이 동일하며 해당 사유일 때는 두 곳에 같은 값을 기록합니다.';

comment on column analysis_job.job_id is '분석 작업 행을 유일하게 식별하는 기본키이다.';

comment on column analysis_job.org_id is '기관 ID 값을 저장한다.';

comment on column analysis_job.assessment_round_id is '평가 회차 ID 값을 저장한다.';

comment on column analysis_job.team_id is '팀 ID 값을 저장한다.';

comment on column analysis_job.submission_id is '분석 작업가 참조하는 submission.submission_id의 식별자이다.';

comment on column analysis_job.analysis_id is '분석 작업가 참조하는 code_analysis.analysis_id의 식별자이다.';

comment on column analysis_job.question_focus_version_no is '회차 질문 초점 집합 버전 값을 저장한다.';

comment on column analysis_job.batch_key is '배치 키 값을 저장한다.';

comment on column analysis_job.job_type is '분석 작업의 작업 유형을 나타내는 코드이다. 허용값과 의미는 코드 서식 및 CHECK 제약을 따른다.';

comment on column analysis_job.execution_no is '실행 순번 값을 저장한다.';

comment on column analysis_job.status is '분석 작업의 상태을 나타내는 코드이다. 허용값과 의미는 코드 서식 및 CHECK 제약을 따른다.';

comment on column analysis_job.started_at is '분석 작업가 처리가 시작된 시각이다.';

comment on column analysis_job.completed_at is '분석 작업가 처리가 완료된 시각이다.';

comment on column analysis_job.failure_reason is '분석 작업의 실패 사유 내용을 기록한다.';

comment on column analysis_job.trace_id is '분석 작업 업무에서 사용하는 추적 ID 값이다.';

comment on column analysis_job.external_job_id is 'AI 서버가 202 응답으로 반환한 작업 ID를 보관하여 비동기 폴링과 재현에 사용한다.';

comment on column analysis_job.extraction_scope_id is '요청 당시 적용한 추출 범위(TOTAL/OWN_COMMIT) 버전을 고정한다.';

comment on column analysis_job.requested_model_id is '요청에 providerModelCode가 지정된 경우 해당 모델을 보존한다.';

comment on column analysis_job.question_budget is '요청의 questionBudget 값을 보존한다.';

comment on column analysis_job.request_payload is 'requirements·teaches·focusItems·attemptId를 포함한 실제 전송 요청 스냅샷을 보존한다.';

comment on column analysis_job.request_payload_hash is '동일 요청 판별과 재시도 검증에 사용하는 SHA-256 해시이다.';

comment on column analysis_job.payload_schema_version is '요청·응답 계약의 스키마 버전을 기록하여 재현성을 보장한다.';

comment on column analysis_job.failure_code is '실패를 안정적으로 분류하기 위한 코드이다. 분석 실행 실패 6종(EMPTY_CODE_EVIDENCE·SOURCE_UNREACHABLE·UNSUPPORTED_LANGUAGE·ANALYSIS_TIMEOUT·MODEL_ERROR·TEMPORARY_ERROR)은 분석 실행 자체의 실패 사유이고, 저장소 접근 실패 5종(INVALID_REPOSITORY_URL·REPO_NOT_FOUND·REPOSITORY_ACCESS_DENIED·BRANCH_NOT_FOUND·UNSUPPORTED_HOST)은 RepositoryVerification.failure_code와, ZIP 검증 실패 5종(FILE_TOO_LARGE·ARCHIVE_INVALID·EMPTY_CODE·PROHIBITED_FILE·GIT_LOG_MISSING)은 SubmissionArtifact.validation_failure_code와 문자열이 동일하며 해당 사유일 때는 두 곳에 같은 값을 기록한다. EMPTY_CODE(ZIP에 코드가 없음)와 EMPTY_CODE_EVIDENCE(분석할 근거를 찾지 못함)는 서로 다른 사유다.';

comment on column analysis_job.created_at is '202 접수 전후를 포함한 작업 생성 시각이다.';

alter table analysis_job
    owner to postgres;

create unique index uq_analysis_job_active
    on analysis_job (batch_key, job_type)
    where ((status)::text = ANY (ARRAY [('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text]));

grant delete, insert, select, update on analysis_job to teamiz_app;

create table measurement_attempt
(
    attempt_id                       uuid                     default gen_random_uuid()            not null
        constraint pk_measurement_attempt
            primary key,
    org_id                           uuid                                                          not null
        constraint fk_measurement_attempt_org_id
            references organization
            on delete restrict,
    cohort_id                        uuid                                                          not null
        constraint fk_measurement_attempt_cohort_id
            references cohort
            on delete restrict,
    assessment_round_id              uuid                                                          not null
        constraint fk_measurement_attempt_assessment_round_id
            references project_assessment_round
            on delete restrict,
    project_id                       uuid                                                          not null
        constraint fk_measurement_attempt_project_id
            references project
            on delete restrict,
    user_id                          uuid                                                          not null
        constraint fk_measurement_attempt_user_id
            references app_user
            on delete restrict,
    assessment_contract_version      varchar(30)              default 'MEAS_V1'::character varying not null,
    source_submission_id             uuid
        constraint fk_measurement_attempt_source_submission_id
            references submission
            on delete restrict,
    code_analysis_id                 uuid
        constraint fk_measurement_attempt_code_analysis_id
            references code_analysis
            on delete restrict,
    attempt_type                     varchar(100)                                                  not null
        constraint ck_measurement_attempt_attempt_type
            check ((attempt_type)::text = ANY
                   (ARRAY [('INITIAL'::character varying)::text, ('RETRY'::character varying)::text, ('REVIEW'::character varying)::text])),
    source_attempt_id                uuid
        constraint fk_measurement_attempt_source_attempt_id
            references measurement_attempt
            on delete restrict,
    attempt_sequence_no              integer                                                       not null
        constraint ck_measurement_attempt_attempt_sequence_no
            check (attempt_sequence_no > 0),
    assigned_at                      timestamp with time zone,
    assigned_by                      uuid
        constraint fk_measurement_attempt_assigned_by
            references app_user
            on delete restrict,
    review_source_report_id          uuid
        constraint fk_measurement_attempt_review_source_report_id
            references report
            on delete restrict,
    review_source_report_snapshot_id uuid
        constraint fk_measurement_attempt_review_source_report_snapshot_id
            references report_snapshot
            on delete restrict,
    review_due_at                    timestamp with time zone,
    status                           varchar(100)                                                  not null
        constraint ck_measurement_attempt_status
            check ((status)::text = ANY
                   (ARRAY [('NOT_STARTED'::character varying)::text, ('SUBMITTED'::character varying)::text, ('ANALYZING'::character varying)::text, ('SESSION_READY'::character varying)::text, ('SESSION_IN_PROGRESS'::character varying)::text, ('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('EXPIRED'::character varying)::text])),
    terminal_reason_code             varchar(100)
        constraint ck_measurement_attempt_terminal_reason_code
            check ((terminal_reason_code)::text = ANY
                   (ARRAY [('COMPLETED'::character varying)::text, ('NOT_SUBMITTED'::character varying)::text, ('ANALYSIS_FAILED'::character varying)::text, ('INSUFFICIENT_PROBLEM_EVIDENCE'::character varying)::text, ('INSUFFICIENT_OWN_COMMIT_EVIDENCE'::character varying)::text, ('NOT_ATTENDED'::character varying)::text, ('SESSION_INCOMPLETE'::character varying)::text, ('REVIEW_NOT_COMPLETED'::character varying)::text, ('INVALID'::character varying)::text])),
    terminal_at                      timestamp with time zone,
    analysis_completed_at            timestamp with time zone,
    assessment_open_at               timestamp with time zone,
    assessment_close_at              timestamp with time zone,
    validity_review_status           varchar(100)                                                  not null
        constraint ck_measurement_attempt_validity_review_status
            check ((validity_review_status)::text = ANY
                   (ARRAY [('NOT_REQUIRED'::character varying)::text, ('PENDING'::character varying)::text, ('CONFIRMED_INVALID'::character varying)::text, ('RESTORED_VALID'::character varying)::text])),
    validity_trigger_reason_code     varchar(100)
        constraint ck_measurement_attempt_validity_trigger_reason_code
            check ((validity_trigger_reason_code IS NULL) OR ((validity_trigger_reason_code)::text = ANY
                                                              (ARRAY [('EXCESSIVE_WINDOW_LEAVE'::character varying)::text, ('EXCESSIVE_CONNECTION_LOSS'::character varying)::text, ('MANAGER_MANUAL_FLAG'::character varying)::text]))),
    validity_decision_reason_code    varchar(100)
        constraint ck_measurement_attempt_validity_decision_reason_code
            check ((validity_decision_reason_code IS NULL) OR ((validity_decision_reason_code)::text = ANY
                                                               (ARRAY [('REVIEWED_NO_VIOLATION'::character varying)::text, ('REVIEWED_VIOLATION_CONFIRMED'::character varying)::text, ('REVIEWED_INSUFFICIENT_EVIDENCE'::character varying)::text]))),
    validity_decision_note           text,
    validity_review_started_at       timestamp with time zone,
    validity_reviewed_by             uuid
        constraint fk_measurement_attempt_validity_reviewed_by
            references app_user
            on delete restrict,
    validity_reviewed_at             timestamp with time zone,
    row_version                      integer                  default 0                            not null,
    updated_at                       timestamp with time zone default CURRENT_TIMESTAMP            not null,
    outcome_type_code                varchar(100)
        constraint ck_measurement_attempt_outcome_type_code
            check ((outcome_type_code IS NULL) OR ((outcome_type_code)::text = ANY
                                                   (ARRAY [('STAGE_DECLINE'::character varying)::text, ('PERSISTENT_LOW'::character varying)::text, ('INVALID_ATTEMPT'::character varying)::text, ('CONTRIBUTION_UNDERSTANDING_GAP'::character varying)::text, ('LOW_PARTICIPATION'::character varying)::text, ('EXCELLENT_TRAINEE'::character varying)::text]))),
    outcome_verdict                  jsonb,
    outcome_policy_version           integer,
    outcome_judged_at                timestamp with time zone,
    constraint ck_measurement_attempt_assessment_close_at
        check ((assessment_close_at IS NULL) OR
               ((assessment_open_at IS NOT NULL) AND (assessment_close_at > assessment_open_at))),
    constraint ck_measurement_attempt_attempt_type_2
        check ((((attempt_type)::text = 'INITIAL'::text) AND (source_attempt_id IS NULL) AND (assigned_at IS NULL) AND
                (assigned_by IS NULL) AND (review_source_report_id IS NULL) AND
                (review_source_report_snapshot_id IS NULL) AND (review_due_at IS NULL)) OR
               (((attempt_type)::text = 'RETRY'::text) AND (source_attempt_id IS NOT NULL) AND
                (review_source_report_id IS NULL) AND (review_source_report_snapshot_id IS NULL) AND
                (review_due_at IS NULL)) OR
               (((attempt_type)::text = 'REVIEW'::text) AND (source_attempt_id IS NOT NULL) AND
                (assigned_at IS NOT NULL) AND (assigned_by IS NOT NULL) AND (review_source_report_id IS NOT NULL) AND
                (review_source_report_snapshot_id IS NOT NULL) AND (review_due_at IS NOT NULL))),
    constraint ck_measurement_attempt_outcome_initial_only
        check (((attempt_type)::text = 'INITIAL'::text) OR
               ((outcome_type_code IS NULL) AND (outcome_verdict IS NULL) AND (outcome_policy_version IS NULL) AND
                (outcome_judged_at IS NULL))),
    constraint ck_measurement_attempt_outcome_set
        check (((outcome_judged_at IS NULL) AND (outcome_type_code IS NULL) AND (outcome_verdict IS NULL) AND
                (outcome_policy_version IS NULL)) OR
               ((outcome_judged_at IS NOT NULL) AND (outcome_verdict IS NOT NULL) AND (outcome_policy_version > 0) AND
                (jsonb_typeof(outcome_verdict) = 'object'::text))),
    constraint ck_measurement_attempt_status_2
        check ((((status)::text = ANY
                 (ARRAY [('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('EXPIRED'::character varying)::text])) AND
                (terminal_reason_code IS NOT NULL) AND (terminal_at IS NOT NULL)) OR (((status)::text <> ALL
                                                                                       (ARRAY [('COMPLETED'::character varying)::text, ('FAILED'::character varying)::text, ('EXPIRED'::character varying)::text])) AND
                                                                                      (terminal_reason_code IS NULL) AND
                                                                                      (terminal_at IS NULL))),
    constraint ck_measurement_attempt_validity_decision_status_match
        check ((validity_decision_reason_code IS NULL) OR
               (((validity_decision_reason_code)::text = 'REVIEWED_VIOLATION_CONFIRMED'::text) AND
                ((validity_review_status)::text = 'CONFIRMED_INVALID'::text)) OR
               (((validity_decision_reason_code)::text = ANY
                 (ARRAY [('REVIEWED_NO_VIOLATION'::character varying)::text, ('REVIEWED_INSUFFICIENT_EVIDENCE'::character varying)::text])) AND
                ((validity_review_status)::text = 'RESTORED_VALID'::text))),
    constraint ck_measurement_attempt_validity_review_status_2
        check ((((validity_review_status)::text = ANY
                 (ARRAY [('NOT_REQUIRED'::character varying)::text, ('PENDING'::character varying)::text])) AND
                (validity_decision_reason_code IS NULL) AND (validity_reviewed_by IS NULL) AND
                (validity_reviewed_at IS NULL)) OR (((validity_review_status)::text = ANY
                                                     (ARRAY [('CONFIRMED_INVALID'::character varying)::text, ('RESTORED_VALID'::character varying)::text])) AND
                                                    (validity_decision_reason_code IS NOT NULL) AND
                                                    (validity_reviewed_by IS NOT NULL) AND
                                                    (validity_reviewed_at IS NOT NULL)))
);

comment on table measurement_attempt is '교육생의 프로젝트 회차별 최초 응시·재시험·다시 보기 수행과 제출·분석·응시 창·종료·무효 확인 현재 상태를 관리하는 개인 수행 SSOT입니다. | 정의서명: MeasurementAttempt | 제약·비고: • attempt_type: INITIAL / RETRY / REVIEW • 상태: NOT_STARTED / SUBMITTED / ANALYZING / SESSION_READY / SESSION_IN_PROGRESS / COMPLETED / FAILED / EXPIRED • UNIQUE(assessment_round_id, user_id) WHERE attempt_type=''INITIAL'' • attempt_sequence_no > 0이며 source_attempt_id가 있으면 같은 사용자·프로젝트·회차의 선행 수행이어야 합니다. • INITIAL은 source_attempt_id·assigned_at·assigned_by·review_source_*·review_due_at이 NULL입니다. • RETRY는 source_attempt_id가 동일 회차 INITIAL 또는 선행 RETRY를 참조하고 재시험 정책에 따른 순번을 가집니다. • REVIEW는 source_attempt_id, assigned_at, assigned_by, review_source_report_id, review_source_report_snapshot_id, review_due_at이 필수입니다. • 회차 OPEN 전환 시 유효 참여 교육생별 INITIAL 수행을 멱등 생성합니다. • 같은 팀의 유효 수행은 동일 source_submission_id와 code_analysis_id를 공유하지만 답변·세션·결과는 개인별로 분리합니다. • analysis_completed_at 확정 시 assessmen • 개인 응시 창은 회차 ProjectAssessmentRound의 공통 창에서 파생하여 실행 당시 값으로 고정합니다: assessment_open_at = MAX(analysis_completed_at, round.assessment_open_at), assessment_close_at = round.assessment_due_at이며 매니저 예외 연장 시에만 후자가 달라집니다. 고정 이후에는 회차 공통 창이 변경되어도 소급하지 않습니다.';

comment on column measurement_attempt.attempt_id is '교육생의 프로젝트 평가 회차별 측정 수행 원장을 식별하는 고유 키이다.';

comment on column measurement_attempt.org_id is '교육생의 프로젝트 평가 회차별 측정 수행 원장의 소속 기관을 식별하며 테넌트 격리와 RLS 필터에 사용한다.';

comment on column measurement_attempt.cohort_id is '측정 수행가 참조하는 cohort.cohort_id의 식별자이다.';

comment on column measurement_attempt.assessment_round_id is '측정 수행가 참조하는 평가 회차 ID 외래키 후보이다.';

comment on column measurement_attempt.project_id is '프로젝트와의 업무 관계를 연결하는 외래 키이다.';

comment on column measurement_attempt.user_id is '사용자와의 업무 관계를 연결하는 외래 키이다.';

comment on column measurement_attempt.assessment_contract_version is '측정 수행 생성과 평가에 적용된 전역 평가 구조 계약 버전을 저장한다.';

comment on column measurement_attempt.source_submission_id is '원천 제출 ID 값을 저장한다.';

comment on column measurement_attempt.code_analysis_id is '코드 분석 ID 값을 저장한다.';

comment on column measurement_attempt.attempt_type is '수행 유형 값을 저장한다.';

comment on column measurement_attempt.source_attempt_id is '원천 수행 ID 값을 저장한다.';

comment on column measurement_attempt.attempt_sequence_no is '수행 순번 값을 저장한다.';

comment on column measurement_attempt.assigned_at is '배정 일시 값을 저장한다.';

comment on column measurement_attempt.assigned_by is '배정자 ID 값을 저장한다.';

comment on column measurement_attempt.review_source_report_id is '다시 보기 기준 리포트 ID 값을 저장한다.';

comment on column measurement_attempt.review_source_report_snapshot_id is '다시 보기 기준 리포트 스냅샷 ID 값을 저장한다.';

comment on column measurement_attempt.review_due_at is '다시 보기 마감 일시 값을 저장한다.';

comment on column measurement_attempt.status is '교육생의 프로젝트 평가 회차별 측정 수행 원장의 현재 업무 처리 상태를 나타낸다.';

comment on column measurement_attempt.terminal_reason_code is '종료 사유 코드 값을 저장한다.';

comment on column measurement_attempt.terminal_at is '종료 일시 값을 저장한다.';

comment on column measurement_attempt.analysis_completed_at is '측정 수행의 분석 완료 일시 값을 기록한다.';

comment on column measurement_attempt.assessment_open_at is '측정 수행의 평가 시작 가능 일시 값을 기록한다.';

comment on column measurement_attempt.assessment_close_at is '측정 수행의 평가 종료 일시 값을 기록한다.';

comment on column measurement_attempt.validity_review_status is '무효 확인 상태 값을 저장한다.';

comment on column measurement_attempt.validity_trigger_reason_code is '무효 확인 발동 사유 코드이다. 허용값 3종(EXCESSIVE_WINDOW_LEAVE·EXCESSIVE_CONNECTION_LOSS·MANAGER_MANUAL_FLAG)은 CHECK 제약을 따르며 검토 미대상이면 NULL이다.';

comment on column measurement_attempt.validity_decision_reason_code is '무효 확인 결정 사유 코드이다. 허용값 3종(REVIEWED_NO_VIOLATION·REVIEWED_VIOLATION_CONFIRMED·REVIEWED_INSUFFICIENT_EVIDENCE)은 CHECK 제약을 따르며, REVIEWED_VIOLATION_CONFIRMED는 validity_review_status=CONFIRMED_INVALID에서만, 나머지 2종은 RESTORED_VALID에서만 허용된다(2026-08-07, S-17).';

comment on column measurement_attempt.validity_decision_note is '무효 확인 메모 값을 저장한다.';

comment on column measurement_attempt.validity_review_started_at is '무효 확인 시작 일시 값을 저장한다.';

comment on column measurement_attempt.validity_reviewed_by is '무효 확인 처리자 ID 값을 저장한다.';

comment on column measurement_attempt.validity_reviewed_at is '무효 확인 완료 일시 값을 저장한다.';

comment on column measurement_attempt.row_version is '행 버전 값을 저장한다.';

comment on column measurement_attempt.updated_at is '레코드가 마지막으로 변경된 시각이다.';

comment on column measurement_attempt.outcome_type_code is '이 회차에서 결정된 유형 판정 한 건이다. 위험 5종(STAGE_DECLINE / PERSISTENT_LOW / INVALID_ATTEMPT / CONTRIBUTION_UNDERSTANDING_GAP / LOW_PARTICIPATION)과 우수 1종(EXCELLENT_TRAINEE) 중 하나이며, 판정했으나 걸린 유형이 없으면 NULL이다. 동시에 매치된 나머지 유형은 outcome_verdict.types에만 남는다. 우수 후보와 지속 우수는 별도 코드를 두지 않고 각각 outcome_verdict의 게이트 통과 여부와 최근 유효 회차의 EXCELLENT_TRAINEE 개수로 유도한다. 이 컬럼이 유형 판정의 유일한 원장이며 InterviewCandidateReason은 여기서 파생한다.';

comment on column measurement_attempt.outcome_verdict is '유형 판정 근거이다. judgedAt / policyVersion / selectedTypeCode / selectionRule / gate / types / resolved 키를 가진다. types는 평가한 유형 전체와 각각의 evaluationStatus(MATCHED / NOT_MATCHED / NOT_APPLICABLE / UNAVAILABLE)를 담는다. 근거 문제·단계는 UUID가 아니라 (problemNo, axisCode) 자연키로 적어 assessment_problem·problem_stage에 대한 dangling 참조가 생기지 않게 한다.';

comment on column measurement_attempt.outcome_policy_version is '유형 판정에 적용한 정책 버전이다. manager_trainee_roster_view.risk_policy_version의 원천이다.';

comment on column measurement_attempt.outcome_judged_at is '유형 판정 배치가 이 수행을 판정한 시각이다. 판정 실행 여부를 가르는 컬럼이며 미판정이면 NULL이다. 판정했으나 걸린 유형이 없으면 이 값만 채워지고 outcome_type_code는 NULL이다. manager_trainee_roster_view.risk_evaluated_at의 원천이다.';

alter table measurement_attempt
    owner to postgres;

create index ix_measurement_attempt_outcome_type_code
    on measurement_attempt (outcome_type_code)
    where (outcome_type_code IS NOT NULL);

create unique index uq_measurement_attempt_initial
    on measurement_attempt (assessment_round_id, user_id)
    where ((attempt_type)::text = 'INITIAL'::text);

create unique index uq_measurement_attempt_retry_sequence
    on measurement_attempt (assessment_round_id, user_id, attempt_type, attempt_sequence_no)
    where ((attempt_type)::text <> 'INITIAL'::text);

grant delete, insert, select, update on measurement_attempt to teamiz_app;

create table reminder_dispatch
(
    dispatch_id             uuid                     default gen_random_uuid()            not null
        constraint pk_reminder_dispatch
            primary key,
    assessment_round_id     uuid                                                          not null
        constraint fk_reminder_dispatch_assessment_round_id
            references project_assessment_round
            on delete restrict,
    team_id                 uuid
        constraint fk_reminder_dispatch_team_id
            references team
            on delete restrict,
    user_id                 uuid                                                          not null
        constraint fk_reminder_dispatch_user_id
            references app_user
            on delete restrict,
    org_id                  uuid                                                          not null
        constraint fk_reminder_dispatch_org_id
            references organization
            on delete restrict,
    analysis_job_id         uuid
        constraint fk_reminder_dispatch_analysis_job_id
            references analysis_job
            on delete restrict,
    report_id               uuid
        constraint fk_reminder_dispatch_report_id
            references report
            on delete restrict,
    trigger_type            varchar(100)                                                  not null
        constraint ck_reminder_dispatch_trigger_type
            check ((trigger_type)::text = ANY
                   (ARRAY [('USER_REQUESTED'::character varying)::text, ('SYSTEM_EVENT'::character varying)::text])),
    reason_code             varchar(100)                                                  not null
        constraint ck_reminder_dispatch_reason_code
            check ((reason_code)::text = ANY
                   (ARRAY [('TEAM_SUBMISSION_MISSING'::character varying)::text, ('TEAM_ANALYSIS_FAILED'::character varying)::text, ('INDIVIDUAL_ASSESSMENT_NOT_STARTED'::character varying)::text, ('ANALYSIS_COMPLETED'::character varying)::text, ('REPORT_PUBLISHED'::character varying)::text, ('REPORT_RELEASED'::character varying)::text, ('REVIEW_ASSIGNED'::character varying)::text])),
    message_template_code   varchar(100)                                                  not null
        constraint ck_reminder_dispatch_message_template_code
            check ((message_template_code)::text = ANY
                   (ARRAY [('TEAM_SUBMISSION_MISSING_V1'::character varying)::text, ('TEAM_ANALYSIS_FAILED_V1'::character varying)::text, ('INDIVIDUAL_ASSESSMENT_NOT_STARTED_V1'::character varying)::text, ('ANALYSIS_COMPLETED_V1'::character varying)::text, ('REPORT_PUBLISHED_V1'::character varying)::text, ('REPORT_RELEASED_V1'::character varying)::text, ('REVIEW_ASSIGNED_V1'::character varying)::text])),
    dispatch_batch_id       uuid                                                          not null,
    request_idempotency_key text,
    request_fingerprint     char(64),
    request_id              uuid,
    correlation_id          uuid,
    channel                 varchar(30)                                                   not null
        constraint ck_reminder_dispatch_channel
            check ((channel)::text = ANY
                   (ARRAY [('EMAIL'::character varying)::text, ('SMS'::character varying)::text, ('PUSH'::character varying)::text])),
    status                  varchar(30)              default 'PENDING'::character varying not null
        constraint ck_reminder_dispatch_status
            check ((status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('SENT'::character varying)::text, ('FAILED'::character varying)::text, ('SUPPRESSED'::character varying)::text])),
    suppression_reason_code varchar(100)
        constraint ck_reminder_dispatch_suppression_reason_code
            check ((suppression_reason_code)::text = ANY
                   (ARRAY [('NUDGE_TARGET_NOT_ELIGIBLE'::character varying)::text, ('NUDGE_TARGET_STATE_CHANGED'::character varying)::text, ('NUDGE_MANAGER_ASSIGNMENT_EXPIRED'::character varying)::text, ('NUDGE_SUBMISSION_DEADLINE_PASSED'::character varying)::text, ('NUDGE_ASSESSMENT_WINDOW_CLOSED'::character varying)::text, ('NUDGE_DUPLICATE_SUPPRESSED'::character varying)::text, ('NOTIFICATION_DUPLICATE_SUPPRESSED'::character varying)::text, ('NUDGE_RECIPIENT_CHANNEL_UNAVAILABLE'::character varying)::text])),
    requested_by            uuid
        constraint fk_reminder_dispatch_requested_by
            references app_user
            on delete restrict,
    requested_at            timestamp with time zone default CURRENT_TIMESTAMP            not null,
    sent_at                 timestamp with time zone,
    failure_code            varchar(100)
        constraint ck_reminder_dispatch_failure_code
            check ((failure_code)::text = ANY
                   (ARRAY [('PROVIDER_ERROR'::character varying)::text, ('TIMEOUT'::character varying)::text, ('RATE_LIMITED'::character varying)::text, ('INVALID_RECIPIENT'::character varying)::text, ('TEMPLATE_ERROR'::character varying)::text, ('CHANNEL_UNAVAILABLE'::character varying)::text, ('SOURCE_NOT_READY'::character varying)::text, ('SOURCE_PATH_CONFLICT'::character varying)::text, ('UNKNOWN_ERROR'::character varying)::text])),
    failure_reason          text,
    retry_count             integer                  default 0                            not null
        constraint ck_reminder_dispatch_retry_count
            check (retry_count >= 0),
    dedupe_key              text                                                          not null
        constraint uq_reminder_dispatch_dedupe_key
            unique,
    created_at              timestamp with time zone default CURRENT_TIMESTAMP            not null,
    constraint ck_reminder_dispatch_failure_reason
        check ((((status)::text = 'FAILED'::text) AND (failure_code IS NOT NULL) AND (failure_reason IS NOT NULL)) OR
               (((status)::text <> 'FAILED'::text) AND (failure_code IS NULL) AND (failure_reason IS NULL))),
    constraint ck_reminder_dispatch_sent_at
        check ((((status)::text = 'SENT'::text) AND (sent_at IS NOT NULL)) OR
               (((status)::text <> 'SENT'::text) AND (sent_at IS NULL)))
);

comment on table reminder_dispatch is '매니저가 요청한 미제출·분석 실패·미응시 독촉과 시스템이 발생시킨 분석 준비 완료·리포트 발행/공개·다시 보기 배정 알림을 수신자별로 기록하는 발송 원장입니다. 외부 발송 성공·실패·억제·재시도만 소유하며 제출·분석·응시·리포트의 업무 상태를 변경하지 않습니다. | 정의서명: ReminderDispatch | 제약·비고: • assessment_round_id는 모든 발송의 필수 프로젝트 평가 회차 FK입니다. • trigger_type: USER_REQUESTED / SYSTEM_EVENT • reason_code: TEAM_SUBMISSION_MISSING / TEAM_ANALYSIS_FAILED / INDIVIDUAL_ASSESSMENT_NOT_STARTED / ANALYSIS_COMPLETED / REPORT_PUBLISHED / REPORT_RELEASED / REVIEW_ASSIGNED • message_template_code는 사유별 버전형 템플릿 7종을 사용하고 사용자 자유 문구를 저장하지 않습니다. • USER_REQUESTED이면 requested_by·request_idempotency_key·request_fingerprint가 필수입니다. • SYSTEM_EVENT이면 requested_by·request_idempotency_key·request_fingerprint는 NULL이며 원천 이벤트 식별값으로 배치와 dedupe_key를 구성합니다. • request_id·correlation_id는 API·로그 추적용이며 발송 결과 재조회 식별자는 dispatch_batch_id입니다. • 같은 org_id·requested_by·request_idempotency_key는 하나의 요청 지문과 dispatch_batch_id에만 대응해야 합니다. • TEAM_S';

comment on column reminder_dispatch.dispatch_id is '독촉 발송 요청 기본키이다.';

comment on column reminder_dispatch.assessment_round_id is '신규 독촉 발송이 참조하는 프로젝트 평가 회차이다.';

comment on column reminder_dispatch.team_id is '팀 단위 독촉의 원인이 된 팀이다.';

comment on column reminder_dispatch.user_id is '독촉 메시지의 실제 수신 사용자이다.';

comment on column reminder_dispatch.org_id is '독촉 발송의 직접 테넌트 경계이다.';

comment on column reminder_dispatch.analysis_job_id is '분석 실행 ID 값을 저장한다.';

comment on column reminder_dispatch.report_id is '리포트 ID 값을 저장한다.';

comment on column reminder_dispatch.trigger_type is '발송 계기 유형 값을 저장한다.';

comment on column reminder_dispatch.reason_code is '독촉 발송이 필요한 원인을 나타낸다.';

comment on column reminder_dispatch.message_template_code is '독촉 발송에 사용한 메시지 템플릿 버전 코드이다.';

comment on column reminder_dispatch.dispatch_batch_id is '동일 독촉 명령에서 확장된 수신자별 발송 행을 묶는 배치 ID이다.';

comment on column reminder_dispatch.request_idempotency_key is '요청 멱등성 키 값을 저장한다.';

comment on column reminder_dispatch.request_fingerprint is '요청 지문 값을 저장한다.';

comment on column reminder_dispatch.request_id is '요청 추적 ID 값을 저장한다.';

comment on column reminder_dispatch.correlation_id is '상관관계 ID 값을 저장한다.';

comment on column reminder_dispatch.channel is '독촉 발송 채널이다.';

comment on column reminder_dispatch.status is '독촉 발송 처리 상태이다.';

comment on column reminder_dispatch.suppression_reason_code is '발송 억제 사유 코드 값을 저장한다.';

comment on column reminder_dispatch.requested_by is '독촉 발송을 요청한 매니저 사용자이다.';

comment on column reminder_dispatch.requested_at is '독촉 발송 요청 생성 시각이다.';

comment on column reminder_dispatch.sent_at is '독촉 메시지 발송 성공 시각이다.';

comment on column reminder_dispatch.failure_code is '독촉 발송 실패의 안정 코드이다.';

comment on column reminder_dispatch.failure_reason is '독촉 발송 실패 상세 사유이다.';

comment on column reminder_dispatch.retry_count is '동일 발송 요청의 재시도 횟수이다.';

comment on column reminder_dispatch.dedupe_key is '동일 사유·대상·회차의 중복 발송 요청을 방지하는 키이다.';

comment on column reminder_dispatch.created_at is '독촉 발송 원장 생성 시각이다.';

alter table reminder_dispatch
    owner to postgres;

grant delete, insert, select, update on reminder_dispatch to teamiz_app;

create unique index uq_code_analysis_active
    on code_analysis (assessment_round_id, team_id, source_submission_id)
    where ((status)::text = 'ACTIVE'::text);

grant delete, insert, select, update on code_analysis to teamiz_app;

create table commit_attribution
(
    attribution_id      uuid    default gen_random_uuid() not null
        constraint pk_commit_attribution
            primary key,
    analysis_id         uuid                              not null
        constraint fk_commit_attribution_analysis_id
            references code_analysis
            on delete restrict,
    repository_id       uuid
        constraint fk_commit_attribution_repository_id
            references repository
            on delete restrict,
    attributed_user_id  uuid
        constraint fk_commit_attribution_attributed_user_id
            references app_user
            on delete restrict,
    branch_name         varchar(200)                      not null,
    commit_hash         varchar(128)                      not null,
    parent_commit_hash  varchar(128),
    commit_message      text,
    author_name         varchar(200)                      not null,
    commit_email        varchar(320)                      not null,
    authored_at         timestamp with time zone          not null,
    committed_at        timestamp with time zone          not null,
    is_merge_commit     boolean                           not null,
    is_revert_commit    boolean                           not null,
    is_bot_commit       boolean                           not null,
    addition_count      integer                           not null
        constraint ck_commit_attribution_addition_count
            check (addition_count >= 0),
    deletion_count      integer                           not null
        constraint ck_commit_attribution_deletion_count
            check (deletion_count >= 0),
    changed_line_count  integer default 0                 not null
        constraint ck_commit_attribution_changed_line_count
            check (changed_line_count >= 0),
    changed_file_count  integer                           not null
        constraint ck_commit_attribution_changed_file_count
            check (changed_file_count >= 0),
    attribution_status  varchar(30)                       not null,
    attribution_method  varchar(100)                      not null,
    verification_status varchar(100)                      not null,
    constraint uq_commit_attribution_analysis_id_repository_id_commit_hash
        unique (analysis_id, repository_id, commit_hash)
);

comment on table commit_attribution is '코드 분석 시점의 개별 Git 커밋 메타데이터·이메일 검증·사용자 귀속 결과를 불변으로 보존하는 원천 스냅샷입니다. | 정의서명: CommitAttribution | 제약·비고: • 행 Grain은 개별 커밋이며 UNIQUE(analysis_id, repository_id, commit_hash)를 적용합니다. • addition_count·deletion_count·changed_line_count·changed_file_count는 0 이상입니다. • attributed_user_id가 있으면 분석 회차의 유효 프로젝트 참여 교육생이어야 합니다. • 원천 작성자·이메일·commit SHA·merge/revert/bot 여부는 계산 정책에서 제외되더라도 삭제하지 않습니다. • 사용자별 집계 커밋 수·변경량·기여 비율은 ParticipantContributionSnapshot이 소유합니다. • 현재 AppUser 이메일 변경으로 과거 귀속 행을 갱신하지 않습니다.';

comment on column commit_attribution.attribution_id is '커밋 귀속 결과의 개별 레코드를 식별하는 고유 키이다.';

comment on column commit_attribution.analysis_id is '분석와의 업무 관계를 연결하는 외래 키이다.';

comment on column commit_attribution.repository_id is '커밋이 속한 저장소다. method=ZIP_WITH_GITLOG 제출은 repository 행이 없어 NULL 이다(2026-08-09, v08). 어느 분석의 커밋인지는 analysis_id(NOT NULL)가 특정하므로 추적에 공백이 생기지 않는다.';

comment on column commit_attribution.attributed_user_id is '커밋 귀속가 참조하는 app_user.user_id의 식별자이다.';

comment on column commit_attribution.branch_name is '브랜치명 값을 저장한다.';

comment on column commit_attribution.commit_hash is '커밋 귀속의 원문을 저장하지 않고 비교·무결성 검증에 사용하는 해시 값이다.';

comment on column commit_attribution.parent_commit_hash is '부모 커밋 SHA 값을 저장한다. root 커밋이거나 부모를 확인할 수 없으면 NULL이다(2026-08-07, S-18) — sentinel 문자열을 채우지 않는다.';

comment on column commit_attribution.commit_message is '커밋 메시지 원문이다. gitHistory[] 개별 커밋 항목에 message 가 아직 오지 않는 케이스가 있어 NULL을 허용한다(2026-08-07, S-19).';

comment on column commit_attribution.author_name is '작성자명 값을 저장한다.';

comment on column commit_attribution.commit_email is '커밋 귀속 업무에서 사용하는 커밋 이메일 값이다.';

comment on column commit_attribution.authored_at is '커밋 귀속의 AUTHORED 일시을 기록하는 시각이다.';

comment on column commit_attribution.committed_at is '커밋 일시 값을 저장한다.';

comment on column commit_attribution.is_merge_commit is '병합 커밋 여부 값을 저장한다.';

comment on column commit_attribution.is_revert_commit is '되돌리기 커밋 여부 값을 저장한다.';

comment on column commit_attribution.is_bot_commit is '봇 커밋 여부 값을 저장한다.';

comment on column commit_attribution.addition_count is '추가 라인 수 값을 저장한다.';

comment on column commit_attribution.deletion_count is '삭제 라인 수 값을 저장한다.';

comment on column commit_attribution.changed_line_count is '커밋 귀속의 CHANGED 줄 수을 수치로 기록한다.';

comment on column commit_attribution.changed_file_count is '변경 파일 수 값을 저장한다.';

comment on column commit_attribution.attribution_status is '귀속 상태 값을 저장한다.';

comment on column commit_attribution.attribution_method is '귀속 방식 값을 저장한다.';

comment on column commit_attribution.verification_status is '커밋 귀속의 검증 상태을 나타내는 코드이다. 허용값과 의미는 코드 서식 및 CHECK 제약을 따른다.';

alter table commit_attribution
    owner to postgres;

grant delete, insert, select, update on commit_attribution to teamiz_app;

create table project_requirement_assessment
(
    assessment_id            uuid                     default gen_random_uuid()            not null
        constraint pk_project_requirement_assessment
            primary key,
    requirement_id           uuid                                                          not null
        constraint fk_project_requirement_assessment_requirement_id
            references project_requirement
            on delete restrict,
    assessment_round_id      uuid                                                          not null
        constraint fk_project_requirement_assessment_assessment_round_id
            references project_assessment_round
            on delete restrict,
    team_id                  uuid                                                          not null
        constraint fk_project_requirement_assessment_team_id
            references team
            on delete restrict,
    org_id                   uuid                                                          not null
        constraint fk_project_requirement_assessment_org_id
            references organization
            on delete restrict,
    result                   varchar(30)              default 'PENDING'::character varying not null
        constraint ck_project_requirement_assessment_result
            check ((result)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('PASS'::character varying)::text, ('FAIL'::character varying)::text])),
    evidence_summary         text,
    evidence_locations       jsonb,
    source_submission_id     uuid
        constraint fk_project_requirement_assessment_source_submission_id
            references submission
            on delete restrict,
    analysis_id              uuid
        constraint fk_project_requirement_assessment_analysis_id
            references code_analysis
            on delete restrict,
    assessment_version       integer                                                       not null
        constraint ck_project_requirement_assessment_assessment_version
            check (assessment_version > 0),
    supersedes_assessment_id uuid
        constraint fk_project_requirement_assessment_supersedes_assessment_id
            references project_requirement_assessment
            on delete restrict,
    assessed_at              timestamp with time zone,
    assessed_by              uuid
        constraint fk_project_requirement_assessment_assessed_by
            references app_user
            on delete restrict,
    created_at               timestamp with time zone default CURRENT_TIMESTAMP            not null,
    assessment_note          text,
    -- [2026-08-25] PASS/FAIL 을 갈랐다. FAIL 만 evidence_summary NULL 을 허용한다 --
    -- AI 엔진은 FAIL 판정에 근거를 요구하지 않는 것이 의도된 설계다(2026-08-05 레드팀 감사:
    -- PASS 만 실제 소스와 대조 검증하고, 못 찾으면 FAIL 로 강등하므로 FAIL 에는 근거가 없다).
    -- 종전 CHECK 가 그 조합을 거부해 적재 트랜잭션이 통째로 롤백됐다(2026-08-24 미프 4차 15건).
    -- docs/migration/2026-08-25_requirement_assessment_fail_allows_no_evidence.sql
    constraint ck_project_requirement_assessment_result_fields
        check ((((result)::text = 'PENDING'::text) AND (evidence_summary IS NULL) AND (source_submission_id IS NULL) AND
                (analysis_id IS NULL) AND (assessed_at IS NULL) AND (assessed_by IS NULL)) OR
               (((result)::text = 'PASS'::text) AND (evidence_summary IS NOT NULL) AND
                (source_submission_id IS NOT NULL) AND (assessed_at IS NOT NULL) AND
                ((analysis_id IS NOT NULL) OR (assessed_by IS NOT NULL))) OR
               (((result)::text = 'FAIL'::text) AND
                (source_submission_id IS NOT NULL) AND (assessed_at IS NOT NULL) AND
                ((analysis_id IS NOT NULL) OR (assessed_by IS NOT NULL))))
);

comment on table project_requirement_assessment is '프로젝트 회차·팀의 특정 요구사항 버전을 최신 유효 제출과 분석 결과로 판정한 이력입니다. 재판정은 기존 행을 갱신하지 않고 새 버전 행으로 보존합니다. | 정의서명: ProjectRequirementAssessment | 제약·비고: • result: PENDING / PASS / FAIL, assessment_version > 0 • PENDING은 근거·분석·판정 시각·판정자가 NULL일 수 있습니다. • PASS·FAIL이면 evidence_summary·source_submission_id·analysis_id·assessed_at·assessed_by가 필수입니다. • source_submission_id와 analysis_id는 해당 팀의 동일 회차 최신 유효 제출과 그 제출에서 생성된 분석 원천이어야 합니다. • requirement_id·assessment_round_id·team_id·Submission·CodeAnalysis·org_id의 프로젝트·기관 경로가 일치해야 합니다. • evidence_locations는 코드 경로·시작/종료 줄·근거 해시를 가진 버전형 JSON Schema로 검증합니다. • 재판정은 supersedes_assessment_id로 이전 행을 가리키는 새 assessment_version을 생성하고 기존 행을 수정하지 않습니다.';

comment on column project_requirement_assessment.assessment_id is '프로젝트 요구사항 판정을 식별하는 기본키이다.';

comment on column project_requirement_assessment.requirement_id is '판정 대상 요구사항 버전을 참조한다.';

comment on column project_requirement_assessment.assessment_round_id is '판정 대상 프로젝트 회차를 참조한다.';

comment on column project_requirement_assessment.team_id is '판정 대상 제출 팀을 참조한다.';

comment on column project_requirement_assessment.org_id is '요구사항 판정의 기관 경계이다.';

comment on column project_requirement_assessment.result is '요구사항 구현 판정 상태이다.';

comment on column project_requirement_assessment.evidence_summary is '판정에 사용한 코드 근거 요약이다.';

comment on column project_requirement_assessment.evidence_locations is '코드 경로와 라인 범위 목록이다.';

comment on column project_requirement_assessment.source_submission_id is '판정 근거가 된 불변 분석 입력 보유 제출을 참조한다.';

comment on column project_requirement_assessment.analysis_id is '판정에 사용한 코드 분석 결과를 참조한다.';

comment on column project_requirement_assessment.assessment_version is '판정 로직 버전이다.';

comment on column project_requirement_assessment.supersedes_assessment_id is '현재 판정이 대체하는 이전 판정을 참조한다.';

comment on column project_requirement_assessment.assessed_at is 'PASS 또는 FAIL이 확정된 시각이다.';

comment on column project_requirement_assessment.assessed_by is '판정을 내린 사람. AI 분석이 낸 판정은 NULL 이며 출처는 analysis_id 가 가리킨다(2026-08-09, v08). 사람이 판정하거나 AI 판정을 뒤집은 경우에만 채운다 -- 이 컬럼의 NULL 여부가 곧 자동/수동 구분이다. PASS/FAIL 은 이 컬럼과 analysis_id 중 최소 하나만 있으면 기록된다.';

comment on column project_requirement_assessment.created_at is '판정 행이 생성된 시각이다.';

comment on column project_requirement_assessment.assessment_note is '요구사항 판정의 부가 설명과 특이사항을 기록한다.';

alter table project_requirement_assessment
    owner to postgres;

grant delete, insert, select, update on project_requirement_assessment to teamiz_app;

create table participant_contribution_snapshot
(
    contribution_snapshot_id       uuid                     default gen_random_uuid() not null
        constraint pk_participant_contribution_snapshot
            primary key,
    org_id                         uuid                                               not null
        constraint fk_participant_contribution_snapshot_org_id
            references organization
            on delete restrict,
    project_id                     uuid                                               not null
        constraint fk_participant_contribution_snapshot_project_id
            references project
            on delete restrict,
    assessment_round_id            uuid                                               not null
        constraint fk_participant_contribution_snapshot_assessment_round_id
            references project_assessment_round
            on delete restrict,
    repository_id                  uuid                                               not null
        constraint fk_participant_contribution_snapshot_repository_id
            references repository
            on delete restrict,
    team_id                        uuid                                               not null
        constraint fk_participant_contribution_snapshot_team_id
            references team
            on delete restrict,
    user_id                        uuid                                               not null
        constraint fk_participant_contribution_snapshot_user_id
            references app_user
            on delete restrict,
    analysis_job_id                uuid                                               not null,
    code_analysis_id               uuid                                               not null
        constraint fk_participant_contribution_snapshot_code_analysis_id
            references code_analysis
            on delete restrict,
    commit_range_strategy          text                                               not null
        constraint ck_participant_contribution_snapshot_commit_range_strategy
            check (commit_range_strategy = ANY
                   (ARRAY ['CUMULATIVE_FROM_PROJECT_START'::text, 'INCREMENTAL_FROM_PREVIOUS_ROUND'::text])),
    base_commit_sha                varchar(64)                                        not null,
    head_commit_sha                varchar(64)                                        not null,
    contribution_period_started_at timestamp with time zone                           not null,
    contribution_period_ended_at   timestamp with time zone                           not null,
    commit_count                   integer                                            not null
        constraint ck_participant_contribution_snapshot_commit_count
            check (commit_count >= 0),
    changed_file_count             integer                                            not null
        constraint ck_participant_contribution_snapshot_changed_file_count
            check (changed_file_count >= 0),
    addition_count                 integer                                            not null
        constraint ck_participant_contribution_snapshot_addition_count
            check (addition_count >= 0),
    deletion_count                 integer                                            not null
        constraint ck_participant_contribution_snapshot_deletion_count
            check (deletion_count >= 0),
    changed_line_count             integer                                            not null
        constraint ck_participant_contribution_snapshot_changed_line_count
            check (changed_line_count >= 0),
    contribution_value             numeric(18, 6)
        constraint ck_participant_contribution_snapshot_contribution_value
            check ((contribution_value IS NULL) OR (contribution_value >= (0)::numeric)),
    contribution_ratio             numeric(10, 4)
        constraint ck_participant_contribution_snapshot_contribution_ratio
            check ((contribution_ratio IS NULL) OR
                   ((contribution_ratio >= (0)::numeric) AND (contribution_ratio <= (1)::numeric))),
    attribution_coverage_rate      numeric(18, 6)
        constraint ck_participant_contribution_snapshot_attribution_coverage_rate
            check ((attribution_coverage_rate IS NULL) OR
                   ((attribution_coverage_rate >= (0)::numeric) AND (attribution_coverage_rate <= (1)::numeric))),
    calculation_policy_version     integer                                            not null,
    status                         varchar(30)                                        not null
        constraint ck_participant_contribution_snapshot_status
            check ((status)::text = ANY
                   (ARRAY [('PENDING'::character varying)::text, ('AVAILABLE'::character varying)::text, ('INSUFFICIENT_ATTRIBUTION'::character varying)::text, ('FAILED'::character varying)::text])),
    failure_code                   varchar(100),
    calculated_at                  timestamp with time zone,
    created_at                     timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint uq_participant_contribution_snapshot_analysis_job_id_assessm
        unique (analysis_job_id, assessment_round_id, user_id),
    constraint ck_participant_contribution_snapshot_contribution_period_ended_
        check (contribution_period_ended_at > contribution_period_started_at),
    constraint ck_participant_contribution_snapshot_status_2
        check ((((status)::text = 'PENDING'::text) AND (contribution_value IS NULL) AND (contribution_ratio IS NULL) AND
                (attribution_coverage_rate IS NULL) AND (calculated_at IS NULL) AND (failure_code IS NULL)) OR
               (((status)::text = 'AVAILABLE'::text) AND (contribution_value IS NOT NULL) AND
                (contribution_ratio IS NOT NULL) AND (attribution_coverage_rate IS NOT NULL) AND
                (calculated_at IS NOT NULL) AND (failure_code IS NULL)) OR (((status)::text = ANY
                                                                             (ARRAY [('INSUFFICIENT_ATTRIBUTION'::character varying)::text, ('FAILED'::character varying)::text])) AND
                                                                            (failure_code IS NOT NULL)))
);

comment on table participant_contribution_snapshot is '빅프로젝트 회차에서 교육생별 GitHub 귀속 커밋 범위와 기여도 계산 결과를 실행별로 불변 보존하는 APPEND-ONLY 스냅샷입니다. | 정의서명: ParticipantContributionSnapshot | 제약·비고: • 상태: PENDING / AVAILABLE / INSUFFICIENT_ATTRIBUTION / FAILED • UNIQUE(analysis_job_id, assessment_round_id, user_id) • commit_range_strategy: CUMULATIVE_FROM_PROJECT_START / INCREMENTAL_FROM_PREVIOUS_ROUND • contribution_period_ended_at > contribution_period_started_at • 집계 수치는 0 이상이고 contribution_value는 NULL 또는 0 이상입니다. • contribution_ratio·attribution_coverage_rate는 NULL 또는 0~1입니다. • AVAILABLE이면 contribution_value·contribution_ratio·attribution_coverage_rate·calculated_at이 필수이고 failure_code는 NULL입니다. • INSUFFICIENT_ATTRIBUTION·FAILED이면 failure_code가 필수입니다. • 기관·프로젝트·회차·저장소·팀·사용자·작업·코드 분석 경로가 일치해야 합니다. • 재분석은 기존 행을 갱신하지 않고 새 analysis_job_id와 새 스냅샷을 생성합니다. • 기여도와 이해도를 하나의 합산 점수로 저장하지 않습니다.';

comment on column participant_contribution_snapshot.contribution_snapshot_id is '기여도 스냅샷 ID 값을 저장한다.';

comment on column participant_contribution_snapshot.org_id is '기관 ID 값을 저장한다.';

comment on column participant_contribution_snapshot.project_id is '프로젝트 ID 값을 저장한다.';

comment on column participant_contribution_snapshot.assessment_round_id is '평가 회차 ID 값을 저장한다.';

comment on column participant_contribution_snapshot.repository_id is '저장소 ID 값을 저장한다.';

comment on column participant_contribution_snapshot.team_id is '팀 ID 값을 저장한다.';

comment on column participant_contribution_snapshot.user_id is '사용자 ID 값을 저장한다.';

comment on column participant_contribution_snapshot.analysis_job_id is '분석 작업 ID 값을 저장한다.';

comment on column participant_contribution_snapshot.code_analysis_id is '코드 분석 ID 값을 저장한다.';

comment on column participant_contribution_snapshot.commit_range_strategy is '커밋 범위 전략 값을 저장한다.';

comment on column participant_contribution_snapshot.base_commit_sha is '기준 커밋 SHA 값을 저장한다.';

comment on column participant_contribution_snapshot.head_commit_sha is '종료 커밋 SHA 값을 저장한다.';

comment on column participant_contribution_snapshot.contribution_period_started_at is '기여 기간 시작 일시 값을 저장한다.';

comment on column participant_contribution_snapshot.contribution_period_ended_at is '기여 기간 종료 일시 값을 저장한다.';

comment on column participant_contribution_snapshot.commit_count is '커밋 수 값을 저장한다.';

comment on column participant_contribution_snapshot.changed_file_count is '변경 파일 수 값을 저장한다.';

comment on column participant_contribution_snapshot.addition_count is '추가 라인 수 값을 저장한다.';

comment on column participant_contribution_snapshot.deletion_count is '삭제 라인 수 값을 저장한다.';

comment on column participant_contribution_snapshot.changed_line_count is '변경 라인 수 값을 저장한다.';

comment on column participant_contribution_snapshot.contribution_value is '기여도 원값 값을 저장한다.';

comment on column participant_contribution_snapshot.contribution_ratio is '팀 내 기여 비율 값을 저장한다.';

comment on column participant_contribution_snapshot.attribution_coverage_rate is '귀속 커버리지 값을 저장한다.';

comment on column participant_contribution_snapshot.calculation_policy_version is '계산 정책 버전 값을 저장한다.';

comment on column participant_contribution_snapshot.status is '상태 값을 저장한다.';

comment on column participant_contribution_snapshot.failure_code is '실패 코드 값을 저장한다.';

comment on column participant_contribution_snapshot.calculated_at is '계산 완료 일시 값을 저장한다.';

comment on column participant_contribution_snapshot.created_at is '생성 일시 값을 저장한다.';

alter table participant_contribution_snapshot
    owner to postgres;

grant delete, insert, select, update on participant_contribution_snapshot to teamiz_app;

create unique index uq_project_extraction_scope_current
    on project_extraction_scope (assessment_round_id)
    where (effective_to IS NULL);

grant delete, insert, select, update on project_extraction_scope to teamiz_app;

create table question_focus_item
(
    question_focus_item_id uuid                     default gen_random_uuid() not null
        constraint pk_question_focus_item
            primary key,
    name                   varchar(200)                                       not null,
    description            text                                               not null,
    active                 boolean                  default true              not null,
    created_at             timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at             timestamp with time zone default CURRENT_TIMESTAMP not null
);

comment on table question_focus_item is '프로젝트 회차의 질문 생성 방향으로 선택할 수 있는 공통 분류 항목 마스터입니다. 프로젝트 구현 요구사항과 분리하며, 실제 회차 선택과 적용 버전은 AssessmentRoundQuestionFocus가 보존합니다. | 정의서명: QuestionFocusItem | 제약·비고: • 테이블 분류: MASTER • question_focus_item_id를 외부·관계의 안정 식별자로 사용하고 name은 표시 문구로 사용합니다. • active=FALSE인 항목은 신규 AssessmentRoundQuestionFocus 집합에 선택할 수 없습니다. • 이미 회차에서 참조한 항목은 물리 삭제하지 않습니다. • 참조된 항목의 의미·질문 생성 방향을 바꾸는 변경은 기존 행을 덮어쓰지 않고 새 question_focus_item_id로 등록합니다. • 기존 행의 updated_at 변경은 오탈자·표시 보정처럼 과거 질문 생성 의미를 바꾸지 않는 범위로 제한합니다. • 별도 taxonomy_version과 문자열 code 속성은 현재 요구사항에서 확정되지 않았으므로 두지 않습니다.';

comment on column question_focus_item.question_focus_item_id is '질문 생성에 사용할 질문 초점 분류 항목의 안정 식별자이다.';

comment on column question_focus_item.name is '질문 초점 항목의 화면 표시 명칭이다.';

comment on column question_focus_item.description is '질문 생성 방향과 적용 범위를 설명한다.';

comment on column question_focus_item.active is '신규 회차 질문 초점 집합에서 선택 가능한지 나타낸다.';

comment on column question_focus_item.created_at is '레코드가 최초 생성된 시각이다.';

comment on column question_focus_item.updated_at is '레코드가 마지막으로 변경된 시각이다.';

alter table question_focus_item
    owner to postgres;

create table assessment_problem
(
    problem_id                      uuid                     default gen_random_uuid() not null
        constraint pk_assessment_problem
            primary key,
    org_id                          uuid                                               not null
        constraint fk_assessment_problem_org_id
            references organization
            on delete restrict,
    code_analysis_id                uuid                                               not null
        constraint fk_assessment_problem_code_analysis_id
            references code_analysis
            on delete restrict,
    problem_scope                   varchar(100)                                       not null
        constraint ck_assessment_problem_problem_scope
            check ((problem_scope)::text = ANY
                   (ARRAY [('TEAM_SHARED_PROBLEM'::character varying)::text, ('INDIVIDUAL_OWN_COMMIT'::character varying)::text])),
    project_verification_concept_id uuid
        constraint fk_assessment_problem_project_verification_concept_id
            references project_verification_concept
            on delete restrict,
    measurement_attempt_id          uuid
        constraint fk_assessment_problem_measurement_attempt_id
            references measurement_attempt
            on delete restrict,
    target_user_id                  uuid
        constraint fk_assessment_problem_target_user_id
            references app_user
            on delete restrict,
    contribution_snapshot_id        uuid
        constraint fk_assessment_problem_contribution_snapshot_id
            references participant_contribution_snapshot
            on delete restrict,
    problem_no                      integer                                            not null
        constraint ck_assessment_problem_problem_no
            check ((problem_no >= 1) AND (problem_no <= 3)),
    title                           text,
    source_snippet_key              varchar(128),
    code_language                   varchar(30)
        constraint ck_assessment_problem_code_language
            check ((code_language IS NULL) OR (length(TRIM(BOTH FROM code_language)) > 0)),
    source_path                     text,
    source_line_start               integer
        constraint ck_assessment_problem_source_line_start
            check ((source_line_start IS NULL) OR (source_line_start > 0)),
    source_line_end                 integer,
    code_snippet_hash               varchar(128),
    best_success_stage              varchar(10)
        constraint ck_assessment_problem_best_success_stage
            check ((best_success_stage IS NULL) OR ((best_success_stage)::text = ANY
                                                    (ARRAY [('L1'::character varying)::text, ('L2'::character varying)::text, ('L3'::character varying)::text, ('L4'::character varying)::text]))),
    generation_status               varchar(30)                                        not null
        constraint ck_assessment_problem_generation_status
            check ((generation_status)::text = ANY
                   (ARRAY [('GENERATED'::character varying)::text, ('NOT_GENERATED'::character varying)::text])),
    not_generated_reason_code       varchar(50)
        constraint ck_assessment_problem_not_generated_reason_code
            check ((not_generated_reason_code IS NULL) OR
                   ((not_generated_reason_code)::text = 'NO_MATCHING_CODE_EVIDENCE'::text)),
    extractor_version               integer
        constraint ck_assessment_problem_extractor_version
            check (extractor_version > 0),
    created_at                      timestamp with time zone default CURRENT_TIMESTAMP not null,
    not_generated_reason_detail     text,
    problem_type                    varchar(50),
    priority                        numeric(6, 4)
        constraint ck_assessment_problem_priority
            check ((priority IS NULL) OR (priority >= (0)::numeric)),
    question_focus_item_id          uuid
        constraint fk_assessment_problem_question_focus_item_id
            references question_focus_item
            on delete restrict,
    teaches_id                      uuid
        constraint fk_assessment_problem_teaches_id
            references teaches
            on delete restrict,
    constraint uq_assessment_problem_code_analysis_id_problem_no
        unique (code_analysis_id, problem_no),
    constraint uq_assessment_problem_code_analysis_id_project_verification_
        unique (code_analysis_id, project_verification_concept_id),
    constraint uq_assessment_problem_measurement_attempt_id_problem_no
        unique (measurement_attempt_id, problem_no),
    constraint ck_assessment_problem_best_success_stage_2
        check (((problem_scope)::text <> 'TEAM_SHARED_PROBLEM'::text) OR (best_success_stage IS NULL)),
    constraint ck_assessment_problem_best_success_stage_3
        check (((generation_status)::text <> 'NOT_GENERATED'::text) OR (best_success_stage IS NULL)),
    constraint ck_assessment_problem_generation_status_2
        check ((((generation_status)::text = 'GENERATED'::text) AND (title IS NOT NULL) AND
                (source_snippet_key IS NOT NULL) AND (code_language IS NOT NULL) AND (source_path IS NOT NULL) AND
                (source_line_start IS NOT NULL) AND (source_line_end IS NOT NULL) AND
                (code_snippet_hash IS NOT NULL) AND (not_generated_reason_code IS NULL)) OR
               (((generation_status)::text = 'NOT_GENERATED'::text) AND (title IS NULL) AND
                (source_snippet_key IS NULL) AND (code_language IS NULL) AND (source_path IS NULL) AND
                (source_line_start IS NULL) AND (source_line_end IS NULL) AND (code_snippet_hash IS NULL) AND
                ((not_generated_reason_code)::text = 'NO_MATCHING_CODE_EVIDENCE'::text) AND
                (best_success_stage IS NULL))),
    constraint ck_assessment_problem_problem_scope_2
        check ((((problem_scope)::text = 'TEAM_SHARED_PROBLEM'::text) AND (measurement_attempt_id IS NULL) AND
                (target_user_id IS NULL) AND (contribution_snapshot_id IS NULL)) OR
               (((problem_scope)::text = 'INDIVIDUAL_OWN_COMMIT'::text) AND
                (project_verification_concept_id IS NULL) AND (measurement_attempt_id IS NOT NULL) AND
                (target_user_id IS NOT NULL) AND (contribution_snapshot_id IS NOT NULL))),
    constraint ck_assessment_problem_question_focus_item_id
        check (((generation_status)::text = 'GENERATED'::text) OR (question_focus_item_id IS NULL)),
    constraint ck_assessment_problem_source_line_end
        check ((source_line_end IS NULL) OR (source_line_end >= source_line_start))
);

comment on table assessment_problem is '프로젝트와 연결된 검증 개념 3건의 출제 계획과 실제 문제 생성 결과를 보존하고, 별도 문제 근거 테이블의 대표 스니펫 식별·위치·해시 정보를 함께 관리합니다. 코드 스니펫 원문은 Submission에 저장하며 코드 근거를 찾지 못한 개념도 문제 슬롯 행을 유지하여 미출제 상태를 명시합니다. | 정의서명: AssessmentProblem | 제약·비고: • problem_scope: TEAM_SHARED_PROBLEM / INDIVIDUAL_OWN_COMMIT • generation_status: GENERATED / NOT_GENERATED • problem_no IN (1,2,3)이며 계획된 검증 개념 3건을 모두 AssessmentProblem 행으로 기록합니다. • TEAM_SHARED_PROBLEM은 팀 코드 분석에서 한 번 생성된 문제 콘텐츠·스니펫 참조 정보 또는 미출제 슬롯을 팀원 개인 세션들이 공통 참조합니다. 문제 콘텐츠의 공유 범위만 의미하며 세션·단계·답변·점수·통과 결과는 교육생별로 독립 관리합니다. • TEAM_SHARED_PROBLEM이면 project_verification_concept_id가 필수이고 measurement_attempt_id·target_user_id·contribution_snapshot_id는 NULL입니다. • TEAM_SHARED_PROBLEM은 UNIQUE(code_analysis_id, problem_no), UNIQUE(code_analysis_id, project_verification_concept_id)를 적용합니다. • INDIVIDUAL_OWN_COMMIT이면 project_verification_concept_id는 NULL이고 measurement_attempt_id·target_user_id·contributi • 문제가 참조하는 근거 목록은 AssessmentProblemReference가 소유하며 대표 블록의 좌표·해시만 본 테이블에 중복 보존합니다. • problem_type·priority·question_focus_item_id는 AI 분석 응답을 보존하기 위해 2026-08-06에 추가했으며 생성 상태별 NOT NULL 묶음에는 포함하지 않습니다(S-05).';

comment on column assessment_problem.problem_id is '계획된 검증 개념의 문제 슬롯 또는 실제 생성된 코드 기반 문제를 식별하는 고유 키이다.';

comment on column assessment_problem.org_id is '기관 ID 값을 저장한다.';

comment on column assessment_problem.code_analysis_id is '평가 문제 슬롯과 생성 결과가 참조하는 코드 분석 ID이다.';

comment on column assessment_problem.problem_scope is '문제 콘텐츠의 공유·귀속 범위를 나타낸다.';

comment on column assessment_problem.project_verification_concept_id is 'TEAM_SHARED_PROBLEM 이 검증하는 프로젝트 검증 개념이다. AI 분석 응답에 개념 식별자가 없어 2026-08-09(v08)부터 NULL 을 허용한다 — teaches_id 로 역매핑이 가능해진 뒤 채운다. INDIVIDUAL_OWN_COMMIT 에서는 항상 NULL 이다.';

comment on column assessment_problem.measurement_attempt_id is '측정 수행 ID 값을 저장한다.';

comment on column assessment_problem.target_user_id is '대상 사용자 ID 값을 저장한다.';

comment on column assessment_problem.contribution_snapshot_id is '기여도 스냅샷 ID 값을 저장한다.';

comment on column assessment_problem.problem_no is '코드 분석 또는 개인 수행 안에서 계획된 문제 슬롯의 표시 순서를 기록한다.';

comment on column assessment_problem.title is 'GENERATED 문제의 제목을 기록하며 NOT_GENERATED이면 NULL이다.';

comment on column assessment_problem.source_snippet_key is 'GENERATED 문제 생성과 출제에 사용한 Submission.code_snippets 원소의 안정적인 식별 키를 저장한다.';

comment on column assessment_problem.code_language is '대표 코드 스니펫의 프로그래밍 언어 또는 파일 형식을 기록한다.';

comment on column assessment_problem.source_path is '대표 코드 스니펫이 추출된 제출 코드의 파일 경로를 기록한다.';

comment on column assessment_problem.source_line_start is '대표 코드 스니펫의 원천 파일 내 시작 라인을 기록한다.';

comment on column assessment_problem.source_line_end is '대표 코드 스니펫의 원천 파일 내 종료 라인을 기록한다.';

comment on column assessment_problem.code_snippet_hash is '문제 출제에 사용한 대표 코드 스니펫 원문의 동일성·무결성 검증 해시를 기록한다.';

comment on column assessment_problem.best_success_stage is '문제에 속한 L1~L4 단계 중 통과 상태에 도달한 가장 높은 평가 축 코드를 저장한다.';

comment on column assessment_problem.generation_status is '계획된 검증 개념에 대응하는 실제 문제 생성 여부를 저장한다.';

comment on column assessment_problem.not_generated_reason_code is '문제를 생성하지 못한 원인을 기록한다.';

comment on column assessment_problem.extractor_version is '문제 근거 스니펫을 선택·추출한 규칙 또는 추출기 버전을 저장한다.';

comment on column assessment_problem.created_at is '문제 계획 슬롯과 생성 결과 레코드가 최초 생성된 시각이다.';

comment on column assessment_problem.not_generated_reason_detail is '문제를 생성하지 못한 사유의 원문 서술을 보존한다.';

comment on column assessment_problem.problem_type is 'AI 분석 응답 problems[].problemType을 보존한다. 값 집합이 확정되지 않아 CHECK를 걸지 않았다(2026-08-06). 현재 확인된 값은 DESIGN_CHOICE 1종이다.';

comment on column assessment_problem.priority is 'AI 분석 응답 problems[].priority를 보존한다. 출제 순서 자체는 problem_no가 소유하며 이 값은 참고 지표다.';

comment on column assessment_problem.question_focus_item_id is 'AI 분석 응답 problems[].questionFocusItemId를 보존한다. 회차의 유효 초점 집합 버전은 AnalysisJob.question_focus_version_no가 고정하며 이 컬럼은 문제 단위 귀속을 남긴다. 미출제 슬롯에는 초점 항목이 없다.';

comment on column assessment_problem.teaches_id is 'AI 분석 응답 Problem.teachId 를 그대로 보존한다(2026-08-09, v08). project_verification_concept_id 가 NULL 일 때 이 값이 문제와 교안 개념을 잇는 유일한 연결이다. AI 가 개념을 특정하지 못하면 NULL 이다.';

alter table assessment_problem
    owner to postgres;

create index ix_assessment_problem_question_focus_item_id
    on assessment_problem (question_focus_item_id)
    where (question_focus_item_id IS NOT NULL);

create index ix_assessment_problem_teaches_id
    on assessment_problem (teaches_id)
    where (teaches_id IS NOT NULL);

create unique index uq_assessment_problem_individual_no
    on assessment_problem (measurement_attempt_id, problem_no)
    where ((problem_scope)::text = 'INDIVIDUAL_OWN_COMMIT'::text);

create unique index uq_assessment_problem_team_concept
    on assessment_problem (code_analysis_id, project_verification_concept_id)
    where ((problem_scope)::text = 'TEAM_SHARED_PROBLEM'::text);

create unique index uq_assessment_problem_team_no
    on assessment_problem (code_analysis_id, problem_no)
    where ((problem_scope)::text = 'TEAM_SHARED_PROBLEM'::text);

grant delete, insert, select, update on assessment_problem to teamiz_app;

create table assessment_problem_reference
(
    problem_reference_id uuid                     default gen_random_uuid() not null
        constraint pk_assessment_problem_reference
            primary key,
    problem_id           uuid                                               not null
        constraint fk_assessment_problem_reference_problem_id
            references assessment_problem
            on delete cascade,
    org_id               uuid                                               not null
        constraint fk_assessment_problem_reference_org_id
            references organization
            on delete restrict,
    reference_type       varchar(30)                                        not null
        constraint ck_assessment_problem_reference_reference_type
            check ((reference_type)::text = ANY
                   (ARRAY [('PRIMARY_BLOCK'::character varying)::text, ('QUESTION_HIGHLIGHT'::character varying)::text, ('CURRICULUM_EVIDENCE'::character varying)::text, ('CALLER'::character varying)::text, ('RELATED_CONTEXT'::character varying)::text])),
    display_order        integer                                            not null
        constraint ck_assessment_problem_reference_display_order
            check (display_order > 0),
    source_path          text,
    source_line_start    integer
        constraint ck_assessment_problem_reference_source_line_start
            check ((source_line_start IS NULL) OR (source_line_start > 0)),
    source_line_end      integer,
    axis_code            varchar(10)
        constraint ck_assessment_problem_reference_axis_code
            check ((axis_code IS NULL) OR ((axis_code)::text = ANY
                                           (ARRAY [('L1'::character varying)::text, ('L2'::character varying)::text, ('L3'::character varying)::text, ('L4'::character varying)::text]))),
    teaches_id           uuid
        constraint fk_assessment_problem_reference_teaches_id
            references teaches
            on delete restrict,
    evidence_hash        varchar(128)                                       not null,
    created_at           timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint uq_assessment_problem_reference_problem_id_display_order
        unique (problem_id, display_order),
    constraint ck_assessment_problem_reference_shape
        check ((((reference_type)::text = 'QUESTION_HIGHLIGHT'::text) AND (source_path IS NOT NULL) AND
                (source_line_start IS NOT NULL) AND (source_line_end IS NOT NULL) AND (axis_code IS NOT NULL)) OR
               (((reference_type)::text = ANY
                 (ARRAY [('PRIMARY_BLOCK'::character varying)::text, ('CALLER'::character varying)::text, ('RELATED_CONTEXT'::character varying)::text])) AND
                (source_path IS NOT NULL) AND (source_line_start IS NOT NULL) AND (source_line_end IS NOT NULL)) OR
               (((reference_type)::text = 'CURRICULUM_EVIDENCE'::text) AND (teaches_id IS NOT NULL))),
    constraint ck_assessment_problem_reference_source_line_end
        check ((source_line_end IS NULL) OR (source_line_end >= source_line_start))
);

comment on table assessment_problem_reference is '문제 하나가 참조하는 근거 목록입니다. 대표 코드 블록(PRIMARY_BLOCK) 외에 단계별 하이라이트(QUESTION_HIGHLIGHT), 교안 개념 증적(CURRICULUM_EVIDENCE), 호출 지점(CALLER)을 유형별로 보존합니다. | 정의서명: AssessmentProblemReference | 제약·비고: • 유형: PRIMARY_BLOCK / QUESTION_HIGHLIGHT / CURRICULUM_EVIDENCE / CALLER • UNIQUE(problem_id, display_order) • 문제당 PRIMARY_BLOCK 최대 1건, 문제당 axis_code별 QUESTION_HIGHLIGHT 최대 1건 • QUESTION_HIGHLIGHT는 코드 좌표와 axis_code가 필수이고 teaches_id는 NULL입니다. • PRIMARY_BLOCK·CALLER는 코드 좌표가 필수이고 axis_code·teaches_id는 NULL입니다. • CURRICULUM_EVIDENCE는 teaches_id가 필수이고 코드 좌표·axis_code는 NULL입니다. • 근거는 팀 공통이므로 교육생별 ProblemStage에 복제하지 않고 문제에 1회만 보존합니다. • 재분석은 기존 행을 갱신하지 않고 새 AssessmentProblem과 함께 새로 생성하며 problem_id 삭제 시 함께 삭제됩니다. • AI 분석 응답 references[]를 그대로 보존하기 위해 2026-08-06에 신설했습니다(S-06).';

comment on column assessment_problem_reference.problem_reference_id is '문제 근거 행을 식별하는 고유 키이다.';

comment on column assessment_problem_reference.problem_id is '근거가 속한 평가 문제의 ID다. 문제가 삭제되면 근거도 함께 삭제된다.';

comment on column assessment_problem_reference.org_id is '기관 ID다. AssessmentProblem·CodeAnalysis와 기관 경로가 일치해야 한다.';

comment on column assessment_problem_reference.reference_type is '근거의 유형을 나타내는 코드다. PRIMARY_BLOCK은 문제의 대표 코드 블록이며 문제당 1건이다. QUESTION_HIGHLIGHT는 L1~L4 단계별로 강조할 코드 구간이다. CALLER는 대표 블록을 호출하는 지점이며 L3·L4 문맥 근거로 사용한다. RELATED_CONTEXT는 문제 이해에 필요한 그 밖의 관련 코드 구간이다(2026-08-06, S-11). CURRICULUM_EVIDENCE는 문제 근거가 된 교안 개념의 증적이며 코드 좌표 대신 teaches_id를 가진다.';

comment on column assessment_problem_reference.display_order is '문제 내에서 근거를 표시할 순서다. AI 분석 응답 references[].displayOrder를 그대로 보존한다.';

comment on column assessment_problem_reference.source_path is '근거 코드가 위치한 저장소 내 파일 경로다. CURRICULUM_EVIDENCE에서는 NULL이다.';

comment on column assessment_problem_reference.source_line_start is '근거 코드 구간의 시작 라인 번호다. CURRICULUM_EVIDENCE에서는 NULL이다.';

comment on column assessment_problem_reference.source_line_end is '근거 코드 구간의 종료 라인 번호다. CURRICULUM_EVIDENCE에서는 NULL이다.';

comment on column assessment_problem_reference.axis_code is '근거를 사용하는 단계의 축 코드다. QUESTION_HIGHLIGHT이면 필수이고, 그 밖의 유형에도 AI가 부가 정보로 채워 보내면 그대로 보존한다(2026-08-06, S-11). ProblemStage.axis_code와 같은 값 집합을 쓰지만 근거는 팀 공통이므로 세션별 단계 행에 복제하지 않는다.';

comment on column assessment_problem_reference.teaches_id is '근거가 가리키는 교안 개념의 ID다. CURRICULUM_EVIDENCE이면 필수이고, 그 밖의 유형에도 AI가 함께 보내면 그대로 보존한다(2026-08-06, S-11).';

comment on column assessment_problem_reference.evidence_hash is '근거의 내용 해시다. AI 분석 응답 references[].evidenceHash를 그대로 보존하며 동일 근거 판별에 사용한다.';

comment on column assessment_problem_reference.created_at is '근거 행이 생성된 시각이다.';

alter table assessment_problem_reference
    owner to postgres;

create index ix_assessment_problem_reference_problem_id
    on assessment_problem_reference (problem_id);

create unique index uq_assessment_problem_reference_highlight_axis
    on assessment_problem_reference (problem_id, axis_code)
    where ((reference_type)::text = 'QUESTION_HIGHLIGHT'::text);

create unique index uq_assessment_problem_reference_primary
    on assessment_problem_reference (problem_id)
    where ((reference_type)::text = 'PRIMARY_BLOCK'::text);

grant delete, insert, select, update on assessment_problem_reference to teamiz_app;

create table assessment_session
(
    session_id                 uuid                     default gen_random_uuid()          not null
        constraint pk_assessment_session
            primary key,
    org_id                     uuid                                                        not null
        constraint fk_assessment_session_org_id
            references organization
            on delete restrict,
    attempt_id                 uuid                                                        not null
        constraint uq_assessment_session_attempt_id
            unique
        constraint fk_assessment_session_attempt_id
            references measurement_attempt
            on delete restrict,
    current_problem_id         uuid
        constraint fk_assessment_session_current_problem_id
            references assessment_problem
            on delete restrict,
    current_problem_stage_id   uuid,
    status                     varchar(100)             default 'READY'::character varying not null
        constraint ck_assessment_session_status
            check ((status)::text = ANY
                   (ARRAY [('READY'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('PAUSED'::character varying)::text, ('COMPLETED'::character varying)::text, ('INTERRUPTED'::character varying)::text, ('INVALID'::character varying)::text, ('FAILED'::character varying)::text, ('SUPERSEDED'::character varying)::text])),
    end_reason_code            varchar(100)
        constraint ck_assessment_session_end_reason_code
            check ((end_reason_code)::text = ANY
                   (ARRAY [('ALL_PROBLEMS_TERMINAL'::character varying)::text, ('ALL_REVIEW_TARGETS_TERMINAL'::character varying)::text, ('COMPLETED_L4'::character varying)::text, ('TERMINATED_AT_L1'::character varying)::text, ('TERMINATED_AT_L2'::character varying)::text, ('TERMINATED_AT_L3'::character varying)::text, ('TERMINATED_AT_L4'::character varying)::text, ('POLICY_TIME_LIMIT_EXCEEDED'::character varying)::text, ('ASSESSMENT_WINDOW_EXPIRED'::character varying)::text, ('REVIEW_DUE_AT_EXPIRED'::character varying)::text, ('DATA_INTEGRITY_INVALID'::character varying)::text, ('ADMIN_INVALIDATED'::character varying)::text, ('TECHNICAL_FAILURE'::character varying)::text])),
    intro_acknowledged_at      timestamp with time zone,
    intro_notice_version       integer,
    started_at                 timestamp with time zone,
    last_saved_at              timestamp with time zone,
    ended_at                   timestamp with time zone,
    policy_time_limit_at       timestamp with time zone,
    window_leave_count         integer                                                     not null
        constraint ck_assessment_session_window_leave_count
            check (window_leave_count >= 0),
    total_away_seconds         integer                                                     not null
        constraint ck_assessment_session_total_away_seconds
            check (total_away_seconds >= 0),
    last_window_left_at        timestamp with time zone,
    last_window_returned_at    timestamp with time zone,
    connection_loss_count      integer                                                     not null
        constraint ck_assessment_session_connection_loss_count
            check (connection_loss_count >= 0),
    total_disconnected_seconds integer                                                     not null
        constraint ck_assessment_session_total_disconnected_seconds
            check (total_disconnected_seconds >= 0),
    ended_axis_code            varchar(10)
        constraint ck_assessment_session_ended_axis_code
            check ((ended_axis_code IS NULL) OR ((ended_axis_code)::text = ANY
                                                 (ARRAY [('L1'::character varying)::text, ('L2'::character varying)::text, ('L3'::character varying)::text, ('L4'::character varying)::text]))),
    row_version                bigint                   default 0                          not null
        constraint ck_assessment_session_row_version
            check (row_version >= 0),
    updated_at                 timestamp with time zone default CURRENT_TIMESTAMP          not null,
    constraint ck_assessment_session_current_problem_stage_id
        check ((current_problem_stage_id IS NULL) OR (current_problem_id IS NOT NULL))
);

comment on table assessment_session is '교육생 수행의 실제 인터랙티브 진행 위치·시작 안내·복구·무결성 요약을 관리합니다. 사용자·회차·분석·평가 구조 계약·응시 마감은 MeasurementAttempt와 상위 원장에서 파생합니다. | 정의서명: AssessmentSession | 제약·비고: • 상태: READY / IN_PROGRESS / PAUSED / COMPLETED / INTERRUPTED / INVALID / FAILED / SUPERSEDED • UNIQUE(attempt_id), MeasurementAttempt 1:0..1 • AssessmentSession은 팀 단위로 공유하지 않으며 팀원별 MeasurementAttempt마다 별도 session_id를 생성합니다. • IN_PROGRESS 또는 started_at이 있으면 intro_acknowledged_at·intro_notice_version이 필수입니다. • COMPLETED·INTERRUPTED·INVALID·FAILED·SUPERSEDED에서는 ended_at과 end_reason_code가 필수입니다. • current_problem_stage_id가 있으면 current_problem_id가 필수이고 해당 단계의 session_id가 현재 세션, problem_id가 현재 문제와 일치해야 합니다. • 현재 문제는 미니프로젝트이면 수행 code_analysis_id의 TEAM_SHARED_PROBLEM 문제 원본, 빅프로젝트이면 본 수행의 INDIVIDUAL_OWN_COMMIT 문제여야 합니다. • 정책이 실제 종료형 시간 제한을 가지는 경우에만 policy_time_limit_at을 계산하고 정책 값을 세션에 복제하지 않습니다. • window_leave_count·connection_loss_count와 누적 시간은 0 이상입니다. •';

comment on column assessment_session.session_id is '교육생 검증 세션의 개별 레코드를 식별하는 고유 키이다.';

comment on column assessment_session.org_id is '기관 ID 값을 저장한다.';

comment on column assessment_session.attempt_id is '측정 수행 원장을 참조한다.';

comment on column assessment_session.current_problem_id is '현재 문제 ID 값을 저장한다.';

comment on column assessment_session.current_problem_stage_id is '현재 문제 단계 ID 값을 저장한다.';

comment on column assessment_session.status is '검증 세션의 상태를 나타낸다.';

comment on column assessment_session.end_reason_code is '종료 사유 코드 값을 저장한다.';

comment on column assessment_session.intro_acknowledged_at is '시작 안내 확인 일시 값을 저장한다.';

comment on column assessment_session.intro_notice_version is '시작 안내 버전 값을 저장한다.';

comment on column assessment_session.started_at is '세션 시작 시각이다.';

comment on column assessment_session.last_saved_at is '마지막 정상 저장 시각이다.';

comment on column assessment_session.ended_at is '세션 종료 시각이다.';

comment on column assessment_session.policy_time_limit_at is '정책 시간 제한 일시 값을 저장한다.';

comment on column assessment_session.window_leave_count is '창 이탈 횟수 값을 저장한다.';

comment on column assessment_session.total_away_seconds is '창 이탈 총 시간(초) 값을 저장한다.';

comment on column assessment_session.last_window_left_at is '마지막 창 이탈 일시 값을 저장한다.';

comment on column assessment_session.last_window_returned_at is '마지막 창 복귀 일시 값을 저장한다.';

comment on column assessment_session.connection_loss_count is '연결 끊김 횟수 값을 저장한다.';

comment on column assessment_session.total_disconnected_seconds is '연결 끊김 총 시간(초) 값을 저장한다.';

comment on column assessment_session.ended_axis_code is '세션이 종료된 시점의 이해 단계 축이다.';

comment on column assessment_session.row_version is '동시 답변 제출과 중복 요청을 막기 위한 낙관적 잠금 버전이다.';

comment on column assessment_session.updated_at is '레코드가 마지막으로 변경된 시각이다.';

alter table assessment_session
    owner to postgres;

grant delete, insert, select, update on assessment_session to teamiz_app;

create table assessment_round_question_focus
(
    assessment_round_question_focus_id uuid default gen_random_uuid() not null
        constraint pk_assessment_round_question_focus
            primary key,
    assessment_round_id                uuid                           not null
        constraint fk_assessment_round_question_focus_assessment_round_id
            references project_assessment_round
            on delete restrict,
    question_focus_item_id             uuid                           not null
        constraint fk_assessment_round_question_focus_question_focus_item_id
            references question_focus_item
            on delete restrict,
    org_id                             uuid                           not null
        constraint fk_assessment_round_question_focus_org_id
            references organization
            on delete restrict,
    version_no                         integer                        not null
        constraint ck_assessment_round_question_focus_version_no
            check (version_no > 0),
    display_scope                      varchar(100)                   not null
        constraint ck_assessment_round_question_focus_display_scope
            check ((display_scope)::text = 'MANAGER_ONLY'::text),
    effective_from                     timestamp with time zone       not null,
    effective_to                       timestamp with time zone,
    constraint uq_assessment_round_question_focus_assessment_round_id_quest
        unique (assessment_round_id, question_focus_item_id, version_no),
    constraint ck_assessment_round_question_focus_effective_to
        check ((effective_to IS NULL) OR (effective_to > effective_from))
);

comment on table assessment_round_question_focus is '프로젝트 회차에 적용할 질문 초점 항목의 전체 선택 집합을 버전별 불변 이력으로 저장합니다. 한 변경 요청은 선택 집합 전체를 동일한 version_no로 기록하고 분석 실행은 해당 집합 버전을 고정합니다. | 정의서명: AssessmentRoundQuestionFocus | 제약·비고: • version_no > 0, UNIQUE(assessment_round_id, question_focus_item_id, version_no) • 같은 회차·버전의 모든 행은 동일한 effective_from·effective_to를 공유합니다. • 회차별 현재 유효 version_no는 하나만 존재해야 하며 집합 단위 조건은 지연 제약 트리거로 보장합니다. • 신규 버전은 직전 선택 집합 전체를 복제한 뒤 추가·삭제를 반영하여 원자 교체합니다. • 신규 선택 항목은 QuestionFocusItem.active=TRUE여야 합니다. • 교육생 API·세션 화면에는 질문 초점과 display_scope를 노출하지 않습니다.';

comment on column assessment_round_question_focus.assessment_round_question_focus_id is '회차 질문 초점 레코드를 식별하는 기본키이다.';

comment on column assessment_round_question_focus.assessment_round_id is '회차 질문 초점가 참조하는 평가 회차 ID 외래키 후보이다.';

comment on column assessment_round_question_focus.question_focus_item_id is '회차 질문 초점가 참조하는 질문 초점 항목 ID 외래키 후보이다.';

comment on column assessment_round_question_focus.org_id is '회차 질문 초점가 참조하는 기관 ID 외래키 후보이다.';

comment on column assessment_round_question_focus.version_no is '회차 전체 질문 초점 선택 집합의 버전 번호이다.';

comment on column assessment_round_question_focus.display_scope is '회차 질문 초점의 표시 범위 값을 기록한다.';

comment on column assessment_round_question_focus.effective_from is '질문 초점 집합 버전의 적용 시작 일시이다.';

comment on column assessment_round_question_focus.effective_to is '질문 초점 집합 버전의 적용 종료 일시이다.';

alter table assessment_round_question_focus
    owner to postgres;

grant delete, insert, select, update on assessment_round_question_focus to teamiz_app;

create table problem_stage
(
    problem_stage_id                     uuid                     default gen_random_uuid()             not null
        constraint pk_problem_stage
            primary key,
    session_id                           uuid                                                           not null
        constraint fk_problem_stage_session_id
            references assessment_session
            on delete restrict,
    problem_id                           uuid                                                           not null
        constraint fk_problem_stage_problem_id
            references assessment_problem
            on delete restrict,
    source_problem_stage_id              uuid
        constraint fk_problem_stage_source_problem_stage_id
            references problem_stage
            on delete restrict,
    axis_code                            varchar(10)                                                    not null
        constraint ck_problem_stage_axis_code
            check ((axis_code)::text = ANY
                   (ARRAY [('L1'::character varying)::text, ('L2'::character varying)::text, ('L3'::character varying)::text, ('L4'::character varying)::text])),
    question_sequence_no                 integer                                                        not null,
    question_text                        text                                                           not null,
    question_answer_text                 text,
    question_score                       smallint
        constraint ck_problem_stage_question_score
            check ((question_score IS NULL) OR ((question_score >= 0) AND (question_score <= 5))),
    question_passed                      boolean,
    first_hint_text                      text                                                           not null,
    first_hint_answer_text               text,
    first_hint_score                     smallint
        constraint ck_problem_stage_first_hint_score
            check ((first_hint_score IS NULL) OR ((first_hint_score >= 0) AND (first_hint_score <= 5))),
    first_hint_passed                    boolean,
    second_hint_text                     text                                                           not null,
    second_hint_answer_text              text,
    second_hint_score                    smallint
        constraint ck_problem_stage_second_hint_score
            check ((second_hint_score IS NULL) OR ((second_hint_score >= 0) AND (second_hint_score <= 5))),
    second_hint_passed                   boolean,
    status                               varchar(30)              default 'PREPARED'::character varying not null
        constraint ck_problem_stage_status
            check ((status)::text = ANY
                   (ARRAY [('PREPARED'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('PASSED'::character varying)::text, ('NOT_PASSED'::character varying)::text, ('NOT_REACHED'::character varying)::text, ('NOT_ANSWERED'::character varying)::text])),
    question_presented_at                timestamp with time zone,
    question_answered_at                 timestamp with time zone,
    first_hint_presented_at              timestamp with time zone,
    first_hint_answered_at               timestamp with time zone,
    second_hint_presented_at             timestamp with time zone,
    second_hint_answered_at              timestamp with time zone,
    created_at                           timestamp with time zone default CURRENT_TIMESTAMP             not null,
    updated_at                           timestamp with time zone default CURRENT_TIMESTAMP             not null,
    row_version                          bigint                   default 0                             not null
        constraint ck_problem_stage_row_version
            check (row_version >= 0),
    is_flagged                           boolean                  default false                         not null,
    question_away_count                  integer                  default 0                             not null
        constraint ck_problem_stage_question_away_count
            check (question_away_count >= 0),
    question_away_seconds                integer                  default 0                             not null
        constraint ck_problem_stage_question_away_seconds
            check (question_away_seconds >= 0),
    question_first_keystroke_delay_ms    integer
        constraint ck_problem_stage_question_first_keystroke_delay_ms
            check ((question_first_keystroke_delay_ms IS NULL) OR (question_first_keystroke_delay_ms >= 0)),
    first_hint_away_count                integer                  default 0                             not null
        constraint ck_problem_stage_first_hint_away_count
            check (first_hint_away_count >= 0),
    first_hint_away_seconds              integer                  default 0                             not null
        constraint ck_problem_stage_first_hint_away_seconds
            check (first_hint_away_seconds >= 0),
    first_hint_first_keystroke_delay_ms  integer
        constraint ck_problem_stage_first_hint_first_keystroke_delay_ms
            check ((first_hint_first_keystroke_delay_ms IS NULL) OR (first_hint_first_keystroke_delay_ms >= 0)),
    second_hint_away_count               integer                  default 0                             not null
        constraint ck_problem_stage_second_hint_away_count
            check (second_hint_away_count >= 0),
    second_hint_away_seconds             integer                  default 0                             not null
        constraint ck_problem_stage_second_hint_away_seconds
            check (second_hint_away_seconds >= 0),
    second_hint_first_keystroke_delay_ms integer
        constraint ck_problem_stage_second_hint_first_keystroke_delay_ms
            check ((second_hint_first_keystroke_delay_ms IS NULL) OR (second_hint_first_keystroke_delay_ms >= 0)),
    problem_closed_at                    timestamp with time zone,
    problem_close_reason_code            varchar(30),
    question_request_id                  uuid,
    first_hint_request_id                uuid,
    second_hint_request_id               uuid,
    constraint uq_problem_stage_session_id_problem_id_axis_code
        unique (session_id, problem_id, axis_code),
    constraint uq_problem_stage_session_id_problem_id_question_sequence_no
        unique (session_id, problem_id, question_sequence_no),
    constraint ck_problem_stage_close_reason
        check (((problem_closed_at IS NULL) AND (problem_close_reason_code IS NULL)) OR
               ((problem_closed_at IS NOT NULL) AND (problem_close_reason_code IS NOT NULL) AND
                ((problem_close_reason_code)::text = ANY
                 (ARRAY [('HINTS_EXHAUSTED'::character varying)::text, ('CURSOR_MOVED'::character varying)::text, ('PROBLEM_TIME_LIMIT'::character varying)::text, ('SESSION_ENDED'::character varying)::text])))),
    constraint ck_problem_stage_first_hint_answer_text
        check (((first_hint_answer_text IS NULL) AND (first_hint_score IS NULL) AND (first_hint_passed IS NULL) AND
                (first_hint_answered_at IS NULL)) OR
               ((first_hint_answer_text IS NOT NULL) AND (first_hint_score IS NOT NULL) AND
                (first_hint_passed IS NOT NULL) AND (first_hint_answered_at IS NOT NULL))),
    constraint ck_problem_stage_first_hint_passed
        check ((first_hint_passed IS NULL) OR (first_hint_score IS NOT NULL)),
    constraint ck_problem_stage_first_hint_score_2
        check ((first_hint_passed IS NULL) OR ((first_hint_passed = true) AND (first_hint_score >= 3)) OR
               ((first_hint_passed = false) AND (first_hint_score < 3))),
    constraint ck_problem_stage_question_answer_text
        check (((question_answer_text IS NULL) AND (question_score IS NULL) AND (question_passed IS NULL) AND
                (question_answered_at IS NULL)) OR
               ((question_answer_text IS NOT NULL) AND (question_score IS NOT NULL) AND
                (question_passed IS NOT NULL) AND (question_answered_at IS NOT NULL))),
    constraint ck_problem_stage_question_passed
        check ((question_passed IS NULL) OR (question_score IS NOT NULL)),
    constraint ck_problem_stage_question_score_2
        check ((question_passed IS NULL) OR ((question_passed = true) AND (question_score >= 3)) OR
               ((question_passed = false) AND (question_score < 3))),
    constraint ck_problem_stage_second_hint_answer_text
        check (((second_hint_answer_text IS NULL) AND (second_hint_score IS NULL) AND (second_hint_passed IS NULL) AND
                (second_hint_answered_at IS NULL)) OR
               ((second_hint_answer_text IS NOT NULL) AND (second_hint_score IS NOT NULL) AND
                (second_hint_passed IS NOT NULL) AND (second_hint_answered_at IS NOT NULL))),
    constraint ck_problem_stage_second_hint_passed
        check ((second_hint_passed IS NULL) OR (second_hint_score IS NOT NULL)),
    constraint ck_problem_stage_second_hint_presented_at
        check ((second_hint_presented_at IS NULL) OR (first_hint_presented_at IS NOT NULL)),
    constraint ck_problem_stage_second_hint_score_2
        check ((second_hint_passed IS NULL) OR ((second_hint_passed = true) AND (second_hint_score >= 3)) OR
               ((second_hint_passed = false) AND (second_hint_score < 3))),
    constraint ck_problem_stage_source_problem_stage_id
        check ((source_problem_stage_id IS NULL) OR (source_problem_stage_id <> problem_stage_id)),
    constraint ck_problem_stage_status_2
        check ((((status)::text = 'PASSED'::text) AND
                ((question_passed IS TRUE) OR (first_hint_passed IS TRUE) OR (second_hint_passed IS TRUE))) OR
               (((status)::text = 'NOT_PASSED'::text) AND (question_answer_text IS NOT NULL) AND
                (question_passed IS NOT TRUE) AND (first_hint_passed IS NOT TRUE) AND
                (second_hint_passed IS NOT TRUE)) OR (((status)::text = ANY
                                                       (ARRAY [('NOT_REACHED'::character varying)::text, ('NOT_ANSWERED'::character varying)::text])) AND
                                                      (question_answer_text IS NULL) AND (question_score IS NULL) AND
                                                      (question_passed IS NULL) AND (first_hint_answer_text IS NULL) AND
                                                      (first_hint_score IS NULL) AND (first_hint_passed IS NULL) AND
                                                      (second_hint_answer_text IS NULL) AND
                                                      (second_hint_score IS NULL) AND (second_hint_passed IS NULL)) OR
               ((status)::text = ANY
                (ARRAY [('PREPARED'::character varying)::text, ('IN_PROGRESS'::character varying)::text])))
);

comment on table problem_stage is '교육생별 평가 세션에서 실제 생성된 문제의 L1~L4 단계에 사용할 질문과 힌트 2개를 사전 저장하고, 질문·각 힌트에 대한 답변·0~5점·통과 여부를 같은 행에 누적 기록합니다. 별도 답변 시도 행은 생성하지 않습니다. | 정의서명: ProblemStage | 제약·비고: • UNIQUE(session_id, problem_id, axis_code) • UNIQUE(session_id, problem_id, question_sequence_no) • axis_code: L1 / L2 / L3 / L4, question_sequence_no: 1 / 2 / 3 / 4 고정 생성 • INITIAL·RETRY는 generation_status=''GENERATED''인 문제마다 세션별 L1~L4 4행을 사전 생성합니다. 총 행 수는 generated_problem_count × 4이며 REVIEW는 지정된 원본 단계만 생성합니다. • generation_status=''NOT_GENERATED''인 문제에는 ProblemStage·단계별 질문·단계별 힌트를 생성하지 않습니다. • 각 단계는 assessment_problem.source_snippet_key를 통해 Submission.code_snippets의 동일한 대표 코드 원문을 사용하며 단계별 코드 근거를 별도로 복제하지 않습니다. • 세션 READY 전 계획 문제 슬롯 3건의 generation_status가 모두 확정되어야 하고, GENERATED 문제의 question_text·first_hint_text·second_hint_text가 모두 존재해야 합니다. • question_score·first_hint_score·second_hint_score는 NULL 또는 0~5이며 답변·통과 여부·답변 일시와 함께 확정합니다. • 기본 ';

comment on column problem_stage.problem_stage_id is '세션별 문제 단계 행을 식별하는 고유 키이다.';

comment on column problem_stage.session_id is '문제 단계가 속한 교육생 평가 세션을 참조한다.';

comment on column problem_stage.problem_id is '단계가 속한 평가 문제를 참조한다.';

comment on column problem_stage.source_problem_stage_id is 'REVIEW 단계가 참조하는 최초 수행의 원본 문제 단계를 저장한다.';

comment on column problem_stage.axis_code is '문제의 평가 단계 L1~L4를 나타낸다.';

comment on column problem_stage.question_sequence_no is '평가 축에 대응하는 질문 순번 1~4를 생성한다.';

comment on column problem_stage.question_text is '해당 단계에서 최초로 제시할 질문 내용을 저장한다.';

comment on column problem_stage.question_answer_text is '교육생이 최초 질문에 제출한 답변 원문을 저장한다.';

comment on column problem_stage.question_score is '최초 질문 답변의 AI 측정 점수를 저장한다.';

comment on column problem_stage.question_passed is '최초 질문 답변의 통과 여부를 저장한다.';

comment on column problem_stage.first_hint_text is '최초 질문 미통과 시 제시할 첫 번째 힌트 내용을 저장한다.';

comment on column problem_stage.first_hint_answer_text is '첫 번째 힌트를 받은 뒤 제출한 답변 원문을 저장한다.';

comment on column problem_stage.first_hint_score is '첫 번째 힌트 답변의 AI 측정 점수를 저장한다.';

comment on column problem_stage.first_hint_passed is '첫 번째 힌트 답변의 통과 여부를 저장한다.';

comment on column problem_stage.second_hint_text is '첫 번째 힌트 답변 미통과 시 제시할 두 번째 힌트 내용을 저장한다.';

comment on column problem_stage.second_hint_answer_text is '두 번째 힌트를 받은 뒤 제출한 최종 답변 원문을 저장한다.';

comment on column problem_stage.second_hint_score is '두 번째 힌트 답변의 AI 측정 점수를 저장한다.';

comment on column problem_stage.second_hint_passed is '두 번째 힌트 답변의 통과 여부를 저장한다.';

comment on column problem_stage.status is '문제 단계 전체의 현재 진행·최종 상태를 저장한다.';

comment on column problem_stage.question_presented_at is '최초 질문을 교육생에게 표시한 시각을 저장한다.';

comment on column problem_stage.question_answered_at is '최초 질문 답변 제출이 확정된 시각을 저장한다.';

comment on column problem_stage.first_hint_presented_at is '첫 번째 힌트를 표시한 시각을 저장한다.';

comment on column problem_stage.first_hint_answered_at is '첫 번째 힌트 답변 제출이 확정된 시각을 저장한다.';

comment on column problem_stage.second_hint_presented_at is '두 번째 힌트를 표시한 시각을 저장한다.';

comment on column problem_stage.second_hint_answered_at is '두 번째 힌트 답변 제출이 확정된 시각을 저장한다.';

comment on column problem_stage.created_at is '문제 단계 행이 생성된 시각을 저장한다.';

comment on column problem_stage.updated_at is '응답·판정·상태가 마지막으로 변경된 시각을 저장한다.';

comment on column problem_stage.row_version is '중복 제출과 동시 갱신 검증을 위한 낙관적 잠금 버전이다.';

comment on column problem_stage.is_flagged is '보기형이 섞여 재생성에도 실패한 질문임을 표시한다.';

comment on column problem_stage.question_away_count is '질문 답변을 쓰는 동안 다른 창·탭으로 나간 횟수이다. 차단하지 않고 기록만 한다.';

comment on column problem_stage.question_away_seconds is '질문 답변을 쓰는 동안 창을 벗어나 있던 누적 시간(초)이다.';

comment on column problem_stage.question_first_keystroke_delay_ms is '질문이 화면에 뜬 뒤 첫 글자를 입력하기까지 걸린 시간(ms)이다. 입력이 없었으면 NULL이다.';

comment on column problem_stage.first_hint_away_count is '첫 번째 힌트 답변을 쓰는 동안 다른 창·탭으로 나간 횟수이다.';

comment on column problem_stage.first_hint_away_seconds is '첫 번째 힌트 답변을 쓰는 동안 창을 벗어나 있던 누적 시간(초)이다.';

comment on column problem_stage.first_hint_first_keystroke_delay_ms is '첫 번째 힌트가 화면에 뜬 뒤 첫 글자를 입력하기까지 걸린 시간(ms)이다. 입력이 없었으면 NULL이다.';

comment on column problem_stage.second_hint_away_count is '두 번째 힌트 답변을 쓰는 동안 다른 창·탭으로 나간 횟수이다.';

comment on column problem_stage.second_hint_away_seconds is '두 번째 힌트 답변을 쓰는 동안 창을 벗어나 있던 누적 시간(초)이다.';

comment on column problem_stage.second_hint_first_keystroke_delay_ms is '두 번째 힌트가 화면에 뜬 뒤 첫 글자를 입력하기까지 걸린 시간(ms)이다. 입력이 없었으면 NULL이다.';

comment on column problem_stage.problem_closed_at is '이 문제가 종료로 확정된 시각을 저장한다. 같은 문제의 L1~L4 행에 같은 값이 찍히며 NULL 이면 아직 끝나지 않았거나 전환 이전에 종료된 문제이다. 리포트 생성 대상 선별이 이 컬럼만 본다.';

comment on column problem_stage.problem_close_reason_code is '문제가 종료된 사유를 저장한다. HINTS_EXHAUSTED(힌트 2개 소진) / CURSOR_MOVED(AI 커서가 다른 문제로 이동) / PROBLEM_TIME_LIMIT(문제별 제한 시간 초과) / SESSION_ENDED(세션 종료 또는 안전망 백필) 중 하나이며 problem_closed_at 과 짝으로만 존재한다.';

comment on column problem_stage.question_request_id is '질문 슬롯 답변을 채점할 때 AI에 보낸 멱등키(clientRequestId)다. 서버가 세션·단계·축·힌트사용수로 만드는 결정론적 UUID이며, 백엔드 로그·AI 로그와 이 행을 잇는 대조 값이다. 이 컬럼이 생기기 전에 답한 슬롯은 NULL이다.';

comment on column problem_stage.first_hint_request_id is '힌트 1개를 보고 답한 슬롯의 채점 멱등키다. 규칙은 question_request_id와 같다.';

comment on column problem_stage.second_hint_request_id is '힌트 2개를 보고 답한 슬롯의 채점 멱등키다. 규칙은 question_request_id와 같다.';

alter table problem_stage
    owner to postgres;

alter table assessment_session
    add constraint fk_assessment_session_current_problem_stage_id
        foreign key (current_problem_stage_id) references problem_stage
            on delete restrict;

create table interview_candidate_reason
(
    candidate_reason_id        uuid                     default gen_random_uuid() not null
        constraint pk_interview_candidate_reason
            primary key,
    candidate_id               uuid                                               not null
        constraint fk_interview_candidate_reason_candidate_id
            references interview_candidate
            on delete restrict,
    reason_code                varchar(100)                                       not null
        constraint ck_interview_candidate_reason_reason_code
            check ((reason_code)::text = ANY
                   (ARRAY [('STAGE_DECLINE'::character varying)::text, ('PERSISTENT_LOW'::character varying)::text, ('INVALID_ATTEMPT'::character varying)::text, ('CONTRIBUTION_UNDERSTANDING_GAP'::character varying)::text, ('LOW_PARTICIPATION'::character varying)::text])),
    evaluation_status          varchar(100)                                       not null
        constraint ck_interview_candidate_reason_evaluation_status
            check ((evaluation_status)::text = ANY
                   (ARRAY [('MATCHED'::character varying)::text, ('NOT_MATCHED'::character varying)::text, ('NOT_APPLICABLE'::character varying)::text, ('UNAVAILABLE'::character varying)::text])),
    not_applicable_reason_code varchar(100)
        constraint ck_interview_candidate_reason_not_applicable_reason_code
            check ((not_applicable_reason_code)::text = ANY
                   (ARRAY [('FIRST_MINI_PROJECT'::character varying)::text, ('INSUFFICIENT_LONGITUDINAL_HISTORY'::character varying)::text])),
    reason_status              varchar(100)                                       not null
        constraint ck_interview_candidate_reason_reason_status
            check ((reason_status)::text = ANY
                   (ARRAY [('ACTIVE'::character varying)::text, ('RESOLVED'::character varying)::text, ('SUPERSEDED'::character varying)::text])),
    effective_from             timestamp with time zone                           not null,
    effective_to               timestamp with time zone,
    resolution_code            varchar(100),
    source_assessment_round_id uuid                                               not null
        constraint fk_interview_candidate_reason_source_assessment_round_id
            references project_assessment_round
            on delete restrict,
    source_attempt_id          uuid
        constraint fk_interview_candidate_reason_source_attempt_id
            references measurement_attempt
            on delete restrict,
    source_session_id          uuid
        constraint fk_interview_candidate_reason_source_session_id
            references assessment_session
            on delete restrict,
    source_problem_id          uuid
        constraint fk_interview_candidate_reason_source_problem_id
            references assessment_problem
            on delete restrict,
    source_problem_stage_id    uuid
        constraint fk_interview_candidate_reason_source_problem_stage_id
            references problem_stage
            on delete restrict,
    reason_summary             text,
    policy_version             integer                                            not null,
    detected_at                timestamp with time zone                           not null,
    created_at                 timestamp with time zone default CURRENT_TIMESTAMP not null,
    updated_at                 timestamp with time zone default CURRENT_TIMESTAMP not null,
    row_version                integer                  default 0                 not null,
    constraint ck_interview_candidate_reason_evaluation_status_2
        check ((((evaluation_status)::text = 'NOT_APPLICABLE'::text) AND (not_applicable_reason_code IS NOT NULL)) OR
               (((evaluation_status)::text <> 'NOT_APPLICABLE'::text) AND (not_applicable_reason_code IS NULL))),
    constraint ck_interview_candidate_reason_reason_status_2
        check ((((reason_status)::text = 'ACTIVE'::text) AND (effective_to IS NULL) AND (resolution_code IS NULL)) OR
               (((reason_status)::text = 'RESOLVED'::text) AND (effective_to IS NOT NULL) AND
                (resolution_code IS NOT NULL) AND (effective_to > effective_from)) OR
               ((reason_status)::text = 'SUPERSEDED'::text)),
    constraint ck_interview_candidate_reason_reason_status_3
        check ((effective_to IS NULL) OR (effective_to > effective_from))
);

comment on table interview_candidate_reason is '하나의 면담 후보에 동시에 일치할 수 있는 복수 위험 유형과 평가 상태·수명주기·MEAS 원천 추적을 보존합니다. | 정의서명: InterviewCandidateReason | 제약·비고: • reason_code: STAGE_DECLINE / PERSISTENT_LOW / INVALID_ATTEMPT / CONTRIBUTION_UNDERSTANDING_GAP / LOW_PARTICIPATION • evaluation_status: MATCHED / NOT_MATCHED / NOT_APPLICABLE / UNAVAILABLE • reason_status: ACTIVE / RESOLVED / SUPERSEDED • 동일 후보·사유·원천의 ACTIVE 행 중복을 금지합니다. • ACTIVE에서는 effective_to·resolution_code가 NULL이고 RESOLVED에서는 둘 다 필수입니다. • 첫 미니프로젝트 단계 하락은 NOT_APPLICABLE/FIRST_MINI_PROJECT, 지속 저점은 이력 부족 시 NOT_APPLICABLE/INSUFFICIENT_LONGITUDINAL_HISTORY입니다. • CONFIRMED_INVALID에서 INVALID_ATTEMPT ACTIVE 사유를 멱등 생성하고 RESTORED_VALID에서는 삭제하지 않고 RESOLVED로 전환합니다. • 평가 실패·준비 중에는 placeholder 후보 사유를 만들지 않고 View 원천 상태로 표현합니다. • 유형 판정의 원장은 MeasurementAttempt.outcome_type_code·outcome_verdict이며 이 테이블은 그 판정에서 파생한 면담 후보 사유입니다. 판정식을 여기에 다시 구현하지 않고 outcome_verdict.types의 MATCHED·NOT_APPLICABLE 항목만 옮겨 적습니다. • 같은 이유로 policy_version은 MeasurementAttempt.outcome_policy_version을 그대로 복사합니다. • 등재는 INITIAL 수행이 판정된 뒤에만 수행하며 동일 후보·사유·source_attempt_id의 ACTIVE 행을 중복 생성하지 않습니다. • resolution_code: VALIDITY_RESTORED(무효 확인이 RESTORED_VALID로 뒤집힘) / RETRY_PASSED(재시험에서 게이트를 만든 문제가 전부 2단 이상 도달). 폐쇄형 목록이 확정되기 전이라 임의 DB CHECK로 고정하지 않습니다.';

comment on column interview_candidate_reason.candidate_reason_id is '후보 사유 ID 값을 저장한다.';

comment on column interview_candidate_reason.candidate_id is '후보 ID 값을 저장한다.';

comment on column interview_candidate_reason.reason_code is '사유 코드 값을 저장한다.';

comment on column interview_candidate_reason.evaluation_status is '평가 상태 값을 저장한다.';

comment on column interview_candidate_reason.not_applicable_reason_code is '비적용 사유 코드 값을 저장한다.';

comment on column interview_candidate_reason.reason_status is '사유 수명주기 값을 저장한다.';

comment on column interview_candidate_reason.effective_from is '적용 시작 일시 값을 저장한다.';

comment on column interview_candidate_reason.effective_to is '적용 종료 일시 값을 저장한다.';

comment on column interview_candidate_reason.resolution_code is '해소 코드 값을 저장한다.';

comment on column interview_candidate_reason.source_assessment_round_id is '원천 평가 회차 ID 값을 저장한다.';

comment on column interview_candidate_reason.source_attempt_id is '원천 수행 ID 값을 저장한다.';

comment on column interview_candidate_reason.source_session_id is '원천 세션 ID 값을 저장한다.';

comment on column interview_candidate_reason.source_problem_id is '원천 문제 ID 값을 저장한다.';

comment on column interview_candidate_reason.source_problem_stage_id is '후보 사유를 발생시킨 세션별 문제 단계와 질문·힌트 응답 결과를 참조한다.';

comment on column interview_candidate_reason.reason_summary is '사유 요약 값을 저장한다.';

comment on column interview_candidate_reason.policy_version is '정책 버전 값을 저장한다.';

comment on column interview_candidate_reason.detected_at is '탐지 일시 값을 저장한다.';

comment on column interview_candidate_reason.created_at is '생성 일시 값을 저장한다.';

comment on column interview_candidate_reason.updated_at is '수정 일시 값을 저장한다.';

comment on column interview_candidate_reason.row_version is '행 버전 값을 저장한다.';

alter table interview_candidate_reason
    owner to postgres;

create unique index uq_interview_candidate_reason_active_grain
    on interview_candidate_reason (candidate_id, reason_code, source_assessment_round_id, source_attempt_id,
                                   source_session_id, source_problem_id, source_problem_stage_id)
    where ((reason_status)::text = 'ACTIVE'::text);

grant delete, insert, select, update on interview_candidate_reason to teamiz_app;

create table interview_source
(
    interview_source_id                    uuid                     default gen_random_uuid() not null
        constraint pk_interview_source
            primary key,
    interview_id                           uuid                                               not null
        constraint fk_interview_source_interview_id
            references interview
            on delete restrict,
    source_type                            varchar(100)                                       not null
        constraint ck_interview_source_source_type_value
            check ((source_type)::text = ANY
                   (ARRAY [('CANDIDATE_REASON'::character varying)::text, ('MEASUREMENT_ATTEMPT'::character varying)::text, ('ASSESSMENT_SESSION'::character varying)::text, ('ASSESSMENT_PROBLEM'::character varying)::text, ('PROBLEM_STAGE'::character varying)::text, ('OBSERVATION_NOTE'::character varying)::text])),
    candidate_reason_id                    uuid
        constraint fk_interview_source_candidate_reason_id
            references interview_candidate_reason
            on delete restrict,
    attempt_id                             uuid
        constraint fk_interview_source_attempt_id
            references measurement_attempt
            on delete restrict,
    session_id                             uuid
        constraint fk_interview_source_session_id
            references assessment_session
            on delete restrict,
    problem_id                             uuid
        constraint fk_interview_source_problem_id
            references assessment_problem
            on delete restrict,
    problem_stage_id                       uuid
        constraint fk_interview_source_problem_stage_id
            references problem_stage
            on delete restrict,
    observation_note_id                    uuid
        constraint fk_interview_source_observation_note_id
            references observation_note
            on delete restrict,
    source_summary_snapshot                jsonb,
    source_status_snapshot                 jsonb,
    source_row_version_snapshot            jsonb,
    validity_review_status_snapshot        jsonb,
    validity_decision_reason_code_snapshot jsonb,
    source_captured_at                     timestamp with time zone                           not null,
    created_by                             uuid                                               not null
        constraint fk_interview_source_created_by
            references app_user
            on delete restrict,
    created_at                             timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint ck_interview_source_source_type
        check (num_nonnulls(candidate_reason_id, attempt_id, session_id, problem_id, problem_stage_id,
                            observation_note_id) = 1),
    constraint ck_interview_source_source_type_2
        check ((((source_type)::text = 'CANDIDATE_REASON'::text) AND (candidate_reason_id IS NOT NULL)) OR
               (((source_type)::text = 'MEASUREMENT_ATTEMPT'::text) AND (attempt_id IS NOT NULL)) OR
               (((source_type)::text = 'ASSESSMENT_SESSION'::text) AND (session_id IS NOT NULL)) OR
               (((source_type)::text = 'ASSESSMENT_PROBLEM'::text) AND (problem_id IS NOT NULL)) OR
               (((source_type)::text = 'PROBLEM_STAGE'::text) AND (problem_stage_id IS NOT NULL)) OR
               (((source_type)::text = 'OBSERVATION_NOTE'::text) AND (observation_note_id IS NOT NULL))),
    constraint ck_interview_source_type_slot_match
        check ((((source_type)::text = 'CANDIDATE_REASON'::text) AND (candidate_reason_id IS NOT NULL)) OR
               (((source_type)::text = 'MEASUREMENT_ATTEMPT'::text) AND (attempt_id IS NOT NULL)) OR
               (((source_type)::text = 'ASSESSMENT_SESSION'::text) AND (session_id IS NOT NULL)) OR
               (((source_type)::text = 'ASSESSMENT_PROBLEM'::text) AND (problem_id IS NOT NULL)) OR
               (((source_type)::text = 'PROBLEM_STAGE'::text) AND (problem_stage_id IS NOT NULL)) OR
               (((source_type)::text = 'OBSERVATION_NOTE'::text) AND (observation_note_id IS NOT NULL)))
);

comment on table interview_source is '면담 확정 당시 후보 사유·MEAS 결과·수동 관찰 근거와 원천 상태 스냅샷을 보존하여 이후 원천 해소와 당시 판단을 함께 재현합니다. | 정의서명: InterviewSource | 제약·비고: • Interview 1:N • source_type별 허용 형식화 FK 중 정확히 1건만 채우도록 CHECK 또는 지연 제약으로 강제합니다. • 원천과 Interview의 기관·기수·교육생·평가 회차 경로가 일치해야 합니다. • 후보 사유·무효 확인·답변 결과가 이후 변경되더라도 포착 당시 요약·상태·행 버전을 수정하지 않습니다. • InterviewBriefItem은 같은 interview_id의 InterviewSource만 참조할 수 있습니다. • ProblemStage의 질문·힌트·각 답변 원문을 자동 복제하지 않고 접근 가능한 요약·상태·행 버전 스냅샷만 보존합니다. • source_type은 폐쇄형 6종이며 참조 테이블 이름을 값으로 씁니다(2026-08-15 확정, ck_interview_source_source_type_value). • source_type과 채워진 FK가 서로 가리키는 대상이 같아야 합니다(ck_interview_source_source_type_2). • 근거 없는 브리프 질문은 InterviewSource 행을 만들지 않고 InterviewBriefItem에서 source_type GENERAL·RAPPORT + interview_source_id NULL로 표현합니다.';

comment on column interview_source.interview_source_id is '면담 원천 ID 값을 저장한다.';

comment on column interview_source.interview_id is '면담 ID 값을 저장한다.';

comment on column interview_source.source_type is '원천 유형 값을 저장한다. 폐쇄형 6종이며 값은 참조 테이블 이름을 따른다 — CANDIDATE_REASON(InterviewCandidateReason) / MEASUREMENT_ATTEMPT(MeasurementAttempt) / ASSESSMENT_SESSION(AssessmentSession) / ASSESSMENT_PROBLEM(AssessmentProblem) / PROBLEM_STAGE(ProblemStage) / OBSERVATION_NOTE(ObservationNote). 값에 대응하는 FK 컬럼이 채워져야 한다.';

comment on column interview_source.candidate_reason_id is '후보 사유 ID 값을 저장한다.';

comment on column interview_source.attempt_id is '수행 ID 값을 저장한다.';

comment on column interview_source.session_id is '세션 ID 값을 저장한다.';

comment on column interview_source.problem_id is '문제 ID 값을 저장한다.';

comment on column interview_source.problem_stage_id is '문제 단계 ID 값을 저장한다.';

comment on column interview_source.observation_note_id is '관찰 메모 ID 값을 저장한다.';

comment on column interview_source.source_summary_snapshot is '원천 요약 스냅샷 값을 저장한다.';

comment on column interview_source.source_status_snapshot is '원천 상태 스냅샷 값을 저장한다.';

comment on column interview_source.source_row_version_snapshot is '원천 행 버전 스냅샷 값을 저장한다.';

comment on column interview_source.validity_review_status_snapshot is '무효 확인 상태 스냅샷 값을 저장한다.';

comment on column interview_source.validity_decision_reason_code_snapshot is '무효 결정 사유 스냅샷 값을 저장한다.';

comment on column interview_source.source_captured_at is '원천 포착 일시 값을 저장한다.';

comment on column interview_source.created_by is '생성 처리자 ID 값을 저장한다.';

comment on column interview_source.created_at is '생성 일시 값을 저장한다.';

alter table interview_source
    owner to postgres;

create table interview_brief_item
(
    brief_item_id       uuid                     default gen_random_uuid() not null
        constraint pk_interview_brief_item
            primary key,
    brief_id            uuid                                               not null
        constraint fk_interview_brief_item_brief_id
            references interview_brief
            on delete restrict,
    source_type         varchar(100)                                       not null
        constraint ck_interview_brief_item_source_type
            check ((source_type)::text = ANY
                   (ARRAY [('CANDIDATE_REASON'::character varying)::text, ('MEASUREMENT_ATTEMPT'::character varying)::text, ('ASSESSMENT_SESSION'::character varying)::text, ('ASSESSMENT_PROBLEM'::character varying)::text, ('PROBLEM_STAGE'::character varying)::text, ('OBSERVATION_NOTE'::character varying)::text, ('GENERAL'::character varying)::text, ('RAPPORT'::character varying)::text])),
    interview_source_id uuid
        constraint fk_interview_brief_item_interview_source_id
            references interview_source
            on delete restrict,
    question_text       text                                               not null,
    question_rationale  text,
    suggested_order     integer,
    display_order       integer,
    is_selected         boolean                                            not null,
    selected_by         uuid
        constraint fk_interview_brief_item_selected_by
            references app_user
            on delete restrict,
    selected_at         timestamp with time zone,
    updated_at          timestamp with time zone default CURRENT_TIMESTAMP not null,
    row_version         integer                  default 0                 not null,
    constraint uq_interview_brief_item_brief_id_display_order
        unique (brief_id, display_order),
    constraint ck_interview_brief_item_is_selected
        check (((is_selected = true) AND (selected_by IS NOT NULL) AND (selected_at IS NOT NULL) AND
                (display_order IS NOT NULL)) OR
               ((is_selected = false) AND (selected_by IS NULL) AND (selected_at IS NULL) AND (display_order IS NULL))),
    constraint ck_interview_brief_item_source_type_2
        check ((((source_type)::text = ANY
                 (ARRAY [('GENERAL'::character varying)::text, ('RAPPORT'::character varying)::text])) AND
                (interview_source_id IS NULL)) OR (((source_type)::text <> ALL
                                                    (ARRAY [('GENERAL'::character varying)::text, ('RAPPORT'::character varying)::text])) AND
                                                   (interview_source_id IS NOT NULL)))
);

comment on table interview_brief_item is '브리프의 저장된 확인 질문·선택 여부·순서를 관리합니다. 근거가 있는 질문은 InterviewSource를 참조하고 라포·일상 질문은 근거 없이 저장합니다. 화면의 저장 전 임시 선택은 보존하지 않습니다. | 정의서명: InterviewBriefItem | 제약·비고: • source_type은 원천 6종 + GENERAL·RAPPORT 8종입니다(2026-08-15 확정). • source_type이 GENERAL·RAPPORT이면 interview_source_id가 NULL이고 그 외에는 필수입니다. 라포·일상 질문은 AI가 설계상 근거 없이 생성하므로 아무 원천이나 대신 참조하게 하지 않습니다. • 매니저 수동 추가 항목은 지원하지 않습니다. 근거 없는 항목이 곧 수동 항목은 아닙니다. • 참조 InterviewSource와 InterviewBrief는 같은 interview_id여야 합니다. • question_text는 매니저가 그대로 읽는 구어체 질문 원문이고 question_rationale은 매니저 전용 근거입니다. • is_selected=TRUE이면 selected_by·selected_at·display_order가 필수이고 FALSE이면 모두 NULL입니다. • 선택 항목 범위에서 UNIQUE(brief_id, display_order)를 적용합니다. • DRAFT 선택 0건은 허용하지만 CONFIRMED 전환 시 선택 항목 1건 이상을 요구합니다. • 면담에서 난이도를 기록하지 않으므로 난이도 속성을 보유하지 않습니다. • 저장 성공 시에만 선택자·시각·이력을 기록합니다.';

comment on column interview_brief_item.brief_item_id is '면담 브리프 항목 레코드를 식별하는 기본키이다.';

comment on column interview_brief_item.brief_id is '면담 브리프 항목가 참조하는 브리프 ID 외래키 후보이다.';

comment on column interview_brief_item.source_type is '면담 브리프 항목의 원천 유형 값을 기록한다. 근거가 있는 질문은 참조하는 InterviewSource의 source_type을 그대로 복사하며 값 6종은 그 컬럼과 같다. 근거가 설계상 없는 질문은 GENERAL(일상 질문)·RAPPORT(라포 형성 질문) 2종을 쓰고 interview_source_id를 NULL로 둔다. MANUAL·INTERVIEW_SOURCE는 08-06 정의서의 폐기된 값이므로 사용하지 않는다.';

comment on column interview_brief_item.interview_source_id is '브리프 항목의 근거가 되는 면담 원천을 참조한다. source_type이 GENERAL·RAPPORT이면 NULL이고 그 외에는 필수이다. NULL은 "근거를 못 찾았다"가 아니라 "설계상 근거가 없는 질문"을 뜻하므로 임의의 원천을 대신 채워 넣지 않는다.';

comment on column interview_brief_item.question_text is '매니저가 면담에서 교육생에게 실제로 던지는 질문 원문을 저장한다. 구어체 한 문장이며 물음표로 끝난다.';

comment on column interview_brief_item.question_rationale is '해당 질문을 도출한 근거를 저장한다. 매니저만 열람하며 교육생에게 노출하지 않는다.';

comment on column interview_brief_item.suggested_order is '제안 순서 값을 저장한다.';

comment on column interview_brief_item.display_order is '면담 브리프 항목의 표시 순서 값을 기록한다.';

comment on column interview_brief_item.is_selected is '면담 브리프 항목의 선택 여부 값을 기록한다.';

comment on column interview_brief_item.selected_by is '면담 브리프 항목가 참조하는 선택 처리자 ID 외래키 후보이다.';

comment on column interview_brief_item.selected_at is '면담 브리프 항목의 선택 일시 값을 기록한다.';

comment on column interview_brief_item.updated_at is '수정 일시 값을 저장한다.';

comment on column interview_brief_item.row_version is '행 버전 값을 저장한다.';

alter table interview_brief_item
    owner to postgres;

create unique index uq_interview_brief_item_selected_order
    on interview_brief_item (brief_id, display_order)
    where (is_selected = true);

grant delete, insert, select, update on interview_brief_item to teamiz_app;

create table interview_brief_item_history
(
    item_history_id    uuid default gen_random_uuid() not null
        constraint pk_interview_brief_item_history
            primary key,
    brief_item_id      uuid                           not null
        constraint fk_interview_brief_item_history_brief_item_id
            references interview_brief_item
            on delete restrict,
    change_type        varchar(100)                   not null
        constraint ck_interview_brief_item_history_change_type
            check ((change_type)::text = ANY
                   (ARRAY [('CREATED'::character varying)::text, ('SELECTED'::character varying)::text, ('DESELECTED'::character varying)::text, ('REORDERED'::character varying)::text, ('COPIED_TO_NEW_VERSION'::character varying)::text])),
    command_code       varchar(100)                   not null
        constraint ck_interview_brief_item_history_command_code
            check ((command_code)::text = ANY
                   (ARRAY [('INITIALIZE_BRIEF'::character varying)::text, ('SAVE_DRAFT'::character varying)::text, ('CONFIRM_BRIEF'::character varying)::text])),
    command_request_id uuid                           not null,
    brief_row_version  integer                        not null,
    before_snapshot    jsonb                          not null,
    after_snapshot     jsonb                          not null,
    changed_by         uuid                           not null
        constraint fk_interview_brief_item_history_changed_by
            references app_user
            on delete restrict,
    changed_at         timestamp with time zone       not null
);

comment on table interview_brief_item_history is '성공한 브리프 항목 생성·선택·해제·순서·버전 복제 명령의 전후 스냅샷을 APPEND-ONLY로 보존합니다. | 정의서명: InterviewBriefItemHistory | 제약·비고: • InterviewBriefItem 1:N APPEND-ONLY • 동일 저장 명령에서 변경된 여러 항목은 동일 command_request_id로 묶습니다. • 성공적으로 커밋된 생성·선택·해제·순서·새 버전 복제만 기록합니다. • 난이도 변경 명령을 사용하지 않으므로 DIFFICULTY_CHANGED·INCREASE_ITEM_DIFFICULTY를 허용하지 않습니다. • before_snapshot·after_snapshot의 질문 키는 question_text·question_rationale을 사용하며 과거 행의 title·summary 키는 그대로 보존합니다. • 화면 임시 클릭과 롤백된 변경은 저장하지 않습니다.';

comment on column interview_brief_item_history.item_history_id is '면담 브리프 항목 이력 레코드를 식별하는 기본키이다.';

comment on column interview_brief_item_history.brief_item_id is '면담 브리프 항목 이력가 참조하는 브리프 항목 ID 외래키 후보이다.';

comment on column interview_brief_item_history.change_type is '변경 유형 값을 저장한다.';

comment on column interview_brief_item_history.command_code is '명령 코드 값을 저장한다.';

comment on column interview_brief_item_history.command_request_id is '명령 요청 ID 값을 저장한다.';

comment on column interview_brief_item_history.brief_row_version is '브리프 행 버전 값을 저장한다.';

comment on column interview_brief_item_history.before_snapshot is '면담 브리프 항목 이력의 변경 전 스냅샷 값을 기록한다.';

comment on column interview_brief_item_history.after_snapshot is '면담 브리프 항목 이력의 변경 후 스냅샷 값을 기록한다.';

comment on column interview_brief_item_history.changed_by is '면담 브리프 항목 이력가 참조하는 변경 처리자 ID 외래키 후보이다.';

comment on column interview_brief_item_history.changed_at is '면담 브리프 항목 이력의 변경 일시 값을 기록한다.';

alter table interview_brief_item_history
    owner to postgres;

grant delete, insert, select, update on interview_brief_item_history to teamiz_app;

grant delete, insert, select, update on interview_source to teamiz_app;

create index ix_problem_stage_closed_at
    on problem_stage (problem_closed_at, session_id, problem_id)
    where (problem_closed_at IS NOT NULL);

grant delete, insert, select, update on problem_stage to teamiz_app;

create table report_evidence
(
    evidence_id                       uuid default gen_random_uuid() not null
        constraint pk_report_evidence
            primary key,
    snapshot_id                       uuid                           not null
        constraint fk_report_evidence_snapshot_id
            references report_snapshot
            on delete restrict,
    metric_id                         uuid
        constraint fk_report_evidence_metric_id
            references report_metric
            on delete restrict,
    problem_id                        uuid
        constraint fk_report_evidence_problem_id
            references assessment_problem
            on delete restrict,
    problem_stage_id                  uuid
        constraint fk_report_evidence_problem_stage_id
            references problem_stage
            on delete restrict,
    source_assessment_round_id        uuid
        constraint fk_report_evidence_source_assessment_round_id
            references project_assessment_round
            on delete restrict,
    source_snapshot_id                uuid
        constraint fk_report_evidence_source_snapshot_id
            references report_snapshot
            on delete restrict,
    subject_user_id                   uuid
        constraint fk_report_evidence_subject_user_id
            references app_user
            on delete restrict,
    subject_class_id                  uuid
        constraint fk_report_evidence_subject_class_id
            references class
            on delete restrict,
    source_class_id                   uuid
        constraint fk_report_evidence_source_class_id
            references class
            on delete restrict,
    source_type                       varchar(100),
    source_ref_id                     text,
    evidence_category                 varchar(100)                   not null
        constraint ck_report_evidence_evidence_category
            check ((evidence_category)::text = ANY
                   (ARRAY [('CURRICULUM_LOCATION'::character varying)::text, ('RESULT_EXPLANATION'::character varying)::text, ('ANSWER_EXCERPT'::character varying)::text, ('METRIC_TRACE'::character varying)::text, ('REPORT_SOURCE_ROUND'::character varying)::text, ('REPORT_SOURCE_SNAPSHOT'::character varying)::text, ('PARTICIPANT_RESULT'::character varying)::text, ('PARTICIPANT_RESULT_OCCURRENCE'::character varying)::text])),
    participant_section_code          varchar(100)
        constraint ck_report_evidence_participant_section_code
            check ((participant_section_code)::text = ANY
                   (ARRAY [('GROWTH_TRANSITION'::character varying)::text, ('EXCELLENT_TRAINEE'::character varying)::text])),
    source_role_code                  varchar(100)
        constraint ck_report_evidence_source_role_code
            check ((source_role_code)::text = ANY
                   (ARRAY [('GROWTH_LAST_MINI'::character varying)::text, ('GROWTH_BIG_ROUND'::character varying)::text, ('OUTCOME_FINAL_MINI'::character varying)::text, ('EXCELLENT_MINI_OCCURRENCE'::character varying)::text])),
    result_category_code              varchar(100)
        constraint ck_report_evidence_result_category_code
            check ((result_category_code)::text = ANY
                   (ARRAY [('LOW_TO_HIGH'::character varying)::text, ('LOW_TO_LOW'::character varying)::text, ('HIGH_TO_HIGH'::character varying)::text, ('HIGH_TO_LOW'::character varying)::text, ('MINI_CLASS_TOP'::character varying)::text])),
    baseline_value                    numeric(18, 6),
    outcome_value                     numeric(18, 6),
    eligibility_status                varchar(100)
        constraint ck_report_evidence_eligibility_status
            check ((eligibility_status)::text = ANY
                   (ARRAY [('ELIGIBLE'::character varying)::text, ('EXCLUDED'::character varying)::text])),
    exclusion_reason_code             varchar(100)
        constraint ck_report_evidence_exclusion_reason_code
            check ((exclusion_reason_code)::text = ANY
                   (ARRAY [('NOT_ATTENDED_INCLUDED'::character varying)::text, ('INVALID_INCLUDED'::character varying)::text, ('INTERRUPTED_INCLUDED'::character varying)::text])),
    occurrence_count                  integer
        constraint ck_report_evidence_occurrence_count
            check ((occurrence_count IS NULL) OR (occurrence_count >= 0)),
    big_project_minimum_reached_level integer
        constraint ck_report_evidence_big_project_minimum_reached_level
            check ((big_project_minimum_reached_level IS NULL) OR
                   ((big_project_minimum_reached_level >= 0) AND (big_project_minimum_reached_level <= 4))),
    is_joint_top                      boolean,
    top_tie_count                     integer
        constraint ck_report_evidence_top_tie_count
            check ((top_tie_count IS NULL) OR (top_tie_count > 0)),
    subject_display_snapshot          jsonb,
    axis_code                         varchar(10),
    decision_code                     varchar(100),
    evidence_summary                  text,
    quote_excerpt                     text,
    display_order                     integer
        constraint ck_report_evidence_display_order
            check ((display_order IS NULL) OR (display_order > 0)),
    filter_snapshot                   jsonb                          not null,
    policy_version                    integer
        constraint ck_report_evidence_policy_version
            check ((policy_version IS NULL) OR (policy_version > 0)),
    trace_payload                     jsonb                          not null,
    constraint ck_report_evidence_evidence_category_2
        check ((((evidence_category)::text = 'METRIC_TRACE'::text) AND (metric_id IS NOT NULL)) OR
               (((evidence_category)::text = 'REPORT_SOURCE_ROUND'::text) AND
                (source_assessment_round_id IS NOT NULL)) OR
               (((evidence_category)::text = 'REPORT_SOURCE_SNAPSHOT'::text) AND (source_snapshot_id IS NOT NULL)) OR
               (((evidence_category)::text = 'PARTICIPANT_RESULT'::text) AND (subject_user_id IS NOT NULL)) OR
               (((evidence_category)::text = 'PARTICIPANT_RESULT_OCCURRENCE'::text) AND
                (subject_user_id IS NOT NULL) AND (source_assessment_round_id IS NOT NULL) AND
                (source_class_id IS NOT NULL)) OR (((evidence_category)::text = 'ANSWER_EXCERPT'::text) AND
                                                   (num_nonnulls(problem_id, problem_stage_id) > 0)) OR
               ((evidence_category)::text = ANY
                (ARRAY [('CURRICULUM_LOCATION'::character varying)::text, ('RESULT_EXPLANATION'::character varying)::text]))),
    constraint ck_report_evidence_source_ref_id
        check (((source_type IS NULL) AND (source_ref_id IS NULL)) OR
               ((source_type IS NOT NULL) AND (source_ref_id IS NOT NULL))),
    constraint ck_report_evidence_source_ref_id_2
        check (NOT ((source_type IS NOT NULL) AND (source_ref_id IS NOT NULL) AND
                    (num_nonnulls(problem_id, problem_stage_id, source_assessment_round_id, source_snapshot_id,
                                  subject_user_id, subject_class_id, source_class_id) > 0))),
    constraint ck_report_evidence_top_tie_count_2
        check ((is_joint_top IS DISTINCT FROM true) OR (top_tie_count >= 2))
);

comment on table report_evidence is '리포트 수치·개인 축 판정·출처 회차·참여자 결과·우수 발생의 원천 FK와 공개용 발췌를 보존합니다. | 정의서명: ReportEvidence | 제약·비고: • evidence_category: CURRICULUM_LOCATION / RESULT_EXPLANATION / ANSWER_EXCERPT / METRIC_TRACE / REPORT_SOURCE_ROUND / REPORT_SOURCE_SNAPSHOT / PARTICIPANT_RESULT / PARTICIPANT_RESULT_OCCURRENCE • metric_id는 지표 trace에서는 필수지만 참여자 결과·출처 회차 근거에서는 NULL을 허용합니다. • 문제·세션별 문제 단계·회차·스냅샷·사용자·반 FK는 원천 종류에 따라 조건부로 사용합니다. • CURRICULUM_LOCATION은 별도 교안-개념 FK를 추가하지 않고 teachId·unitId·sourcePages·studyPointer를 trace_payload JSONB에 발행 당시 값으로 보존합니다. • 개념별 취약도 집계는 ReportMetric의 teaches_id와 metric_grain_code=''CONCEPT'' 또는 ''ROUND_CONCEPT'' 경로를 사용합니다. • 성장 요약은 마지막 미니 결과·빅프로젝트 평균 원값·기술 제외 사유를 고정합니다. • 우수 발생은 판정 당시 반·회차·공동 1위 여부·동점 인원 수를 고정합니다. • 형식화 FK와 source_type/source_ref_id로 같은 원천을 중복 표현하지 않습니다. • 원천 전문을 복제하지 않고 식별자·요약·길이 제한된 마스킹 발췌만 저장합니다. • 근거 열람은 최신 ManagerAssignment를 검증하고 허용·거부를 AuditLog에 기록합니다. • 감사 기록 실패 시 민감 근거 응답을 ';

comment on column report_evidence.evidence_id is '리포트 수치의 원천 근거 trace의 개별 레코드를 식별하는 고유 키이다.';

comment on column report_evidence.snapshot_id is '스냅샷 ID 값을 저장한다.';

comment on column report_evidence.metric_id is '지표와의 업무 관계를 연결하는 외래 키이다.';

comment on column report_evidence.problem_id is '문제 ID 값을 저장한다.';

comment on column report_evidence.problem_stage_id is '문제 단계 ID 값을 저장한다.';

comment on column report_evidence.source_assessment_round_id is '원천 평가 회차 ID 값을 저장한다.';

comment on column report_evidence.source_snapshot_id is '원천 스냅샷 ID 값을 저장한다.';

comment on column report_evidence.subject_user_id is '대상 사용자 ID 값을 저장한다.';

comment on column report_evidence.subject_class_id is '대상 반 ID 값을 저장한다.';

comment on column report_evidence.source_class_id is '원천 반 ID 값을 저장한다.';

comment on column report_evidence.source_type is '리포트 수치의 원천 근거 trace에서 관리하는 원천유형 정보이다.';

comment on column report_evidence.source_ref_id is '리포트 근거 업무에서 사용하는 원천 참조 ID 값이다.';

comment on column report_evidence.evidence_category is '근거 분류 값을 저장한다.';

comment on column report_evidence.participant_section_code is '참여자 섹션 코드 값을 저장한다.';

comment on column report_evidence.source_role_code is '원천 역할 코드 값을 저장한다.';

comment on column report_evidence.result_category_code is '결과 분류 코드 값을 저장한다.';

comment on column report_evidence.baseline_value is '기준값 값을 저장한다.';

comment on column report_evidence.outcome_value is '결과값 값을 저장한다.';

comment on column report_evidence.eligibility_status is '참여 적격 상태 값을 저장한다.';

comment on column report_evidence.exclusion_reason_code is '제외 사유 코드 값을 저장한다.';

comment on column report_evidence.occurrence_count is '발생 횟수 값을 저장한다.';

comment on column report_evidence.big_project_minimum_reached_level is '빅프로젝트 최소 도달 단계 값을 저장한다.';

comment on column report_evidence.is_joint_top is '공동 1위 여부 값을 저장한다.';

comment on column report_evidence.top_tie_count is '동점 인원 수 값을 저장한다.';

comment on column report_evidence.subject_display_snapshot is '대상 표시 스냅샷 값을 저장한다.';

comment on column report_evidence.axis_code is '평가 축 코드 값을 저장한다.';

comment on column report_evidence.decision_code is '판정 코드 값을 저장한다.';

comment on column report_evidence.evidence_summary is '근거 요약 값을 저장한다.';

comment on column report_evidence.quote_excerpt is '공개용 답변 발췌 값을 저장한다.';

comment on column report_evidence.display_order is '표시 순서 값을 저장한다.';

comment on column report_evidence.filter_snapshot is '리포트 근거 처리 시점의 필터 스냅샷을 구조화 데이터로 보존한다.';

comment on column report_evidence.policy_version is '정책 버전 값을 저장한다.';

comment on column report_evidence.trace_payload is '리포트 수치의 원천 근거 trace를 구조화하여 저장한다. CURRICULUM_LOCATION에서는 teachId·unitId·sourcePages·studyPointer를 포함한다.';

alter table report_evidence
    owner to postgres;

grant delete, insert, select, update on report_evidence to teamiz_app;

create table problem_stage_activity_log
(
    activity_log_id  uuid                     default gen_random_uuid() not null
        constraint pk_problem_stage_activity_log
            primary key,
    problem_stage_id uuid                                               not null
        constraint fk_problem_stage_activity_log_problem_stage_id
            references problem_stage
            on delete restrict,
    session_id       uuid                                               not null
        constraint fk_problem_stage_activity_log_session_id
            references assessment_session
            on delete restrict,
    event_type       varchar(30)                                        not null
        constraint ck_problem_stage_activity_log_event_type
            check ((event_type)::text = ANY
                   (ARRAY [('WINDOW_LEAVE'::character varying)::text, ('CONNECTION_LOSS'::character varying)::text, ('FIRST_KEYSTROKE_DELAY'::character varying)::text])),
    started_at       timestamp with time zone                           not null,
    duration_ms      integer                                            not null
        constraint ck_problem_stage_activity_log_duration_ms
            check (duration_ms >= 0),
    created_at       timestamp with time zone default CURRENT_TIMESTAMP not null
);

comment on table problem_stage_activity_log is '응시 중 관찰 신호(창 이탈·연결 끊김·첫 타이핑 지연)를 발생 건별로 보존합니다. assessment_session·problem_stage의 누적 합계·최근 1건 시각과 달리 매니저가 "언제 몇 초씩 몇 번" 벌어졌는지 재구성할 수 있게 합니다. | 정의서명: ProblemStageActivityLog | 제약·비고: • event_type: WINDOW_LEAVE / CONNECTION_LOSS / FIRST_KEYSTROKE_DELAY • duration_ms >= 0 • 같은 세션·문제 단계에 이벤트 유형별로 여러 행이 쌓일 수 있다(누적이 아니라 발생 건마다 INSERT). • 판정 로직(무효 응시)은 이 테이블이 아니라 assessment_session의 누적 컬럼을 그대로 읽으며, 이 테이블은 매니저 조회용 상세 이력이다.';

comment on column problem_stage_activity_log.activity_log_id is '부정행위 의심 이벤트 로그 한 건을 식별하는 고유 키이다.';

comment on column problem_stage_activity_log.problem_stage_id is '이벤트가 발생한 시점에 응시 중이던 문제 단계를 참조한다.';

comment on column problem_stage_activity_log.session_id is '이벤트가 속한 평가 세션을 참조한다. problem_stage_id로 조인하지 않고 세션 단위 목록 조회를 바로 하기 위해 중복 저장한다.';

comment on column problem_stage_activity_log.event_type is '이벤트 유형을 나타낸다. WINDOW_LEAVE(응시창 이탈) / CONNECTION_LOSS(연결 끊김) / FIRST_KEYSTROKE_DELAY(첫 타이핑 지연) 중 하나이다.';

comment on column problem_stage_activity_log.started_at is '이벤트가 시작된 시각을 저장한다. 클라이언트가 복귀·재연결 시점에 지속 시간만 보내므로 now() - duration으로 역산한 근사치이다.';

comment on column problem_stage_activity_log.duration_ms is '이벤트 지속 시간을 밀리초로 저장한다. 응시창 이탈·연결 끊김은 초 단위 입력을 1000배 하여 저장한다.';

comment on column problem_stage_activity_log.created_at is '이 로그 행이 적재된 시각을 저장한다.';

alter table problem_stage_activity_log
    owner to postgres;

create index ix_problem_stage_activity_log_problem_stage_id
    on problem_stage_activity_log (problem_stage_id);

create index ix_problem_stage_activity_log_session_id
    on problem_stage_activity_log (session_id, started_at);

grant delete, insert, select, update on problem_stage_activity_log to teamiz_app;

grant delete, insert, select, update on question_focus_item to teamiz_app;

create table refresh_token
(
    token_id          uuid                     default gen_random_uuid() not null
        constraint pk_refresh_token
            primary key,
    user_id           uuid                                               not null
        constraint fk_refresh_token_user_id
            references app_user
            on delete restrict,
    token_hash        varchar(128)                                       not null
        constraint uq_refresh_token_token_hash
            unique,
    token_family_id   uuid                                               not null,
    parent_token_id   uuid
        constraint fk_refresh_token_parent_token_id
            references refresh_token
            on delete restrict,
    issued_at         timestamp with time zone default CURRENT_TIMESTAMP not null,
    expires_at        timestamp with time zone                           not null,
    last_used_at      timestamp with time zone,
    revoked_at        timestamp with time zone,
    revoked_reason    text
        constraint ck_refresh_token_revoked_reason
            check (revoked_reason = ANY
                   (ARRAY ['LOGOUT'::text, 'ROTATED'::text, 'PASSWORD_CHANGED'::text, 'ACCOUNT_INACTIVATED'::text, 'ORGANIZATION_SUSPENDED'::text, 'ORGANIZATION_DELETION_PENDING'::text, 'TOKEN_REUSE_DETECTED'::text, 'ADMIN_REVOKED'::text])),
    revoked_by        uuid
        constraint fk_refresh_token_revoked_by
            references app_user
            on delete restrict,
    issued_ip         inet                                               not null,
    last_used_ip      inet                                               not null,
    issued_user_agent text                                               not null,
    last_user_agent   text                                               not null,
    reuse_detected_at timestamp with time zone,
    created_at        timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint ck_refresh_token_expires_at
        check (expires_at > issued_at),
    constraint ck_refresh_token_revoked_reason_2
        check (((revoked_at IS NULL) AND (revoked_reason IS NULL)) OR
               ((revoked_at IS NOT NULL) AND (revoked_reason IS NOT NULL)))
);

comment on table refresh_token is '로그인 상태를 연장할 때 사용하는 Refresh Token의 해시와 회전 계열, 폐기·재사용 탐지 이력, 발급·최근 사용 접속 정보를 보존하는 보안 원장입니다. 토큰 원문은 저장하지 않으며 한 토큰에서 하나의 후속 토큰만 생성되는 선형 회전을 강제합니다. | 정의서명: RefreshToken | 제약·비고: • token_hash는 전체 UNIQUE이며 Refresh Token 원문은 DB에 저장하지 않습니다. • expires_at은 issued_at보다 이후여야 합니다. • 활성 토큰은 revoked_at·revoked_reason·revoked_by가 모두 NULL입니다. • 폐기된 토큰은 revoked_at·revoked_reason이 필수이며 사용자·관리자 행위 폐기에서만 revoked_by를 기록합니다. • 폐기 사유: LOGOUT / ROTATED / PASSWORD_CHANGED / ACCOUNT_INACTIVATED / ORGANIZATION_SUSPENDED / ORGANIZATION_DELETION_PENDING / TOKEN_REUSE_DETECTED / ADMIN_REVOKED • parent_token_id는 값이 있으면 UNIQUE이고 같은 user_id·token_family_id의 직전 토큰만 참조합니다. • 회전 시 기존 토큰을 ROTATED로 폐기하고 새 토큰의 parent_token_id를 같은 트랜잭션에서 기록합니다. • 폐기 토큰 재사용을 탐지하면 같은 token_family_id의 활성 토큰을 모두 폐기하고 reuse_detected_at을 기록합니다. • issued_ip·issued_user_agent는 발급 시 필수이며 last_used_ip·last_user_agent는 최초 발급값으로 초기화하거나 실제 사용값으로 갱신합니다. • 계정 ';

comment on column refresh_token.token_id is '리프레시 토큰 기본키이다.';

comment on column refresh_token.user_id is '토큰 소유 사용자이다.';

comment on column refresh_token.token_hash is '원문 대신 저장하는 토큰 해시이다.';

comment on column refresh_token.token_family_id is '회전 토큰 계열을 식별하는 값이다.';

comment on column refresh_token.parent_token_id is '회전 전 상위 토큰이다.';

comment on column refresh_token.issued_at is '토큰 발급 시각이다.';

comment on column refresh_token.expires_at is '토큰 만료 시각이다.';

comment on column refresh_token.last_used_at is '토큰이 마지막으로 정상 사용된 시각이다.';

comment on column refresh_token.revoked_at is '토큰이 폐기된 시각이다.';

comment on column refresh_token.revoked_reason is '토큰 폐기 사유이다.';

comment on column refresh_token.revoked_by is '토큰 폐기 요청의 사용자 식별자이며 시스템 폐기이면 NULL이다.';

comment on column refresh_token.issued_ip is '토큰 발급 요청 IP이다.';

comment on column refresh_token.last_used_ip is '토큰의 최근 정상 사용 IP이다.';

comment on column refresh_token.issued_user_agent is '토큰 발급 요청 사용자 에이전트이다.';

comment on column refresh_token.last_user_agent is '토큰의 최근 정상 사용 사용자 에이전트이다.';

comment on column refresh_token.reuse_detected_at is '폐기·회전된 토큰의 재사용 탐지 시각이다.';

comment on column refresh_token.created_at is '토큰 행 생성 시각이다.';

alter table refresh_token
    owner to postgres;

create unique index uq_refresh_token_parent_not_null
    on refresh_token (parent_token_id)
    where (parent_token_id IS NOT NULL);

grant delete, insert, select, update on refresh_token to teamiz_app;

create table report_generation_item
(
    generation_item_id     uuid                     default gen_random_uuid() not null
        constraint pk_report_generation_item
            primary key,
    generation_run_id      uuid                                               not null
        constraint fk_report_generation_item_generation_run_id
            references report_generation_run
            on delete restrict,
    problem_id             uuid                                               not null
        constraint fk_report_generation_item_problem_id
            references assessment_problem
            on delete restrict,
    session_id             uuid                                               not null
        constraint fk_report_generation_item_session_id
            references assessment_session
            on delete restrict,
    score_run_ref          text,
    external_job_id        uuid
        constraint uq_report_generation_item_external_job_id
            unique,
    status                 varchar(30)                                        not null
        constraint ck_report_generation_item_status
            check ((status)::text = ANY
                   (ARRAY [('QUEUED'::character varying)::text, ('RUNNING'::character varying)::text, ('SUCCEEDED'::character varying)::text, ('FAILED'::character varying)::text])),
    failure_reason         text,
    request_payload        jsonb                                              not null
        constraint ck_report_generation_item_request_payload
            check (jsonb_typeof(request_payload) = 'object'::text),
    response_payload       jsonb
        constraint ck_report_generation_item_response_payload
            check ((response_payload IS NULL) OR (jsonb_typeof(response_payload) = 'object'::text)),
    request_payload_hash   char(64)                                           not null
        constraint ck_report_generation_item_request_payload_hash
            check (request_payload_hash ~ '^[0-9a-f]{64}$'::text),
    response_payload_hash  char(64)
        constraint ck_report_generation_item_response_payload_hash
            check ((response_payload_hash IS NULL) OR (response_payload_hash ~ '^[0-9a-f]{64}$'::text)),
    payload_schema_version integer                                            not null
        constraint ck_report_generation_item_payload_schema_version
            check (payload_schema_version >= 1),
    narrative_failed       boolean                  default false             not null,
    problem_no             smallint
        constraint ck_report_generation_item_problem_no
            check ((problem_no IS NULL) OR ((problem_no >= 1) AND (problem_no <= 3))),
    started_at             timestamp with time zone,
    completed_at           timestamp with time zone,
    created_at             timestamp with time zone default CURRENT_TIMESTAMP not null,
    constraint uq_report_generation_item_generation_run_id_problem_id
        unique (generation_run_id, problem_id),
    constraint ck_report_generation_item_completed_at
        check ((completed_at IS NULL) OR (started_at IS NULL) OR (completed_at >= started_at)),
    constraint ck_report_generation_item_status_2
        check (((status)::text <> 'FAILED'::text) OR (failure_reason IS NOT NULL)),
    constraint ck_report_generation_item_status_3
        check (((status)::text <> 'SUCCEEDED'::text) OR (completed_at IS NOT NULL))
);

comment on table report_generation_item is '문제(problem) 단위 AI 리포트 생성 호출의 작업 추적과 응답 버퍼 | 테이블 유형: TRANSACTION | 변경: 신규 | 제약·비고: • /reports는 문제마다 1회 호출되며 각 호출이 별도 jobId·상태·결과를 반환하므로 ReportGenerationRun보다 하위 Grain이 필요합니다. • UNIQUE(generation_run_id, problem_id), UNIQUE(external_job_id) • response_payload에는 문제별 reportMarkdown·narrative·problem·curriculumRefs·retest·versions를 임시 보관하고, 모든 항목이 종료되면 최종 결합 결과만 ReportSnapshot.summary_payload에 확정합니다. • 하나 이상의 항목이 실패하거나 narrative_failed=TRUE이면 상위 실행과 스냅샷은 PARTIAL로 처리합니다. • AiUsage는 context_type=REPORT_GENERATION_ITEM, context_id=generation_item_id로 연결합니다.';

comment on column report_generation_item.generation_item_id is '문제별 리포트 생성 항목을 유일하게 식별하는 기본키이다.';

comment on column report_generation_item.generation_run_id is '이 항목이 속한 전체 리포트 생성 실행을 연결하는 외래 키이다.';

comment on column report_generation_item.problem_id is '리포트 생성 대상 문제를 연결하는 외래 키이다.';

comment on column report_generation_item.session_id is '리포트 생성의 원천 검증 세션을 연결하는 외래 키이다.';

comment on column report_generation_item.score_run_ref is 'AI 요청의 scoreRunId 값을 보존한다.';

comment on column report_generation_item.external_job_id is 'AI 서버가 202 응답으로 반환한 작업 ID이다.';

comment on column report_generation_item.status is '문제별 리포트 생성 항목의 현재 처리 상태이다.';

comment on column report_generation_item.failure_reason is '문제별 생성 실패 사유를 기록한다.';

comment on column report_generation_item.request_payload is 'AI 서버로 전송한 요청 스냅샷을 보존한다.';

comment on column report_generation_item.response_payload is '문제별 AI 응답 원문을 최종 확정 전까지 버퍼링한다.';

comment on column report_generation_item.request_payload_hash is '요청 무결성과 멱등성 검증에 사용하는 SHA-256 해시이다.';

comment on column report_generation_item.response_payload_hash is '응답 무결성 검증에 사용하는 SHA-256 해시이다.';

comment on column report_generation_item.payload_schema_version is 'API 페이로드 계약의 스키마 버전이다.';

comment on column report_generation_item.narrative_failed is '서술(narrative) 생성이 실패했는지를 기록한다.';

comment on column report_generation_item.problem_no is '세션 안에서 이 문제가 몇 번째인지를 기록한다.';

comment on column report_generation_item.started_at is 'AI 작업이 시작된 시각이다.';

comment on column report_generation_item.completed_at is 'AI 작업이 완료된 시각이다.';

comment on column report_generation_item.created_at is '레코드가 최초 생성된 시각이다.';

alter table report_generation_item
    owner to postgres;

grant delete, insert, select, update on report_generation_item to teamiz_app;

create table storage_usage_snapshot
(
    snapshot_id          uuid                     default gen_random_uuid()              not null
        constraint pk_storage_usage_snapshot
            primary key,
    org_id               uuid                                                            not null
        constraint fk_storage_usage_snapshot_org_id
            references organization
            on delete restrict,
    measurement_batch_id uuid                                                            not null,
    used_bytes           bigint                                                          not null
        constraint ck_storage_usage_snapshot_used_bytes
            check (used_bytes >= 0),
    file_count           integer                  default 0                              not null
        constraint ck_storage_usage_snapshot_file_count
            check (file_count >= 0),
    captured_at          timestamp with time zone default CURRENT_TIMESTAMP              not null,
    storage_category     varchar(100)             default 'ORG_TOTAL'::character varying not null
        constraint ck_storage_usage_snapshot_storage_category
            check ((storage_category)::text = ANY
                   (ARRAY [('ORG_TOTAL'::character varying)::text, ('CODE_ARTIFACT'::character varying)::text, ('SESSION_TRANSCRIPT'::character varying)::text, ('SCORE_EVIDENCE'::character varying)::text, ('REPORT_EXPORT'::character varying)::text, ('CURRICULUM_PDF'::character varying)::text, ('DATABASE'::character varying)::text])),
    storage_scope        varchar(100)             default 'ORG_TOTAL'::character varying not null
        constraint ck_storage_usage_snapshot_storage_scope_not_blank
            check (length(TRIM(BOTH FROM storage_scope)) > 0),
    source_type          varchar(100)                                                    not null
        constraint ck_storage_usage_snapshot_source_type
            check ((source_type)::text = ANY
                   (ARRAY [('DB_METADATA'::character varying)::text, ('DB_SYSTEM_CATALOG'::character varying)::text, ('CLOUD_API'::character varying)::text, ('CLOUD_INVENTORY'::character varying)::text, ('CLOUD_METRIC'::character varying)::text])),
    measurement_method   varchar(100)                                                    not null
        constraint ck_storage_usage_snapshot_measurement_method_format
            check ((measurement_method)::text ~ '^[A-Z][A-Z0-9_]{0,99}$'::text),
    source_ref           text,
    calculation_version  integer                  default 1                              not null
        constraint ck_storage_usage_snapshot_calculation_version
            check (calculation_version > 0),
    source_watermark     text,
    created_at           timestamp with time zone default CURRENT_TIMESTAMP              not null,
    constraint uq_storage_usage_snapshot_measurement_batch_id_storage_categ
        unique (measurement_batch_id, storage_category, storage_scope)
);

comment on table storage_usage_snapshot is '기관이 파일과 데이터 저장 공간을 얼마나 쓰고 있었는지 특정 시점에 재어 둔 기록입니다. 체중계의 측정값처럼 원본 파일 자체가 아니라 저장 범주별 바이트 수와 파일 수, 측정 방법을 보존합니다. | 정의서명: StorageUsageSnapshot | 제약·비고: • 성공한 측정값만 적재 • UNIQUE(measurement_batch_id, storage_category, storage_scope) • used_bytes·file_count는 0 이상, calculation_version > 0 • 카테고리: ORG_TOTAL / CODE_ARTIFACT / SESSION_TRANSCRIPT / SCORE_EVIDENCE / REPORT_EXPORT / CURRICULUM_PDF / DATABASE • 원천 유형: DB_METADATA / DB_SYSTEM_CATALOG / CLOUD_API / CLOUD_INVENTORY / CLOUD_METRIC • 화면 범주는 동일 measurement_batch_id·captured_at·calculation_version의 완전한 세트만 합산 • 저장량 상한 판정은 최신 ORG_TOTAL 행 1건 사용 • 원천 비밀값·서명 URL 저장 금지 • file_count=0이 실제 파일 0인지 원천에서 파일 수를 제공하지 않은 것인지는 measurement_method로 구분합니다. • 화면의 필수 저장 범주 행이 빠지면 0으로 채우지 않고 집계 실패로 처리합니다. • storage_category=''ORG_TOTAL'' 행은 기관 전체 한도 판정용이며 세부 범주와 다시 더하지 않습니다. • 성공한 측정값만 저장하고 측정 실패·재시도 정보는 OrganizationUsageSnapshot 또는 집계 작업 로그에서 관리합니다.';

comment on column storage_usage_snapshot.snapshot_id is '저장량 스냅샷을 식별한다.';

comment on column storage_usage_snapshot.org_id is '측정 대상 기관이다.';

comment on column storage_usage_snapshot.measurement_batch_id is '화면 총계에 함께 사용되는 카테고리 행을 하나의 측정 세트로 묶는다.';

comment on column storage_usage_snapshot.used_bytes is '해당 범위가 사용 중인 바이트 수다.';

comment on column storage_usage_snapshot.file_count is '해당 범위의 파일·객체 수다. 원천에서 개수를 제공하지 않으면 0과 미지원 여부를 측정 방법으로 구분한다.';

comment on column storage_usage_snapshot.captured_at is '원천 사용량을 측정한 시각이다.';

comment on column storage_usage_snapshot.storage_category is '총량 또는 API 응답의 저장 카테고리 구분이다.';

comment on column storage_usage_snapshot.storage_scope is '동일 카테고리 안의 측정 범위다.';

comment on column storage_usage_snapshot.source_type is '측정 원천 유형이다.';

comment on column storage_usage_snapshot.measurement_method is '사용량·파일 수 산출 방법 코드다.';

comment on column storage_usage_snapshot.source_ref is '측정 원천의 비민감 참조값이다.';

comment on column storage_usage_snapshot.calculation_version is '집계 계산 버전이다.';

comment on column storage_usage_snapshot.source_watermark is '원천 반영 범위를 재현하기 위한 워터마크다.';

comment on column storage_usage_snapshot.created_at is '스냅샷 행 생성 시각이다.';

alter table storage_usage_snapshot
    owner to postgres;

grant delete, insert, select, update on storage_usage_snapshot to teamiz_app;


