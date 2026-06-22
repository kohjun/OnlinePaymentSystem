CREATE TABLE IF NOT EXISTS raffle_entries (
    entry_id VARCHAR(255) PRIMARY KEY,
    sale_event_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT fk_raffle_entry_event FOREIGN KEY (sale_event_id) REFERENCES sale_events(sale_event_id),
    CONSTRAINT uq_raffle_entry_customer UNIQUE (sale_event_id, customer_id)
);

CREATE TABLE IF NOT EXISTS raffle_winners (
    winner_id VARCHAR(255) PRIMARY KEY,
    sale_event_id VARCHAR(255) NOT NULL,
    entry_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    checkout_status VARCHAR(50) NOT NULL,
    draw_seed VARCHAR(255),
    drawn_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    checkout_completed_at TIMESTAMP,
    CONSTRAINT fk_raffle_winner_event FOREIGN KEY (sale_event_id) REFERENCES sale_events(sale_event_id),
    CONSTRAINT fk_raffle_winner_entry FOREIGN KEY (entry_id) REFERENCES raffle_entries(entry_id),
    CONSTRAINT uq_raffle_winner_entry UNIQUE (entry_id)
);

CREATE INDEX IF NOT EXISTS idx_raffle_entries_event_status ON raffle_entries(sale_event_id, status);
CREATE INDEX IF NOT EXISTS idx_raffle_winners_event_status ON raffle_winners(sale_event_id, checkout_status);
CREATE INDEX IF NOT EXISTS idx_raffle_winners_customer ON raffle_winners(customer_id);
