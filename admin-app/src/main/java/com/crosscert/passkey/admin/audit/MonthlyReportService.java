package com.crosscert.passkey.admin.audit;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;

/**
 * F4 Task 6 — Generates the Audit Chain Monthly Report as a PDF byte stream.
 *
 * <p>Reuses {@link AuditChainMonitorController#overview(int)} to compose the
 * tenant-by-tenant chain status snapshot (24h verification window — the same
 * cadence the dashboard uses), then renders an HTML report and converts it
 * to PDF via openhtmltopdf-pdfbox 1.0.10.
 *
 * <p>The report's "Period" header echoes the caller-supplied from/to dates
 * (filename + display only) — chain verification itself is always against
 * the current state, since the hash chain is append-only and verification
 * walks the full history.
 */
@Service
public class MonthlyReportService {

    private static final int OVERVIEW_WINDOW_HOURS = 24;

    private final AuditChainMonitorController monitor;

    public MonthlyReportService(AuditChainMonitorController monitor) {
        this.monitor = monitor;
    }

    public byte[] generate(LocalDate from, LocalDate to) {
        AuditChainOverview overview = monitor.overview(OVERVIEW_WINDOW_HOURS);
        String html = buildHtml(from, to, overview);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF generation failed", e);
        }
    }

    private String buildHtml(LocalDate from, LocalDate to, AuditChainOverview overview) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset='utf-8'>");
        sb.append("<style>body{font-family:sans-serif} table{border-collapse:collapse;width:100%}");
        sb.append("th,td{border:1px solid #ccc;padding:6px;font-size:11px;text-align:left}");
        sb.append(".intact{color:#080} .tampered{color:#c00;font-weight:bold}</style>");
        sb.append("</head><body>");
        sb.append("<h1>Audit Chain Monthly Report</h1>");
        sb.append("<p>Period: ").append(esc(from.toString()))
                .append(" — ").append(esc(to.toString())).append("</p>");
        sb.append("<p>Generated at: ").append(esc(java.time.Instant.now().toString())).append("</p>");

        AuditChainOverview.Totals t = overview.totals();
        sb.append("<h2>Summary</h2>");
        sb.append("<ul>");
        sb.append("<li>Tenants intact: ").append(t.tenantsIntact())
                .append(" / ").append(t.tenantsTotal()).append("</li>");
        sb.append("<li>Tenants tampered: ").append(t.tenantsTampered()).append("</li>");
        sb.append("<li>Verified rows: ").append(t.verifiedRows()).append("</li>");
        sb.append("<li>Verification time: ").append(t.verificationMs()).append(" ms</li>");
        sb.append("<li>Verified at: ").append(esc(String.valueOf(overview.verifiedAt()))).append("</li>");
        sb.append("</ul>");

        sb.append("<h2>Tenants</h2>");
        sb.append("<table><tr><th>Tenant</th><th>Tenant ID</th><th>Status</th>")
                .append("<th>Verified Rows</th><th>Tampered Entry</th></tr>");
        for (AuditChainTenantOverview c : overview.tenants()) {
            String status = c.intact() ? "INTACT" : "TAMPERED";
            String cls = c.intact() ? "intact" : "tampered";
            sb.append("<tr>")
                    .append("<td>").append(esc(c.tenantName())).append("</td>")
                    .append("<td>").append(esc(c.tenantId() == null ? "[platform]" : c.tenantId().toString())).append("</td>")
                    .append("<td class='").append(cls).append("'>").append(esc(status)).append("</td>")
                    .append("<td>").append(c.verifiedRows()).append("</td>")
                    .append("<td>").append(esc(c.tamperedEntryId() == null ? "—" : c.tamperedEntryId().toString())).append("</td>")
                    .append("</tr>");
        }
        sb.append("</table>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
