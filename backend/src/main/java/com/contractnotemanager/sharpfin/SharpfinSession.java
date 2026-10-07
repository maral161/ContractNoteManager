package com.contractnotemanager.sharpfin;

import java.time.LocalDate;

import com.fasterxml.jackson.databind.JsonNode;

/** A logged-in connection to Sharpfin, used for one import. Read-only: nothing is written back. */
public interface SharpfinSession extends AutoCloseable {

    /** One page of {@code /api/orders/paginated} (instrument orders, active dates from..to). */
    JsonNode ordersPage(LocalDate from, LocalDate to, int page);

    /** {@code /api/orders/{key}?calculate_allocations=true} – the order with allocation figures. */
    JsonNode orderDetails(String orderKey);

    @Override
    void close();
}
