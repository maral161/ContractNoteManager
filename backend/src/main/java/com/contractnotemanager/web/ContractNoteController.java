package com.contractnotemanager.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;

import com.contractnotemanager.contractnote.ContractNoteService;
import com.contractnotemanager.domain.ContractNoteStatus;
import com.contractnotemanager.web.dto.ContractNoteDto;
import com.contractnotemanager.web.dto.UpdateNoteRequest;
import com.contractnotemanager.web.dto.UploadResult;
import com.contractnotemanager.web.error.ApiException;

@RestController
@RequestMapping("/api/v1/contract-notes")
public class ContractNoteController {

    private final ContractNoteService notes;

    public ContractNoteController(ContractNoteService notes) {
        this.notes = notes;
    }

    /** PDFs dropped in the bulk-action area, matched against the ticked orders. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<UploadResult> upload(@RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "orderIds", required = false) List<Long> orderIds) {
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("No files uploaded");
        }
        return notes.upload(files, orderIds);
    }

    /** All contract notes, optionally only one status. */
    @GetMapping
    public List<ContractNoteDto> list(@RequestParam(required = false) ContractNoteStatus status) {
        return notes.list(status);
    }

    /** Number of notes per status, plus "open" (not matched yet) for the tab badge. */
    @GetMapping("/count")
    public Map<String, Long> counts() {
        return notes.counts();
    }

    /** Evaluates all partially matched and unmatched notes again against the current orders. */
    @PostMapping("/reevaluate")
    public ContractNoteService.ReevaluationSummary reevaluateAll() {
        return notes.reevaluateOpen();
    }

    @GetMapping("/{id}")
    public ContractNoteDto get(@PathVariable Long id) {
        return notes.get(id);
    }

    /** The optional file name in the path only makes the browser's PDF viewer show the right title. */
    @GetMapping({"/{id}/file", "/{id}/file/{name}"})
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        ContractNoteService.NamedFile file = notes.file(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.name()).build().toString())
                .body(file.content());
    }

    /** Corrects the values read from the PDF; the note is evaluated again right away. */
    @PatchMapping("/{id}")
    public UploadResult update(@PathVariable Long id, @RequestBody UpdateNoteRequest request) {
        return notes.update(id, request);
    }

    @PostMapping("/{id}/reevaluate")
    public UploadResult reevaluate(@PathVariable Long id) {
        return notes.reevaluate(id);
    }

    /** Lets Claude read the stored PDF again. */
    @PostMapping("/{id}/reread")
    public UploadResult reread(@PathVariable Long id) {
        return notes.reread(id);
    }

    /** Partially matched note: take its price, quantity, commission and broker into the linked order. */
    @PostMapping("/{id}/apply-to-order")
    public UploadResult applyToOrder(@PathVariable Long id) {
        return notes.applyToOrder(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        notes.delete(id);
    }
}
