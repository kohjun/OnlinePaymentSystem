# 에브리세일 B2B SaaS 유통 준비 기준

이 문서는 에브리세일을 데모/파일럿/상용 배포 후보로 판단하기 위한 품질 기준입니다.

## 배포 모드

`app.distribution.mode`는 현재 실행 모드를 명확히 표시합니다.

- `DEMO`: 로컬 데모와 세일즈 시연용입니다. `MockPaymentGateway`와 기본 데모 테넌트를 허용합니다.
- `PILOT`: 제한된 파트너 검증용입니다. 외부 인증, 테넌트 헤더, 운영 모니터링을 켜는 것을 권장합니다.
- `PRODUCTION`: 실제 유통 모드입니다. 로컬 DB, Mock 결제, 시뮬레이션 로그인, 테넌트 헤더 미강제 상태를 차단해야 합니다.

운영 전환 시 최소 설정 예시는 다음과 같습니다.

```yaml
app:
  distribution:
    mode: PRODUCTION
    release-channel: stable
    fail-fast-on-blockers: true
    require-real-payment-gateway: true
    require-external-auth: true
    require-tenant-isolation: true
  tenancy:
    require-tenant-header: true
  security:
    external-auth:
      enabled: true
payment:
  default-gateway: REAL_PAYMENT_GATEWAY
```

## 자동 품질 게이트

로컬 릴리즈 후보는 아래 명령으로 검증합니다.

```powershell
.\scripts\verify-distribution.ps1
```

Windows 실행 정책으로 직접 실행이 차단되면 다음 명령을 사용합니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-distribution.ps1
```

검증 항목:

- Java 17+ 런타임 확인
- `compileJava`, `compileTestJava`
- 핵심 단위 테스트
- 에브리세일 브랜딩/인코딩 회귀 검사
- Electron Windows 패키징
- `desktop-app\dist\EverySale-win32-x64\EverySale.exe` 산출물 확인

빠른 개발 루프에서는 다음처럼 일부 단계를 생략할 수 있습니다.

```powershell
.\scripts\verify-distribution.ps1 -SkipDesktopPackage
.\scripts\verify-distribution.ps1 -SkipTests
```

## 운영 Readiness API

배포 후보 서버는 다음 엔드포인트로 상태를 확인합니다.

```text
GET /api/system/readiness
```

응답 상태:

- `READY`: 차단 이슈와 경고가 없습니다.
- `ATTENTION_REQUIRED`: 출시 차단은 아니지만 데모/파일럿 경고가 있습니다.
- `BLOCKED`: 출시 차단 이슈가 있습니다. HTTP 503으로 응답합니다.

주요 검사:

- Redis 연결
- Temporal 기준 경로 활성화
- Outbox 활성화
- 레거시 WAL 비활성화
- Java 버전
- 결제 게이트웨이 모드
- 외부 인증 요구 조건
- 테넌트 식별 강제
- 운영 데이터베이스 엔드포인트
- 릴리즈 채널

## B2B 요청 계약

모든 API 요청은 다음 헤더를 지원합니다.

```text
X-Tenant-Id: partner-company
X-Partner-Id: commerce-team
X-Correlation-Id: request-or-trace-id
```

기본 데모 모드에서는 헤더가 없어도 `everysale-demo`, `demo-partner`가 사용됩니다.
운영 모드에서는 `app.tenancy.require-tenant-header=true`로 `X-Tenant-Id`를 강제해야 합니다.

응답에는 추적을 위해 다음 헤더가 포함됩니다.

```text
X-Tenant-Id
X-Partner-Id
X-Correlation-Id
```

로그 레벨 패턴에도 `tenantId`, `partnerId`, `correlationId`가 포함됩니다.

## 수동 QA 체크리스트

- 앱 실행: `desktop-app\dist\EverySale-win32-x64\EverySale.exe`
- 스플래시 문구가 에브리세일 기준인지 확인
- 커스텀 타이틀바 표시와 최소화/최대화/닫기 동작 확인
- 일반 브라우저 접근 시 타이틀바 숨김과 상단 여백 확인
- `POST /api/reservations/complete` 성공 또는 `PENDING` 확인
- `GET /api/reservations/workflows/{workflowId}` 상태 확인
- Temporal UI에서 activity retry와 compensation 이력 확인
- `GET /api/system/readiness` 결과 확인
- `GET /api/system/health`, `GET /api/payments/health` 확인
- Outbox `PENDING`, `IN_PROGRESS`, `PUBLISHED`, `FAILED` 전이 확인

## 상용 유통 전 남은 필수 항목

현재 저장소는 로컬 데모와 파일럿 후보 기준을 강화한 상태입니다. 실제 상용 SaaS 전환에는 다음 항목이 별도 구현되어야 합니다.

- 외부 IdP 기반 인증/OIDC/SAML 연동
- 테넌트별 데이터 격리 정책과 마이그레이션 전략
- 실제 PG 연동과 결제 취소/환불 정산 검증
- 서명된 설치 파일과 자동 업데이트 채널
- 운영 관측성: 메트릭, 로그 수집, 알림, SLO 대시보드
- 보안 점검: 비밀 관리, 취약점 스캔, 감사 로그, 권한 모델
