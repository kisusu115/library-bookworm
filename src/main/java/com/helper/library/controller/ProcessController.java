package com.helper.library.controller;

import com.helper.library.service.ExcelService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ProcessController {

    private final ExcelService excelService;
    private final Map<String, JobStatus> jobStatuses = new ConcurrentHashMap<>();

    private static class JobStatus {
        final SseEmitter emitter = new SseEmitter(3600_000L);
        final AtomicReference<Workbook> result = new AtomicReference<>(null);
        final AtomicReference<String> error = new AtomicReference<>(null);
    }

    @PostMapping("/process/text")
    public ResponseEntity<String> processText(@RequestBody String textData,
                                              @RequestParam("ttbkey") String ttbkey) {
        String jobId = UUID.randomUUID().toString();
        jobStatuses.put(jobId, new JobStatus());

        List<String> isbns = excelService.parseIsbnFromText(textData);
        startProcessing(jobId, isbns, ttbkey);

        return ResponseEntity.ok(jobId);
    }

    @PostMapping("/process/excel")
    public ResponseEntity<String> processExcel(@RequestParam("file") MultipartFile file,
                                               @RequestParam("isbnColumn") String isbnColumn,
                                               @RequestParam("startRow") int startRow,
                                               @RequestParam("ttbkey") String ttbkey) {
        String jobId = UUID.randomUUID().toString();
        jobStatuses.put(jobId, new JobStatus());

        try {
            List<String> isbns = excelService.parseIsbnFromExcel(file, isbnColumn, startRow);
            startProcessing(jobId, isbns, ttbkey);
        } catch (IOException e) {
            log.error("Error parsing Excel file for job {}", jobId, e);
            JobStatus status = jobStatuses.get(jobId);
            status.error.set("엑셀 파일 처리 중 오류가 발생했습니다: " + e.getMessage());
            try {
                sendErrorEvent(status, jobId);
            } catch (IOException sendErrorE) {
                log.error("Failed to send error SSE event for job {}. Removing job.", jobId, sendErrorE);
                status.emitter.completeWithError(e);
                jobStatuses.remove(jobId);
            }
        }

        return ResponseEntity.ok(jobId);
    }

    @GetMapping("/status/{jobId}")
    public SseEmitter getStatus(@PathVariable String jobId) {
        JobStatus status = jobStatuses.get(jobId);
        if (status == null) {
            log.warn("No job found for ID: {}", jobId);
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error").data("Invalid Job ID"));
                emitter.complete();
            } catch (IOException e) {
                log.error("Error sending error for invalid job ID", e);
            }
            return emitter;
        }
        return status.emitter;
    }

    @GetMapping("/download/{jobId}")
    public void downloadExcel(@PathVariable String jobId, HttpServletResponse response) throws IOException {
        JobStatus status = jobStatuses.get(jobId);
        if (status == null || status.result.get() == null) {
            log.error("Job not found or not complete for download: {}", jobId);
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "결과 파일을 찾을 수 없거나 작업이 완료되지 않았습니다.");
            return;
        }

        Workbook workbook = status.result.get();
        String fileName = "도서_정보_결과_" + jobId.substring(0, 8) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");

        workbook.write(response.getOutputStream());
        workbook.close();

        jobStatuses.remove(jobId);
        log.info("Job {} downloaded and removed.", jobId);
    }

    private void startProcessing(String jobId, List<String> isbns, String ttbkey) {
        log.info("Starting processing for job ID: {} with {} ISBNs", jobId, isbns.size());
        excelService.createExcelFile(isbns, ttbkey, progress -> {
            try {
                sendProgressUpdateEvent(jobStatuses.get(jobId), progress);
            } catch (IOException e) {
                log.error("Failed to send progress update for job {}", jobId, e);
            }
        }).whenComplete((workbook, throwable) -> handleProcessingCompletion(jobId, workbook, throwable));
    }

    private void handleProcessingCompletion(String jobId, Workbook workbook, Throwable throwable) {
        JobStatus status = jobStatuses.get(jobId);
        if (status == null) {
            return;
        }

        if (throwable != null) {
            log.error("Error processing job {}", jobId, throwable);
            status.error.set("작업 처리 중 오류가 발생했습니다: " + throwable.getMessage());
            try {
                sendErrorEvent(status, jobId);
            } catch (IOException e) {
                log.error("Failed to send error SSE event for job {}. Removing job.", jobId, e);
                status.emitter.completeWithError(e);
                jobStatuses.remove(jobId);
            }
        } else {
            status.result.set(workbook);
            log.info("Successfully completed job {}", jobId);
            try {
                sendCompleteEvent(status, jobId);
            } catch (IOException e) {
                log.error("Failed to send complete SSE event for job {}. Removing job.", jobId, e);
                status.emitter.completeWithError(e);
                jobStatuses.remove(jobId);
            }
        }
    }

    private void sendErrorEvent(JobStatus status, String jobId) throws IOException {
        String errorMessage = status.error.get() != null ? status.error.get() : "Unknown error";
        SseEmitter.SseEventBuilder event = SseEmitter.event().name("error").data(errorMessage);
        status.emitter.send(event);
        status.emitter.complete();
        log.info("Sent error event for job {} and completed emitter.", jobId);
    }

    private void sendCompleteEvent(JobStatus status, String jobId) throws IOException {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name("complete").data("100.00");
        status.emitter.send(event);
        status.emitter.complete();
        log.info("Sent complete event for job {} and completed emitter.", jobId);
    }

    private void sendProgressUpdateEvent(JobStatus status, double progress) throws IOException {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name("progress").data(String.format("%.2f", progress));
        status.emitter.send(event);
    }
}
