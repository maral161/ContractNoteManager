package com.contractnotemanager.service;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Settlement date helper. Skips weekends; bank holidays are not considered. */
public final class BusinessDays {

    private BusinessDays() {
    }

    public static LocalDate add(LocalDate date, int businessDays) {
        LocalDate result = date;
        int added = 0;
        while (added < businessDays) {
            result = result.plusDays(1);
            if (result.getDayOfWeek() != DayOfWeek.SATURDAY && result.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return result;
    }
}
