# 에브리세일 C2C 상용 시나리오 검증

이 문서는 판매자 가입부터 상품 공개, Toss 결제 의도 생성, 운영 큐 감사까지 한 번에 검증하는 기준 절차입니다. 실제 Toss 승인 단계는 결제창에서 사용자가 수행하며, 저장소의 `.env`나 결제 키를 변경하지 않습니다.

## 사전 조건

1. `docker compose up -d`로 Postgres, Redis, Kafka, Temporal을 실행합니다.
2. 애플리케이션을 `bootRun`으로 실행합니다.
3. `GET /api/system/health`가 응답하는지 확인합니다.
4. Toss 테스트 결제까지 확인하려면 현재 `.env`의 `test_` client/secret key가 애플리케이션에 주입되어 있어야 합니다.

## 자동 스모크

호출 순서와 요청 본문만 먼저 확인하려면 다음을 실행합니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-c2c-commercial-smoke.ps1 -DryRun
```

로컬 서버에 실제 테스트 데이터를 생성하고 전체 흐름을 검증합니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-c2c-commercial-smoke.ps1
```

Toss 키를 주입하지 않은 환경에서는 결제 의도 생성만 건너뜁니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-c2c-commercial-smoke.ps1 -SkipPaymentIntent
```

스크립트가 검증하는 순서는 다음과 같습니다.

1. 서비스 health/readiness 조회
2. C2C 판매자 프로필 생성과 본인 인증 제출
3. 운영 큐를 통한 판매자 승인
4. 토큰화된 정산 계좌 제출과 운영 승인
5. 판매글 초안 생성, 검수 제출, 운영 승인
6. 정가 판매 이벤트 생성과 공개
7. 공개 marketplace 조회
8. 서버 가격 기준 Toss 결제 의도 생성
9. 운영 큐와 관리자 감사 이력 조회

각 실행은 고유한 `runId`를 사용하므로 이전 스모크 데이터와 충돌하지 않습니다. 로컬 테스트 데이터는 개발용 시스템 초기화 절차로 정리하며 운영 데이터베이스에서 이 스크립트를 실행하지 않습니다.

## 좌석형 티켓 예매 수동 검증

1. `http://localhost:8080/app/`에서 `티켓 예매` 배지가 붙은 이벤트를 엽니다.
2. 대기열이 활성화된 환경에서는 `WAITING` 순번이 감소한 뒤 좌석표가 자동으로 열리는지 확인합니다.
3. 첫 번째 고객이 좌석을 선택하면 10분 선점 타이머와 선택 좌석이 표시되는지 확인합니다.
4. 두 번째 고객이 같은 좌석을 선택하면 `409 SEAT_ALREADY_HELD`로 거부되는지 확인합니다.
5. 첫 번째 고객이 Toss intent를 생성한 뒤 좌석 해제 요청이 `409 SEAT_HOLD_BOUND_TO_PAYMENT_INTENT`로 거부되는지 확인합니다.
6. Toss 결제창에서 취소하면 intent가 `CANCELLED`가 되고 좌석이 즉시 선택 가능 상태로 돌아오는지 확인합니다.
7. 결제가 성공하면 `inventory_reservations.active_seat_id`가 좌석 중복 확정을 막고 구매 내역에 좌석 ID와 `발급 완료` 상태가 표시되는지 확인합니다.

합격 기준은 한 이벤트에서 고객당 활성 선점 한 좌석, 좌석당 활성 예약 한 건, 결제 취소 후 즉시 반환, 결제 성공 후 디지털 티켓 주문 원장 생성입니다.

## 실시간 경매 수동 검증

1. 에브리세일 메인 화면을 브라우저 창 두 개에서 엽니다.
2. 두 창에서 같은 경매 이벤트를 선택하고 `실시간 경매 연결됨` 상태를 확인합니다.
3. 각 창의 고객 ID를 서로 다르게 설정합니다.
4. 첫 번째 창에서 최소 입찰가 이상으로 입찰합니다.
5. 새로고침 없이 두 번째 창의 최고 입찰가, 최고 입찰자, 최소 입찰가, 최근 입찰 기록이 갱신되는지 확인합니다.
6. 두 번째 창에서 더 높은 금액으로 입찰하고 첫 번째 창이 동일하게 갱신되는지 확인합니다.
7. 마감 직전 입찰 시 종료 시간이 연장되는지 확인합니다.
8. 관리자 마감 또는 자동 마감 후 양쪽 창에서 입찰 버튼이 비활성화되고 낙찰자가 표시되는지 확인합니다.
9. 낙찰자가 Toss 결제창으로 진입하고 다른 고객은 거부되는지 확인합니다.

합격 기준은 동시 입찰 중 최고 금액 한 건만 `WINNING`이고, 모든 연결 화면이 새로고침 없이 동일한 상태를 표시하는 것입니다.

## 래플 수동 검증

1. 브라우저 창 두 개에서 같은 래플을 선택하고 `래플 실시간 연결됨` 상태를 확인합니다.
2. 서로 다른 고객 ID로 응모합니다.
3. 양쪽 화면의 현재 응모자 수가 즉시 증가하는지 확인합니다.
4. 같은 고객 ID로 다시 응모해 중복 행이 생성되지 않는지 확인합니다.
5. 관리자가 seed와 당첨자 수를 지정해 추첨합니다.
6. 모든 창에서 추첨 완료 상태가 즉시 반영되는지 확인합니다.
7. 당첨자만 Toss 결제 의도를 생성할 수 있고 미당첨자는 거부되는지 확인합니다.
8. 결제 기한이 지난 당첨권은 `EXPIRED`가 되고 결제가 거부되는지 확인합니다.

합격 기준은 `(saleEventId, customerId)`당 응모 한 건, 동일 seed의 동일 당첨 결과, 당첨 수량을 초과하지 않는 결제권 생성입니다.

## Toss 결제와 복구

1. Toss 테스트 결제창에서 테스트 카드 승인을 완료합니다.
2. 성공 redirect가 `/api/payments/toss/confirm`으로 이어지는지 확인합니다.
3. `200 SUCCESS` 또는 `202 PENDING` 응답을 확인합니다.
4. `PENDING`이면 workflow status를 조회해 최종 `SUCCESS`까지 확인합니다.
5. 브라우저 redirect를 중단한 뒤 reconciliation worker가 장기 미확정 intent를 복구하는지 확인합니다.
6. 중복 confirm이 추가 승인이나 중복 marketplace order를 만들지 않는지 확인합니다.

`202 PENDING` 자체는 최종 성공으로 집계하지 않습니다. workflow가 `SUCCESS`로 끝나고 주문, 결제, 재고, outbox가 일치해야 통과입니다.

## 운영 판정

배포 후보는 다음 세 명령을 모두 통과해야 합니다.

```powershell
.\gradlew.bat test --no-daemon
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-integration.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-distribution.ps1
```

운영 프로파일은 Temporal worker, inventory reconciliation, Toss reconciliation, auction auto-close, Flyway, JPA schema validation이 하나라도 꺼지면 readiness에서 차단됩니다.
다중 인스턴스 배포에서는 Redis marketplace realtime broadcast가 필수이며, 실제 판매자 지급은 외부 payout transfer adapter가 구성되어야 합니다. `LEDGER_ONLY`는 로컬 흐름 검증용이며 실제 송금을 의미하지 않습니다.
