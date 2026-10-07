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

    /** The "Unmatched Contract Notes" list (unmatched and unreadable notes). */
    @GetMapping
    public List<ContractNoteDto> listOpen() {
        return notes.listOpen();
    }

    @GetMapping("/count")
    public Map<String, Long> countOpen() {
        return Map.of("unmatched", notes.countOpen());
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

    @PatchMapping("/{id}")
    public ContractNoteDto update(@PathVariable Long id, @RequestBody UpdateNoteRequest request) {
        return notes.update(id, request);
    }

    @PostMapping("/{id}/rematch")
    public UploadResult rematch(@PathVariable Long id) {
        return notes.rematch(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        notes.delete(id);
    }
}
