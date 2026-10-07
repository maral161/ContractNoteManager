package com.contractnotemanager.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.contractnotemanager.contractnote.ContractNoteService;
import com.contractnotemanager.domain.OrderStatus;
import com.contractnotemanager.service.OrderService;
import com.contractnotemanager.web.dto.AdvanceStatusRequest;
import com.contractnotemanager.web.dto.BulkRequest;
import com.contractnotemanager.web.dto.BulkResult;
import com.contractnotemanager.web.dto.ContractNoteDto;
import com.contractnotemanager.web.dto.OrderDto;
import com.contractnotemanager.web.dto.OrderFilter;
import com.contractnotemanager.web.dto.PageDto;
import com.contractnotemanager.web.dto.StatusHistoryDto;
import com.contractnotemanager.web.dto.UpdateOrderRequest;
import com.contractnotemanager.web.error.ApiException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    /** Sortable columns of the table → entity properties. */
    private static final java.util.Map<String, String> SORT_FIELDS = java.util.Map.of(
            "asset", "asset.name",
            "isin", "asset.isin",
            "status", "status",
            "bookedDate", "bookedDate",
            "tradedDate", "tradedDate",
            "settlementDate", "settlementDate",
            "validTo", "validTo",
            "owner", "owner.name",
            "noteMatched", "noteStatus");

    private final OrderService orders;
    private final ContractNoteService notes;

    public OrderController(OrderService orders, ContractNoteService notes) {
        this.orders = orders;
        this.notes = notes;
    }

    @GetMapping
    public PageDto<OrderDto> list(
            @RequestParam(defaultValue = "BOOKED") OrderFilter.DateType dateType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String asset,
            @RequestParam(required = false) String portfolio,
            @RequestParam(required = false) List<Long> owner,
            @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) List<Long> custody,
            @RequestParam(defaultValue = "ALL") OrderFilter.NoteMatch noteMatch,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "bookedDate") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        String property = SORT_FIELDS.get(sort);
        if (property == null) {
            throw ApiException.badRequest("Cannot sort by " + sort);
        }
        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500),
                Sort.by(dir, property).and(Sort.by("id")));
        OrderFilter filter = new OrderFilter(dateType, from, to, asset, portfolio, owner, status, custody, noteMatch);
        return PageDto.of(orders.search(filter, pageable));
    }

    @GetMapping("/{id}")
    public OrderDto get(@PathVariable Long id) {
        return orders.get(id);
    }

    @PatchMapping("/{id}")
    public OrderDto update(@PathVariable Long id, @Valid @RequestBody UpdateOrderRequest request) {
        return orders.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        orders.delete(id);
    }

    @PostMapping("/{id}/revert")
    public OrderDto revert(@PathVariable Long id) {
        return orders.revert(id);
    }

    @PostMapping("/{id}/status/advance")
    public OrderDto advance(@PathVariable Long id, @Valid @RequestBody AdvanceStatusRequest request) {
        return orders.advanceStatus(id, request.expectedStatus());
    }

    @GetMapping("/{id}/status-history")
    public List<StatusHistoryDto> statusHistory(@PathVariable Long id) {
        return orders.statusHistory(id);
    }

    @GetMapping("/{id}/contract-note")
    public ContractNoteDto contractNote(@PathVariable Long id) {
        return notes.forOrder(id);
    }

    @PostMapping("/bulk")
    public BulkResult bulk(@Valid @RequestBody BulkRequest request) {
        return orders.bulk(request);
    }
}
