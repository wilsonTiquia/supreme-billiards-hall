package com.supremebilliardshall.billiards_hall_system.service.impl;

import java.util.Map;

/**
 * Stored constants to the words the owner uses.
 *
 * Kept on the server rather than in the browser because the constant and its wording have to
 * agree wherever they are read, and because the filter list and the feed itself must never
 * disagree about what an action is called.
 *
 * The wording matches the rest of the interface deliberately: the quick-sale screen says "Give
 * away" and the losses tile says "Given away", so the log says "Given away" too. An audit trail
 * that invents its own vocabulary makes the reader translate.
 */
final class AuditVocabulary {

    private AuditVocabulary() {
    }

    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("BILL_LINE_VOIDED", "Line voided"),
            Map.entry("BUSINESS_DAY_CLOSED", "Day closed"),
            Map.entry("CASH_COUNT_CORRECTED", "Drawer count corrected"),
            Map.entry("CASH_COUNT_SUPERSEDED", "Drawer recounted after close"),
            Map.entry("CASH_FLOAT_OVERRIDDEN", "Float differed from the standard"),
            Map.entry("CATEGORY_CREATED", "Category added"),
            Map.entry("CATEGORY_UPDATED", "Category edited"),
            Map.entry("CATEGORY_ARCHIVED", "Category archived"),
            Map.entry("CUSTOMER_TYPE_CREATED", "Customer type added"),
            Map.entry("CUSTOMER_TYPE_UPDATED", "Customer type edited"),
            Map.entry("CUSTOMER_TYPE_ARCHIVED", "Customer type archived"),
            Map.entry("POOL_TABLE_CREATED", "Table added"),
            Map.entry("POOL_TABLE_UPDATED", "Table edited"),
            Map.entry("POOL_TABLE_ARCHIVED", "Table archived"),
            Map.entry("POOL_TABLE_RATE_CHANGED", "Table rate changed"),
            Map.entry("PRODUCT_CREATED", "Product added"),
            Map.entry("PRODUCT_UPDATED", "Product edited"),
            Map.entry("PRODUCT_ARCHIVED", "Product archived"),
            Map.entry("PRODUCT_RESTORED", "Product restored"),
            Map.entry("SESSION_RATE_OVERRIDE", "Friend rate given"),
            Map.entry("SESSION_TIME_REDUCED", "Time reduced"),
            Map.entry("SETTING_CHANGED", "Setting changed"),
            Map.entry("STOCK_DELIVERY", "Delivery received"),
            Map.entry("STOCK_CORRECTION", "Stock corrected"),
            Map.entry("STOCK_STAFF_COMP", "Given away"));

    // What kind of thing the row is about, for the column that sits beside the subject.
    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("product", "Product"),
            Map.entry("product_category", "Category"),
            Map.entry("customer_type", "Customer type"),
            Map.entry("pool_table", "Table"),
            Map.entry("pool_table_rate", "Table"),
            Map.entry("table_session", "Table"),
            Map.entry("bill_line", "Bill line"),
            Map.entry("cash_count", "Drawer count"),
            Map.entry("branch_setting", "Setting"),
            Map.entry("stock_movement", "Product"));

    /*
     * An unmapped constant falls back to something readable rather than to the constant itself.
     * This runs on live data: a new audited action shipped without a word here should read as
     * "Session rate override" on the screen, not as SESSION_RATE_OVERRIDE, and certainly not
     * blank. The map is still the right place to give it proper wording.
     */
    static String action(String action) {
        if (action == null) {
            return "—";
        }
        String known = ACTIONS.get(action);
        return known != null ? known : sentenceCase(action);
    }

    static String entity(String entityTable) {
        if (entityTable == null) {
            return "—";
        }
        String known = ENTITIES.get(entityTable);
        return known != null ? known : sentenceCase(entityTable);
    }

    private static String sentenceCase(String constant) {
        String words = constant.replace('_', ' ').toLowerCase();
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
