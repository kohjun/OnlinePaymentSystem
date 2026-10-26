package com.example.payment.application.service;

public class CommerceSettlementBlockedException extends IllegalStateException {
    public CommerceSettlementBlockedException() {
        super("REFUND_VERIFICATION_REQUIRED: order fulfillment and payout are blocked");
    }
}
