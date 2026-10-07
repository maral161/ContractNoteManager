package com.contractnotemanager.importer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.contractnotemanager.config.SharpfinProperties;
import com.contractnotemanager.domain.ImportRun;
import com.contractnotemanager.domain.ImportRunStatus;
import com.contractnotemanager.repository.ImportRunRepository;
import com.contractnotemanager.sharpfin.SharpfinClient;
import com.contractnotemanager.sharpfin.SharpfinException;
import com.contractnotemanager.sharpfin.SharpfinSession;
import com.contractnotemanager.web.error.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runs one import when the user clicks "Import from Sharpfin": reads all pages of the orders list,
 * then each order's details with allocation figures, and upserts every order in its own transaction.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final SharpfinClient sharpfin;
    private final OrderImporter importer;
    private final ImportRunRepository runs;
    private final SharpfinProperties props;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ImportService(SharpfinClient sharpfin, OrderImporter importer, ImportRunRepository runs,
            SharpfinProperties props) {
        this.sharpfin = sharpfin;
        this.props = props;
        this.importer = importer;
        this.runs = runs;
    }

    public boolean isRunning() {
        return running.get();
    }

    public ImportRun runImport(LocalDate from, LocalDate to, ImportDateType dateType) {
        if (from == null || to == null || to.isBefore(from)) {
            throw ApiException.badRequest("Invalid date range");
        }
        if (!running.compareAndSet(false, true)) {
            throw ApiException.conflict("An import is already running");
        }
        try {
            return doImport(from, to, dateType == null ? ImportDateType.BOOKED : dateType);
        } finally {
            running.set(false);
        }
    }

    private ImportRun doImport(LocalDate from, LocalDate to, ImportDateType dateType) {
        ImportRun run = new ImportRun();
        run.setStartedAt(Instant.now());
        run.setStatus(ImportRunStatus.RUNNING);
        run.setFromDate(from);
        run.setToDate(to);
        run.setDateType(dateType);
        run = runs.save(run);

        Set<String> received = new HashSet<>();
        try (SharpfinSession session = sharpfin.openSession()) {
            int page = 1;
            int pages;
            do {
                JsonNode result;
                try {
                    result = session.ordersPage(sharpfinDateType(dateType), from, to, page);
                } finally {
                    if (page == 1) {
                        run.setRequestUrl(session.lastRequestUrl());
                    }
                }
                pages = Math.max(1, result.path("no_of_pages").asInt(1));
                if (page == 1) {
                    run.setExpectedCount(result.path("no_of_elements").asInt(0));
                }
                for (JsonNode listOrder : result.path("orders")) {
                    String key = listOrder.path("key").asText();
                    if (received.add(key)) {
                        importOne(session, listOrder, run);
                    }
                }
                page++;
            } while (page <= pages);
        } catch (SharpfinException e) {
            log.warn("Import failed: {}", e.getMessage());
            run.setErrorMessage(e.getMessage());
            run.setStatus(ImportRunStatus.FAILED);
        }

        if (run.getStatus() == ImportRunStatus.RUNNING) {
            boolean complete = run.getExpectedCount() == null || received.size() >= run.getExpectedCount();
            if (!complete && run.getErrorMessage() == null) {
                run.setErrorMessage("Expected " + run.getExpectedCount() + " orders, received " + received.size());
            }
            boolean clean = complete && run.getFailedCount() == 0 && run.getDetailsMissingCount() == 0;
            run.setStatus(clean ? ImportRunStatus.SUCCESS : ImportRunStatus.PARTIAL);
        }
        run.setFinishedAt(Instant.now());
        log.info("Import {} {} {}..{}: {} created, {} updated, {} skipped, {} conflicts, {} failed",
                run.getStatus(), dateType, from, to, run.getCreatedCount(), run.getUpdatedCount(), run.getSkippedCount(),
                run.getConflictCount(), run.getFailedCount());
        return runs.save(run);
    }

    private String sharpfinDateType(ImportDateType dateType) {
        SharpfinProperties.DateTypes values = props.dateTypes();
        return switch (dateType) {
            case BOOKED -> values.booked();
            case TRADED -> values.traded();
            case SETTLED -> values.settled();
        };
    }

    private void importOne(SharpfinSession session, JsonNode listOrder, ImportRun run) {
        String key = listOrder.path("key").asText();
        JsonNode payload = listOrder;
        boolean withDetails = false;
        try {
            payload = session.orderDetails(key);
            withDetails = true;
        } catch (SharpfinException e) {
            log.warn("Details for order {} could not be read: {}", key, e.getMessage());
            run.setDetailsMissingCount(run.getDetailsMissingCount() + 1);
        }
        try {
            switch (importer.importOrder(payload, withDetails)) {
                case CREATED -> run.setCreatedCount(run.getCreatedCount() + 1);
                case UPDATED -> run.setUpdatedCount(run.getUpdatedCount() + 1);
                case SKIPPED -> run.setSkippedCount(run.getSkippedCount() + 1);
                case CONFLICT -> run.setConflictCount(run.getConflictCount() + 1);
            }
        } catch (RuntimeException e) {
            log.warn("Order {} could not be imported: {}", key, e.getMessage());
            run.setFailedCount(run.getFailedCount() + 1);
            if (run.getErrorMessage() == null) {
                run.setErrorMessage("Order " + key + ": " + e.getMessage());
            }
        }
    }
}
