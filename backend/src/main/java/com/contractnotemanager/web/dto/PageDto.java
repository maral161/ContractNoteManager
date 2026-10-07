package com.contractnotemanager.web.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/** One page of results with the total, as the table's pagination needs it. */
public record PageDto<T>(List<T> content, long totalElements, int page, int size) {

    public static <T> PageDto<T> of(Page<T> page) {
        return new PageDto<>(page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize());
    }
}
