package com.contractnotemanager.web;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.contractnotemanager.config.AppProperties;
import com.contractnotemanager.config.ClaudeProperties;
import com.contractnotemanager.config.SharpfinProperties;
import com.contractnotemanager.domain.OrderStatus;
import com.contractnotemanager.repository.BrokerRepository;
import com.contractnotemanager.repository.CustodyRepository;
import com.contractnotemanager.repository.OwnerRepository;
import com.contractnotemanager.repository.PortfolioRepository;
import com.contractnotemanager.web.dto.NamedRef;

/** Options for filters and drop-downs, and the settings the UI shows in its header. */
@RestController
@RequestMapping("/api/v1")
@Transactional(readOnly = true)
public class ReferenceController {

    private static final Sort BY_NAME = Sort.by("name");

    private final CustodyRepository custodies;
    private final OwnerRepository owners;
    private final BrokerRepository brokers;
    private final PortfolioRepository portfolios;
    private final AppProperties app;
    private final SharpfinProperties sharpfin;
    private final ClaudeProperties claude;

    public ReferenceController(CustodyRepository custodies, OwnerRepository owners, BrokerRepository brokers,
            PortfolioRepository portfolios, AppProperties app, SharpfinProperties sharpfin, ClaudeProperties claude) {
        this.custodies = custodies;
        this.owners = owners;
        this.brokers = brokers;
        this.portfolios = portfolios;
        this.app = app;
        this.sharpfin = sharpfin;
        this.claude = claude;
    }

    @GetMapping("/custodies")
    public List<NamedRef> custodies() {
        return custodies.findAll(BY_NAME).stream().map(c -> new NamedRef(c.getId(), c.getName(), c.getTag())).toList();
    }

    @GetMapping("/owners")
    public List<NamedRef> owners() {
        return owners.findAll(BY_NAME).stream().map(o -> new NamedRef(o.getId(), o.getName(), o.getEmail())).toList();
    }

    @GetMapping("/brokers")
    public List<NamedRef> brokers() {
        return brokers.findAll(BY_NAME).stream().map(b -> new NamedRef(b.getId(), b.getName(), b.getBic())).toList();
    }

    @GetMapping("/portfolios")
    public List<NamedRef> portfolios(@RequestParam(required = false) String q) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        return portfolios.findAll(BY_NAME).stream()
                .filter(p -> needle.isEmpty() || p.getName().toLowerCase().contains(needle))
                .limit(50)
                .map(p -> new NamedRef(p.getId(), p.getName(), null))
                .toList();
    }

    @GetMapping("/statuses")
    public List<Map<String, String>> statuses() {
        return java.util.Arrays.stream(OrderStatus.values())
                .map(s -> Map.of("value", s.name(), "label", s.label()))
                .toList();
    }

    @GetMapping("/app-info")
    public Map<String, Object> appInfo() {
        return Map.of(
                "userName", app.userName(),
                "userRole", app.userRole(),
                "environment", sharpfin.environmentLabel(),
                "sharpfinUrl", sharpfin.baseUrl(),
                "sharpfinConfigured", sharpfin.hasCredentials(),
                "claudeConfigured", claude.hasApiKey(),
                "claudeModel", claude.model());
    }
}
