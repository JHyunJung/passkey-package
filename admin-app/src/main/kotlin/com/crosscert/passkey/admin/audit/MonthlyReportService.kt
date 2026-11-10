package com.crosscert.passkey.admin.audit

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

private const val OVERVIEW_WINDOW_HOURS = 24

/** 번들 한글 글꼴(나눔고딕, SIL OFL 1.1 — fonts/OFL.txt). PDF base-14 글꼴엔 한글 글리프가 없다. */
private const val REPORT_FONT_FAMILY = "NanumGothic"
private val REPORT_FONTS = listOf(
    "fonts/NanumGothic-Regular.ttf" to 400,
    "fonts/NanumGothic-Bold.ttf" to 700,
)

/**
 * F4 Task 6 — Generates the Audit Chain Monthly Report as a PDF byte stream.
 *
 * Reuses [AuditChainMonitorController.overview] to compose the
 * tenant-by-tenant chain status snapshot (24h verification window — the same
 * cadence the dashboard uses), then renders an HTML report and converts it
 * to PDF via openhtmltopdf-pdfbox 1.0.10.
 *
 * The report's "Period" header echoes the caller-supplied from/to dates
 * (filename + display only) — chain verification itself is always against
 * the current state, since the hash chain is append-only and verification
 * walks the full history.
 */
@Service
class MonthlyReportService(private val monitor: AuditChainMonitorController) {

    fun generate(from: LocalDate, to: LocalDate): ByteArray {
        val overview = monitor.overview(OVERVIEW_WINDOW_HOURS)
        val html = buildHtml(from, to, overview)

        try {
            ByteArrayOutputStream().use { out ->
                val builder = PdfRendererBuilder()
                for ((path, weight) in REPORT_FONTS) {
                    // subset=true: 실제로 쓰인 글리프만 PDF 에 포함(파일 크기 유지).
                    builder.useFont({ fontStream(path) }, REPORT_FONT_FAMILY, weight, FontStyle.NORMAL, true)
                }
                builder.withHtmlContent(html, null)
                builder.toStream(out)
                builder.run()
                return out.toByteArray()
            }
        } catch (e: IOException) {
            throw IllegalStateException("PDF generation failed", e)
        }
    }

    private fun fontStream(path: String) =
        MonthlyReportService::class.java.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("PDF font resource missing: $path")

    private fun buildHtml(from: LocalDate, to: LocalDate, overview: AuditChainOverview): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset='utf-8'/>")
        sb.append("<style>body{font-family:'NanumGothic',sans-serif} table{border-collapse:collapse;width:100%}")
        sb.append("th,td{border:1px solid #ccc;padding:6px;font-size:11px;text-align:left}")
        sb.append(".intact{color:#080} .tampered{color:#c00;font-weight:bold}</style>")
        sb.append("</head><body>")
        sb.append("<h1>Audit Chain Monthly Report</h1>")
        sb.append("<p>Period: ").append(esc(from.toString()))
            .append(" — ").append(esc(to.toString())).append("</p>")
        sb.append("<p>Generated at: ").append(esc(Instant.now().toString())).append("</p>")

        // 원 Java 와 동일: totals/tenants 가 null 이면 NPE(참조형 컴포넌트는 record 처럼 nullable).
        val t = overview.totals!!
        sb.append("<h2>Summary</h2>")
        sb.append("<ul>")
        sb.append("<li>Tenants intact: ").append(t.tenantsIntact)
            .append(" / ").append(t.tenantsTotal).append("</li>")
        sb.append("<li>Tenants tampered: ").append(t.tenantsTampered).append("</li>")
        sb.append("<li>Verified rows: ").append(t.verifiedRows).append("</li>")
        sb.append("<li>Verification time: ").append(t.verificationMs).append(" ms</li>")
        sb.append("<li>Verified at: ").append(esc(overview.verifiedAt.toString())).append("</li>")
        sb.append("</ul>")

        sb.append("<h2>Tenants</h2>")
        sb.append("<table><tr><th>Tenant</th><th>Tenant ID</th><th>Status</th>")
            .append("<th>Verified Rows</th><th>Tampered Entry</th></tr>")
        for (c in overview.tenants!!) {
            val status = if (c.intact) "INTACT" else "TAMPERED"
            val cls = if (c.intact) "intact" else "tampered"
            sb.append("<tr>")
                .append("<td>").append(esc(c.tenantName)).append("</td>")
                .append("<td>").append(esc(if (c.tenantId == null) "[platform]" else c.tenantId.toString())).append("</td>")
                .append("<td class='").append(cls).append("'>").append(esc(status)).append("</td>")
                .append("<td>").append(c.verifiedRows).append("</td>")
                .append("<td>").append(esc(if (c.tamperedEntryId == null) "—" else c.tamperedEntryId.toString())).append("</td>")
                .append("</tr>")
        }
        sb.append("</table>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun esc(s: String?): String {
        if (s == null) return ""
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
