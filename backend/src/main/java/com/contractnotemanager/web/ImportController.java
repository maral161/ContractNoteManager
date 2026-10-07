package com.contractnotemanager.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.contractnotemanager.domain.ImportRun;
import com.contractnotemanager.importer.ImportDateType;
import com.contractnotemanager.importer.ImportService;
import com.contractnotemanager.repository.ImportRunRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

    private final ImportService imports;
    private final ImportRunRepository runs;

    public ImportController(ImportService imports, ImportRunRepository runs) {
        this.imports = imports;
        this.runs = runs;
    }

    public record ImportRequest(@NotNull LocalDate fromDate, @NotNull LocalDate toDate, ImportDateType dateType) {
    }

    /** Runs an import now; answers when it is finished, with the counts. */
    @PostMapping
    public ImportRun run(@Valid @RequestBody ImportRequest request) {
        return imports.runImport(request.fromDate(), request.toDate(), request.dateType());
    }

    @GetMapping
    public List<ImportRun> history() {
        return runs.findTop50ByOrderByStartedAtDesc();
    }
}
