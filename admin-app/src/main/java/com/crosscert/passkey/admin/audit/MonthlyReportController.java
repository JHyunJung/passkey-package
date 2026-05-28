package com.crosscert.passkey.admin.audit;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * F4 Task 6 — Monthly Audit Chain Report PDF download endpoint.
 *
 * <p>{@code GET /admin/api/audit/chain/monthly-report?from=YYYY-MM-DD&to=YYYY-MM-DD}
 * returns {@code application/pdf} as an attachment. Records a
 * {@code MONTHLY_REPORT_GENERATED} audit event with the from/to range and the
 * generated PDF byte size.
 */
@RestController
@RequestMapping("/admin/api/audit/chain/monthly-report")
public class MonthlyReportController {

    private final MonthlyReportService service;
    private final AuditLogService auditService;

    public MonthlyReportController(MonthlyReportService service, AuditLogService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    public ResponseEntity<byte[]> download(
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            Authentication auth) {
        // LocalDate.parse rejects anything that isn't strict YYYY-MM-DD, so
        // the values are safe to interpolate into the Content-Disposition
        // filename below (no path traversal, no header injection).
        LocalDate fromD = LocalDate.parse(from);
        LocalDate toD = LocalDate.parse(to);

        byte[] pdf = service.generate(fromD, toD);

        auditService.append(new AuditAppendRequest(
                null,
                auth.getName(),
                "MONTHLY_REPORT_GENERATED",
                "audit_chain",
                "monthly",
                null,
                Map.of(
                        "from", fromD.toString(),
                        "to", toD.toString(),
                        "bytes", pdf.length)));

        String filename = "audit-chain-monthly-" + fromD + "-to-" + toD + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }
}
