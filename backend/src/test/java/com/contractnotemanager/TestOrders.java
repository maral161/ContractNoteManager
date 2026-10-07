package com.contractnotemanager;

import java.math.BigDecimal;

import com.contractnotemanager.domain.Asset;
import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderStatus;

/** Builds orders like the Sharpfin ones of 2026-10-07 for unit tests. */
public final class TestOrders {

    private TestOrders() {
    }

    public static Order order(long id, String asset, String isin, String side, String quantity, String price,
            String settlement, String currency, OrderStatus status) {
        Asset a = new Asset();
        a.setName(asset);
        a.setIsin(isin);
        a.setSettlementDuration(2);
        Order o = new Order();
        o.setId(id);
        o.setAsset(a);
        o.setSide(side);
        o.setOrderType("quantity");
        o.setValue(new BigDecimal(quantity));
        o.setPrice(new BigDecimal(price));
        o.setSettlementAmount(new BigDecimal(settlement));
        o.setCurrencyCode(currency);
        o.setStatus(status);
        return o;
    }

    public static Order abb(OrderStatus status) {
        return order(1, "ABB", "CH0012221716", "sell", "1864", "435.52", "811809.28", "SEK", status);
    }

    public static Order apple(OrderStatus status) {
        return order(2, "Apple Inc", "US0378331005", "sell", "473", "219.65", "103894.45", "USD", status);
    }

    public static Order barclays(OrderStatus status) {
        return order(4, "Barclays PLC", "GB0031348658", "buy", "98", "123.57", "-12109.86", "GBP", status);
    }
}
