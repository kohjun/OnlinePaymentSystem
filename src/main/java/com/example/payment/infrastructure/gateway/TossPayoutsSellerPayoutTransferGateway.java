package com.example.payment.infrastructure.gateway;

import com.example.payment.application.service.SellerPayoutTransferGateway;
import com.example.payment.domain.model.marketplace.PayoutTransferStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 실제 지급대행 제공자에 송금을 요청하는 어댑터.
 *
 * 상태 분류는 결제 수납 쪽 {@link TossPaymentsGateway}와 같은 기준을 따른다.
 * 송금 요청의 4xx와 달리 상태 조회 오류는 송금 실패의 증거가 아니다.
 * 조회 HTTP 오류와 통신 오류는 UNKNOWN으로 유지한다. 코디네이터가 UNKNOWN을 재송금이
 * 아니라 제공자 상태 조회로만 진행시키기 때문이다. 조회 실패를 FAILED로
 * 잘못 분류하면 자동 복구가 중단되어 이미 나간 돈을 놓칠 수 있다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.payout.transfer.provider",
        havingValue = TossPayoutsSellerPayoutTransferGateway.PROVIDER_NAME
)
public class TossPayoutsSellerPayoutTransferGateway implements SellerPayoutTransferGateway {

    static final String PROVIDER_NAME = "TOSS_PAYOUTS";

    private static final int MAX_FAILURE_REASON_LENGTH = 1000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TossPayoutsProperties properties;

    public TossPayoutsSellerPayoutTransferGateway(RestTemplate payoutsRestTemplate,
                                                  ObjectMapper objectMapper,
                                                  TossPayoutsProperties properties) {
        this.restTemplate = payoutsRestTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public TransferResult transfer(TransferRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        // 제공자 측 멱등 기준. 코디네이터가 payoutId당 하나로 고정해 넘기므로
        // 같은 정산에 대한 재요청은 제공자가 새 송금으로 만들지 않는다.
        body.put("refPayoutId", request.idempotencyKey());
        body.put("amount", request.amount());
        body.put("currency", request.currency());
        body.put("accountRef", request.accountRef());
        body.put("sellerId", request.sellerId());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url("/v1/payouts"),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers(request.idempotencyKey())),
                    String.class
            );
            return readTransferResult(response.getBody(), null, request.idempotencyKey());

        } catch (HttpStatusCodeException e) {
            return httpErrorResult(e, null);
        } catch (RestClientException e) {
            // 연결·읽기 타임아웃을 포함한다. 요청이 제공자에 닿았는지 알 수
            // 없으므로 절대 FAILED로 내리지 않는다.
            log.warn("Payout transfer result is indeterminate: payoutId={}, reason={}",
                    request.payoutId(), e.getMessage());
            return unknown(null, "PAYOUT_NETWORK_ERROR: " + e.getMessage());
        }
    }

    @Override
    public TransferResult getStatus(String providerTransferId, String idempotencyKey) {
        // 송금이 providerTransferId를 받기 전에 UNKNOWN으로 닫히는 경우가 있어
        // 코디네이터가 null을 넘길 수 있다. 그때는 멱등키로 조회한다.
        String lookupUrl = hasText(providerTransferId)
                ? url("/v1/payouts/" + providerTransferId)
                : UriComponentsBuilder.fromHttpUrl(url("/v1/payouts"))
                        .queryParam("refPayoutId", idempotencyKey)
                        .toUriString();

        if (!hasText(providerTransferId) && !hasText(idempotencyKey)) {
            return unknown(null, "PAYOUT_LOOKUP_KEY_MISSING");
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    lookupUrl,
                    HttpMethod.GET,
                    new HttpEntity<>(headers(null)),
                    String.class
            );
            return readTransferResult(response.getBody(), providerTransferId, idempotencyKey);

        } catch (HttpStatusCodeException e) {
            // 조회에서의 404는 "아직 안 만들어졌다"와 "없어졌다"를 구분할 수
            // 없다. 송금이 실제로 나갔을 가능성이 남으므로 UNKNOWN으로 둔다.
            if (e.getStatusCode().value() == 404) {
                return unknown(providerTransferId, "PAYOUT_NOT_FOUND_YET");
            }
            return unknown(providerTransferId, "PAYOUT_LOOKUP_HTTP_" + e.getStatusCode().value());
        } catch (RestClientException e) {
            return unknown(providerTransferId, "PAYOUT_NETWORK_ERROR: " + e.getMessage());
        }
    }

    private TransferResult readTransferResult(String body, String fallbackTransferId, String referenceKey) {
        JsonNode node;
        try {
            node = objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            return unknown(fallbackTransferId, "PAYOUT_RESPONSE_UNPARSEABLE");
        }

        // 목록 조회(refPayoutId 질의)는 배열로 돌아온다.
        if (node != null && node.isArray()) {
            JsonNode match = null;
            for (JsonNode candidate : node) {
                if (hasText(referenceKey) && referenceKey.equals(text(candidate, "refPayoutId"))) {
                    if (match != null) return unknown(fallbackTransferId, "PAYOUT_LOOKUP_AMBIGUOUS");
                    match = candidate;
                }
            }
            if (match == null) return unknown(fallbackTransferId, "PAYOUT_NOT_FOUND_YET");
            node = match;
        }
        if (node == null || !node.isObject()) return unknown(fallbackTransferId, "PAYOUT_RESPONSE_UNPARSEABLE");
        String transferId = text(node, "payoutId");
        String returnedReference = text(node, "refPayoutId");
        JsonNode amount = node.get("amount");
        String currency = text(node, "currency");
        if (!hasText(transferId) || !hasText(referenceKey) || !referenceKey.equals(returnedReference)
                || (hasText(fallbackTransferId) && !fallbackTransferId.equals(transferId))
                || amount == null || !amount.isNumber() || amount.decimalValue().signum() < 0
                || !hasText(currency)) {
            return unknown(fallbackTransferId, "PAYOUT_RESPONSE_MISMATCH");
        }
        String status = text(node, "status");

        PayoutTransferStatus mapped = mapStatus(status);
        String reason = text(node, "failureReason");
        if (!hasText(reason)) {
            reason = "PAYOUT_STATUS_" + defaultText(status, "UNSPECIFIED");
        }
        return new TransferResult(mapped, transferId,
                mapped == PayoutTransferStatus.SUCCEEDED ? null : safeReason(reason),
                returnedReference, amount.decimalValue(), currency);
    }

    private PayoutTransferStatus mapStatus(String providerStatus) {
        if (!hasText(providerStatus)) {
            return PayoutTransferStatus.UNKNOWN;
        }
        return switch (providerStatus.toUpperCase(Locale.ROOT)) {
            case "COMPLETED", "DONE", "SUCCEEDED" -> PayoutTransferStatus.SUCCEEDED;
            case "REQUESTED", "IN_PROGRESS", "PROCESSING" -> PayoutTransferStatus.PROCESSING;
            case "FAILED", "CANCELED", "CANCELLED", "REJECTED" -> PayoutTransferStatus.FAILED;
            default -> PayoutTransferStatus.UNKNOWN;
        };
    }

    private TransferResult httpErrorResult(HttpStatusCodeException e, String providerTransferId) {
        int status = e.getStatusCode().value();
        if (e.getStatusCode().is5xxServerError() || status == 408 || status == 429) {
            return unknown(providerTransferId, "PAYOUT_HTTP_" + status);
        }

        String code = "PAYOUT_HTTP_" + status;
        String message = e.getResponseBodyAsString();
        try {
            JsonNode error = objectMapper.readTree(message == null ? "{}" : message);
            String parsedCode = text(error, "code");
            if (hasText(parsedCode)) {
                code = parsedCode;
            }
            String parsedMessage = text(error, "message");
            if (hasText(parsedMessage)) {
                message = parsedMessage;
            }
        } catch (Exception ignored) {
            // 본문이 JSON이 아니면 원문을 그대로 쓰되 아래에서 마스킹한다.
        }
        return new TransferResult(PayoutTransferStatus.FAILED, providerTransferId,
                safeReason(code + ": " + message));
    }

    private TransferResult unknown(String providerTransferId, String reason) {
        return new TransferResult(PayoutTransferStatus.UNKNOWN, providerTransferId, safeReason(reason));
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(Base64.getEncoder().encodeToString(
                (defaultText(properties.getSecretKey(), "") + ":").getBytes(StandardCharsets.UTF_8)));
        if (hasText(idempotencyKey)) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private String url(String path) {
        return properties.getBaseUrl().replaceAll("/+$", "") + path;
    }

    /**
     * 실패 사유는 seller_payout_transfers.failure_reason에 그대로 저장된다.
     * 계좌 식별자가 응답 본문에 섞여 돌아올 수 있으므로 가린 뒤 자른다.
     */
    private String safeReason(String reason) {
        if (!hasText(reason)) {
            return "Payout result could not be determined.";
        }
        String masked = reason
                .replaceAll("(?i)(accountRef[\"'=: ]+)[^,\\s}\"']+", "$1****")
                .replaceAll("\\d{6,}", "****");
        return masked.length() > MAX_FAILURE_REASON_LENGTH
                ? masked.substring(0, MAX_FAILURE_REASON_LENGTH)
                : masked;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
