package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import lombok.Getter;

@Getter
public enum SetupKind {
    CATEGORIES("categories", "product_category", "name", "CATEGORY"),
    TABLES("tables", "pool_table", "name", "POOL_TABLE"),
    CUSTOMER_TYPES("customer-types", "customer_type", "name", "CUSTOMER_TYPE"),
    EXPENSE_CATEGORIES("expense-categories", "expense_category", "name", "EXPENSE_CATEGORY"),
    VOUCHERS("vouchers", "voucher_batch", "coalesce(note, quantity || ' vouchers · ' || minutes || ' min')", "VOUCHER_BATCH"),
    STAFF("staff", "app_user", "full_name", "USER");

    private final String path;
    private final String table;
    private final String label;
    private final String action;

    SetupKind(String path, String table, String label, String action) {
        this.path = path;
        this.table = table;
        this.label = label;
        this.action = action;
    }

    public static SetupKind fromPath(String path) {
        for (SetupKind kind : values()) {
            if (kind.path.equals(path)) return kind;
        }
        throw new ResourceNotFoundException("Setup list", path);
    }
}
