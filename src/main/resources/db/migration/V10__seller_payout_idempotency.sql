CREATE UNIQUE INDEX ux_seller_payouts_source
    ON seller_payouts(source_type, source_id);
