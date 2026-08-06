ALTER TABLE inventory_sync_issues
    DROP CONSTRAINT IF EXISTS chk_inventory_sync_issue_type;

ALTER TABLE inventory_sync_issues
    ADD CONSTRAINT chk_inventory_sync_issue_type CHECK (issue_type IN (
        'RESERVE_RELEASE_REQUIRED',
        'CONFIRM_DB_APPLY_REQUIRED',
        'RELEASE_DB_APPLY_REQUIRED',
        'ORDER_CANCELLATION_RESTORE_REQUIRED'
    ));
