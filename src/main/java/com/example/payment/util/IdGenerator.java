package com.example.payment.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 결제 시스템에서 사용하는 ID 생성 유틸리티
 * - 결제 ID, 주문 ID, 예약 ID 등 고유 식별자 생성
 * - 정렬 가능하고 충돌 가능성이 낮은 ID 생성
 */
public class IdGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final AtomicInteger SEQUENCE = new AtomicInteger(0);

    /**
     * 결제 ID 생성
     * 형식: PAY-{날짜시간}-{시퀀스}-{랜덤}
     * 예: PAY-220405123456-001-5A7B
     */
    public static String generatePaymentId() {
        LocalDateTime now = LocalDateTime.now();
        String dateTime = now.format(DATE_FORMAT);
        String sequence = String.format("%03d", nextSequence());
        String random = generateRandomHex(4);

        return "PAY-" + dateTime + "-" + sequence + "-" + random;
    }

    /**
     * 예약 ID 생성
     * 형식: RES-{날짜시간}-{시퀀스}-{랜덤}
     */
    public static String generateReservationId() {
        LocalDateTime now = LocalDateTime.now();
        String dateTime = now.format(DATE_FORMAT);
        String sequence = String.format("%03d", nextSequence());
        String random = generateRandomHex(4);

        return "RES-" + dateTime + "-" + sequence + "-" + random;
    }

    /**
     * 멱등성 키 생성
     * 외부에서 제공되지 않은 경우 사용
     */
    public static String generateIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * 시퀀스 번호 증가 (0-999, 순환)
     */
    private static int nextSequence() {
        return SEQUENCE.getAndUpdate(current -> (current + 1) % 1000);
    }

    /**
     * 랜덤 16진수 문자열 생성
     */
    private static String generateRandomHex(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int val = ThreadLocalRandom.current().nextInt(16);
            sb.append(Integer.toHexString(val).toUpperCase());
        }
        return sb.toString();
    }
}