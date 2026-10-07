package com.contractnotemanager.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.contractnotemanager.domain.ContractNote;
import com.contractnotemanager.domain.ContractNoteStatus;
import com.contractnotemanager.domain.Order;
import com.contractnotemanager.domain.OrderAllocation;
import com.contractnotemanager.web.dto.OrderFilter;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/** Translates the toolbar filters into a JPA query. */
final class OrderSpecifications {

    private OrderSpecifications() {
    }

    static Specification<Order> of(OrderFilter f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            String dateField = switch (f.dateType() == null ? OrderFilter.DateType.BOOKED : f.dateType()) {
                case BOOKED -> "bookedDate";
                case TRADED -> "tradedDate";
                case SETTLED -> "settlementDate";
            };
            if (f.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.<LocalDate>get(dateField), f.from()));
            }
            if (f.to() != null) {
                p.add(cb.lessThanOrEqualTo(root.<LocalDate>get(dateField), f.to()));
            }
            if (notBlank(f.asset())) {
                String like = "%" + f.asset().trim().toLowerCase() + "%";
                p.add(cb.or(
                        cb.like(cb.lower(root.get("asset").get("name")), like),
                        cb.like(cb.lower(root.get("asset").get("isin")), like)));
            }
            if (notBlank(f.portfolio())) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<OrderAllocation> a = sub.from(OrderAllocation.class);
                sub.select(a.get("id")).where(
                        cb.equal(a.get("order"), root),
                        cb.like(cb.lower(a.get("portfolio").get("name")),
                                "%" + f.portfolio().trim().toLowerCase() + "%"));
                p.add(cb.exists(sub));
            }
            if (f.owner() != null && !f.owner().isEmpty()) {
                p.add(root.get("owner").get("id").in(f.owner()));
            }
            if (f.status() != null && !f.status().isEmpty()) {
                p.add(root.get("status").in(f.status()));
            }
            if (f.custody() != null && !f.custody().isEmpty()) {
                p.add(root.get("custody").get("id").in(f.custody()));
            }
            if (f.noteMatch() != null && f.noteMatch() != OrderFilter.NoteMatch.ALL) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<ContractNote> n = sub.from(ContractNote.class);
                switch (f.noteMatch()) {
                    case MATCHED -> {
                        sub.select(n.get("id")).where(cb.equal(n.get("order"), root),
                                cb.equal(n.get("status"), ContractNoteStatus.MATCHED));
                        p.add(cb.exists(sub));
                    }
                    case PARTIAL -> {
                        sub.select(n.get("id")).where(cb.equal(n.get("order"), root),
                                cb.equal(n.get("status"), ContractNoteStatus.PARTIALLY_MATCHED));
                        p.add(cb.exists(sub));
                    }
                    default -> {
                        sub.select(n.get("id")).where(cb.equal(n.get("order"), root));
                        p.add(cb.not(cb.exists(sub)));
                    }
                }
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
