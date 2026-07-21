# IZ-Get API 계약 초안

기준 목업: `https://team-iz.github.io/Frontend/` (2026-07-20 확인)

모든 경로에는 `ApiPathConfig`가 `/api/v0`을 자동으로 붙인다. 현재 Controller는 프론트엔드 연동을 위한 요청/응답 계약만 정의하며, application/domain/infrastructure 구현 전까지 `501 Not Implemented`를 반환한다.

## 인증 (`auth`)

| Method | Path | Request | Response | 인증 |
|---|---|---|---|---|
| POST | `/auth/login` | `LoginRequest` | `LoginResponse` | 공개 |
| POST | `/auth/refresh` | `RefreshTokenRequest` | `RefreshTokenResponse` | 공개 |
| POST | `/auth/manager-signup` | `ManagerSignupRequest` | `ActivateAccountResponse` | 공개 |
| POST | `/auth/trainee-activation` | `TraineeActivationRequest` | `ActivateAccountResponse` | 공개 |

매니저 회원가입은 초대 토큰, 이름, 비밀번호와 필수 동의 2개를 받는다. 교육생은 명단에 이름이 이미 있으므로 이름을 다시 받지 않고 필수 동의 4개와 선택 동의 1개를 받는다. 두 요청 모두 이메일·기관·기수·권한은 초대 토큰에서 결정하며 클라이언트 입력으로 받지 않는다.

## 회원 (`member`)

| Method | Path | Request | Response | 역할 |
|---|---|---|---|---|
| GET | `/members` | query parameters | `MemberListResponse` | 인증 사용자(서비스에서 기관/기수 범위 제한) |
| GET | `/members/{memberId}` | - | `MemberSummaryResponse` | 인증 사용자(서비스에서 범위 제한) |
| POST | `/members/organizations/{organizationId}/manager-invitations` | `InviteManagerRequest` | `InviteManagerResponse` | 슈퍼어드민, 총괄 |
| POST | `/members/cohorts/{cohortId}/trainees` | `RegisterTraineesRequest` | `RegisterTraineesResponse` | 총괄 |
| PATCH | `/members/{memberId}/status` | `UpdateMemberStatusRequest` | `MemberSummaryResponse` | 슈퍼어드민, 총괄 |
| PATCH | `/members/{memberId}/manager-assignments` | `UpdateManagerAssignmentsRequest` | `MemberSummaryResponse` | 총괄 |

슈퍼어드민은 기관의 총괄 매니저 부트스트랩/복구만 수행하고, 담당 매니저 초대·기수/반 배정은 총괄 매니저의 책임이다. 마지막 총괄의 정지·강등은 application 계층에서 차단해야 한다.

## 기관 (`organization`)

| Method | Path | Request | Response | 역할 |
|---|---|---|---|---|
| GET | `/organizations` | query parameters | `OrganizationListResponse` | 슈퍼어드민 |
| POST | `/organizations` | `CreateOrganizationRequest` | `OrganizationResponse` | 슈퍼어드민 |
| GET | `/organizations/{organizationId}` | - | `OrganizationResponse` | 슈퍼어드민 |
| PATCH | `/organizations/{organizationId}` | `UpdateOrganizationRequest` | `OrganizationResponse` | 슈퍼어드민 |
| DELETE | `/organizations/{organizationId}` | - | `DeleteOrganizationResponse` | 슈퍼어드민 |

삭제는 즉시 물리 삭제가 아닌 soft-delete이다. `purgeAvailableAt` 이전 파기는 허용하지 않는다.

## 기수 (`cohort`)

| Method | Path | Request | Response | 역할 |
|---|---|---|---|---|
| GET | `/cohorts` | query parameters | `CohortListResponse` | 인증 사용자(범위 제한) |
| GET | `/cohorts/{cohortId}` | - | `CohortResponse` | 인증 사용자(범위 제한) |
| POST | `/cohorts` | `CreateCohortRequest` | `CohortResponse` | 총괄 |
| PATCH | `/cohorts/{cohortId}/end` | `EndCohortRequest` | `CohortResponse` | 총괄 |

초기 명단은 선택이며, 등록 대상 이메일의 형식·기관 소속·중복을 검증한 뒤 초대 파이프라인을 실행한다.

## 반 (`classroom`)

| Method | Path | Request | Response | 역할 |
|---|---|---|---|---|
| GET | `/cohorts/{cohortId}/classrooms` | - | `ClassroomListResponse` | 인증 사용자(범위 제한) |
| POST | `/cohorts/{cohortId}/classrooms` | `CreateClassroomRequest` | `ClassroomResponse` | 총괄 |
| PATCH | `/cohorts/{cohortId}/classrooms/{classroomId}/managers` | `UpdateClassroomManagersRequest` | `ClassroomResponse` | 총괄 |
| PATCH | `/cohorts/{cohortId}/classrooms/trainee-assignments` | `AssignTraineesRequest` | `AssignTraineesResponse` | 총괄 |

반은 담당 매니저 없이 생성할 수 있지만 응답에 `managerAssignmentRequired=true`를 표시한다. 교육생 일괄 배정은 동일 기수 내 반에만 허용해야 한다.

## 운영 (`operations`)

| Method | Path | Request | Response | 역할 |
|---|---|---|---|---|
| GET | `/organizations/{organizationId}/operations/usage?period=yyyy-MM` | - | `OrganizationUsageResponse` | 슈퍼어드민 |
| GET | `/organizations/{organizationId}/operations/settings` | - | `OperationSettingResponse` | 슈퍼어드민 |
| PUT | `/organizations/{organizationId}/operations/settings` | `UpdateOperationSettingRequest` | `OperationSettingResponse` | 슈퍼어드민 |

사용량 응답은 저장량(코드 제출·세션 로그·채점 근거·리포트), 활동량(교육생·세션·채점·리포트), 모델별 호출/토큰/비용을 분리한다. 예산 초과는 경고 상태이며 자동 서비스 중단을 의미하지 않는다.

## 감사 (`audit`)

제시된 아키텍처대로 외부 API를 노출하지 않는다. 기관 생성/삭제, 계정 상태·권한·담당 변경, 기수 종료, 반 배정, 운영 설정 변경을 application 계층에서 `AuditService`로 기록하며, 대상 ID·행위자 ID·변경 전/후·사유·시각을 보존해야 한다.

## 보안 경계

- JWT access token은 `email`, `role`, `organizationId`, `tokenType` 클레임을 가진다.
- Controller의 역할 검사는 1차 방어선이다. `organizationId`, 담당 기수, 담당 반 범위는 application 계층에서 반드시 다시 검사한다.
- 공개 경로는 인증 API와 Swagger 문서뿐이다.
- CORS는 로컬 프론트엔드와 배포 목업 origin만 허용한다.
