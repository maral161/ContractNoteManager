package com.contractnotemanager.importer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.contractnotemanager.domain.Asset;
import com.contractnotemanager.domain.Broker;
import com.contractnotemanager.domain.Custody;
import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderAllocation;
import com.contractnotemanager.domain.Owner;
import com.contractnotemanager.domain.Portfolio;
import com.contractnotemanager.repository.AssetRepository;
import com.contractnotemanager.repository.BrokerRepository;
import com.contractnotemanager.repository.CustodyRepository;
import com.contractnotemanager.repository.OwnerRepository;
import com.contractnotemanager.repository.PortfolioRepository;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Copies a Sharpfin order payload onto the local entities. Reference data (custody, asset, owner,
 * broker, portfolio) is upserted by its Sharpfin key. Must run inside a transaction.
 */
@Component
public class OrderPayloadMapper {

    private final CustodyRepository custodies;
    private final AssetRepository assets;
    private final OwnerRepository owners;
    private final BrokerRepository brokers;
    private final PortfolioRepository portfolios;

    public OrderPayloadMapper(CustodyRepository custodies, AssetRepository assets, OwnerRepository owners,
            BrokerRepository brokers, PortfolioRepository portfolios) {
        this.custodies = custodies;
        this.assets = assets;
        this.owners = owners;
        this.brokers = brokers;
        this.portfolios = portfolios;
    }

    /** Copies all order fields and allocations from the payload (used on import and on "revert"). */
    public void applyOrder(Order order, JsonNode n, boolean withDetails) {
        order.setSfKey(text(n, "key"));
        order.setSfVersion(n.path("version").asInt(0));
        order.setSfStatus(text(n, "status"));
        order.setSfDeleted(n.path("deleted").asBoolean(false));
        order.setCustody(upsertCustody(n.path("custody")));
        order.setAsset(upsertAsset(n.path("asset")));
        order.setOwner(upsertOwner(n.path("owner")));
        order.setBroker(upsertBroker(n.path("broker")));
        order.setOrderType(text(n, "type"));
        order.setSide(text(n, "side"));
        order.setPrice(decimal(n, "price"));
        order.setValue(orZero(decimal(n, "value")));
        order.setCurrencyCode(text(n, "currency_code"));
        order.setSettlementAmount(decimal(n, "settlement_amount"));
        order.setCommission(orZero(decimal(n, "commission")));
        order.setAccruedInterest(orZero(decimal(n, "accrued_interest")));
        order.setUpFrontFee(orZero(decimal(n, "up_front_fee")));
        order.setIssuerFee(orZero(decimal(n, "issuer_fee")));
        order.setBookedDate(date(n, "booked_date"));
        order.setValidTo(date(n, "valid_to"));
        order.setSource(text(n, "source"));
        order.setComment(text(n, "comment"));
        order.setPartialFillAllowed(n.path("partial_fill_allowed").asBoolean(false));
        order.setMerged(n.path("is_merged").asBoolean(false));
        order.setHandledManually(n.path("handled_manually").asBoolean(false));
        order.setHandledManuallyEligible(n.path("handled_manually_eligible").asBoolean(false));
        order.setExchange(n.has("exchange") ? n.get("exchange").toString() : null);
        order.setRawPayload(n.toString());
        order.setLastSyncedAt(Instant.now());
        order.setDetailsMissing(!withDetails);
        if (withDetails) {
            order.setDetailsImportedAt(Instant.now());
        }
        applyAllocations(order, n.path("allocation"));
    }

    private void applyAllocations(Order order, JsonNode allocationNodes) {
        Map<String, OrderAllocation> existing = new HashMap<>();
        for (OrderAllocation a : order.getAllocations()) {
            existing.put(a.getPortfolio().getSfKey(), a);
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode a : allocationNodes) {
            String portfolioKey = text(a, "key");
            if (portfolioKey == null || !seen.add(portfolioKey)) {
                continue;
            }
            OrderAllocation allocation = existing.get(portfolioKey);
            if (allocation == null) {
                allocation = new OrderAllocation();
                allocation.setPortfolio(upsertPortfolio(portfolioKey, text(a, "portfolio_name")));
                order.addAllocation(allocation);
            } else if (text(a, "portfolio_name") != null) {
                allocation.getPortfolio().setName(text(a, "portfolio_name"));
            }
            BigDecimal value = orZero(decimal(a, "value"));
            allocation.setValue(value);
            allocation.setOriginalValue(value);
            allocation.setStatus(text(a, "status"));
            allocation.setReason(text(a, "reason"));
            allocation.setExternalId(text(a, "external_id"));
            allocation.setCommission(decimal(a, "commission"));
            allocation.setPortfolioQuantity(decimal(a, "portfolio_quantity"));
            allocation.setPortfolioWeight(decimal(a, "portfolio_weight"));
            allocation.setTargetQuantity(decimal(a, "target_quantity"));
            allocation.setTargetWeight(decimal(a, "target_weight"));
            allocation.setOrderWeight(decimal(a, "order_weight"));
        }
        order.getAllocations().removeIf(a -> !seen.contains(a.getPortfolio().getSfKey()));
    }

    Custody upsertCustody(JsonNode c) {
        String key = text(c, "key");
        if (key == null) {
            throw new IllegalArgumentException("Order without custody");
        }
        Custody custody = custodies.findBySfKey(key).orElseGet(Custody::new);
        custody.setSfKey(key);
        custody.setName(text(c, "name"));
        custody.setTag(text(c, "tag"));
        custody.setDomicile(text(c, "domicile"));
        custody.setContractNotesEnabled(c.path("contract_notes_enabled").asBoolean(false));
        custody.setDeleted(c.path("deleted").asBoolean(false));
        custody.setRawPayload(c.toString());
        return custodies.save(custody);
    }

    Asset upsertAsset(JsonNode a) {
        String key = text(a, "key");
        if (key == null) {
            throw new IllegalArgumentException("Order without asset");
        }
        Asset asset = assets.findBySfKey(key).orElseGet(Asset::new);
        asset.setSfKey(key);
        asset.setName(text(a, "name"));
        asset.setType(a.path("type").asText("unknown"));
        asset.setIsin(isin(a));
        asset.setDomicile(text(a, "domicile"));
        asset.setQuoteCurrencyCode(text(a, "quote_currency_code"));
        asset.setSettlementCurrencyCode(text(a, "settlement_currency_code"));
        asset.setSettlementDuration(a.has("settlement_duration") ? a.get("settlement_duration").asInt() : null);
        JsonNode typeData = a.path("type_data");
        asset.setQtyDecimals(typeData.has("qty_decimals") ? typeData.get("qty_decimals").asInt() : null);
        asset.setClassifications(a.has("classifications") ? a.get("classifications").toString() : null);
        asset.setTypeData(typeData.isMissingNode() ? null : typeData.toString());
        asset.setDeleted(a.path("deleted").asBoolean(false));
        return assets.save(asset);
    }

    Owner upsertOwner(JsonNode o) {
        String key = text(o, "key");
        if (key == null) {
            return null;
        }
        Owner owner = owners.findBySfKey(key).orElseGet(Owner::new);
        owner.setSfKey(key);
        owner.setName(text(o, "name"));
        owner.setEmail(text(o, "email"));
        owner.setType(text(o.path("type"), "id"));
        return owners.save(owner);
    }

    /** Returns null when the payload has no broker yet (an empty object). */
    Broker upsertBroker(JsonNode b) {
        String key = text(b, "key");
        if (key == null) {
            return null;
        }
        Broker broker = brokers.findBySfKey(key).orElseGet(Broker::new);
        broker.setSfKey(key);
        broker.setName(text(b, "name"));
        broker.setBic(text(b, "bic"));
        broker.setLei(text(b, "lei"));
        return brokers.save(broker);
    }

    Portfolio upsertPortfolio(String key, String name) {
        Portfolio portfolio = portfolios.findBySfKey(key).orElseGet(Portfolio::new);
        portfolio.setSfKey(key);
        portfolio.setName(name != null ? name : key);
        return portfolios.save(portfolio);
    }

    private static String isin(JsonNode asset) {
        for (JsonNode id : asset.path("identifiers")) {
            if ("isin".equalsIgnoreCase(id.path("key").asText())) {
                return id.path("value").asText(null);
            }
        }
        return null;
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }

    static BigDecimal decimal(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field '" + field + "' is not a number: " + s);
        }
    }

    static LocalDate date(JsonNode n, String field) {
        String s = text(n, field);
        return s == null ? null : LocalDate.parse(s);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
