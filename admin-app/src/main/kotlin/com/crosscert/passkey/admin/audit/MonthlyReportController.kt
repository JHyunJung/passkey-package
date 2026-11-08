package com.crosscert.passkey.admin.audit

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * F4 Task 6 — Monthly Audit Chain Report PDF download endpoint.
 *
 * `GET /admin/api/audit/chain/monthly-report?from=YYYY-MM-DD&to=YYYY-MM-DD`
 * returns `application/pdf` as an attachment. Records a
 * `MONTHLY_REPORT_GENERATED` audit event with the from/to range and the
 * generated PDF byte size.
 */
@RestController
@RequestMapping("/admin/api/audit/chain/monthly-report")
class MonthlyReportController(
    private val service: MonthlyReportService,
    private val auditService: AuditLogService,
) {

    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    @GetMapping
    fun download(
        @RequestParam("from") from: String,
        @RequestParam("to") to: String,
        auth: Authentication,
    ): ResponseEntity<ByteArray> {
        // LocalDate.parse rejects anything that isn't strict YYYY-MM-DD, so
        // the values are safe to interpolate into the Content-Disposition
        // filename below (no path traversal, no header injection).
        val fromD = LocalDate.parse(from)
        val toD = LocalDate.parse(to)

        val pdf = service.generate(fromD, toD)

        auditService.append(
            AuditAppendRequest(
                null,
                auth.name,
                "MONTHLY_REPORT_GENERATED",
                "audit_chain",
                "monthly",
                null,
                java.util.Map.of<String, Any>(
                    "from", fromD.toString(),
                    "to", toD.toString(),
                    "bytes", pdf.size,
                ),
            ),
        )

        val filename = "audit-chain-monthly-$fromD-to-$toD.pdf"
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "attachment; filename=\"$filename\"")
            .body(pdf)
    }
}
