package com.crosscert.passkey.admin.web.support;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.IProcessor;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.standard.expression.IStandardExpression;
import org.thymeleaf.standard.expression.IStandardExpressionParser;
import org.thymeleaf.standard.expression.StandardExpressions;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.dialect.AbstractProcessorDialect;

import org.unbescape.html.HtmlEscape;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * 공통 UI 조각이 호출자에게서 임의 속성을 받을 때 쓰는 {@code ui:attrs="${attrs}"}.
 * 맵의 각 항목을 요소 속성으로 쓴다. {@code class} 는 기존 값에 덧붙이고, 값이 null·빈 문자열이면
 * {@code disabled} 같은 값 없는 속성으로 쓴다. 값은 HTML 이스케이프한다(이름은 영숫자·_@:.- 만).
 */
@Component
public class UiDialect extends AbstractProcessorDialect {

    public static final String PREFIX = "ui";

    private static final Pattern ATTR_NAME = Pattern.compile("[A-Za-z_@:][A-Za-z0-9_@:.\\-]*");

    /** setAttribute 는 값을 이스케이프하지 않으므로 직접 한다(속성 값 탈출 방지). */
    private static String esc(String value) {
        return HtmlEscape.escapeHtml4Xml(value);
    }

    public UiDialect() {
        super("Admin UI", PREFIX, 1000);
    }

    @Override
    public Set<IProcessor> getProcessors(String dialectPrefix) {
        return Set.of(new AttrsProcessor(dialectPrefix), new XdataProcessor(dialectPrefix));
    }

    static final class AttrsProcessor extends AbstractAttributeTagProcessor {

        AttrsProcessor(String dialectPrefix) {
            super(TemplateMode.HTML, dialectPrefix, null, false, "attrs", true, 1000, true);
        }

        @Override
        protected void doProcess(ITemplateContext context, IProcessableElementTag tag, AttributeName attributeName,
                                 String attributeValue, IElementTagStructureHandler structureHandler) {
            IStandardExpressionParser parser = StandardExpressions.getExpressionParser(context.getConfiguration());
            IStandardExpression expr = parser.parseExpression(context, attributeValue);
            Object v = expr.execute(context);
            if (!(v instanceof Map<?, ?> map)) return;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!ATTR_NAME.matcher(name).matches()) continue;
                String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
                if (name.equals("class") && tag.hasAttribute("class")) {
                    structureHandler.setAttribute("class", tag.getAttributeValue("class") + " " + esc(value));
                } else {
                    structureHandler.setAttribute(name, esc(value));
                }
            }
        }
    }

    /**
     * {@code ui:xdata="${@fmt.xdata('chipInput', 'values', values)}"} → {@code x-data="chipInput({...})"}.
     * Thymeleaf 의 일반 {@code th:*} 속성은 {@code @빈} 호출 표현식을 막기 때문에 Alpine 설정 JSON 은 이 속성으로 쓴다.
     */
    static final class XdataProcessor extends AbstractAttributeTagProcessor {

        XdataProcessor(String dialectPrefix) {
            super(TemplateMode.HTML, dialectPrefix, null, false, "xdata", true, 1000, true);
        }

        @Override
        protected void doProcess(ITemplateContext context, IProcessableElementTag tag, AttributeName attributeName,
                                 String attributeValue, IElementTagStructureHandler structureHandler) {
            IStandardExpressionParser parser = StandardExpressions.getExpressionParser(context.getConfiguration());
            Object v = parser.parseExpression(context, attributeValue).execute(context);
            if (v != null) structureHandler.setAttribute("x-data", esc(String.valueOf(v)));
        }
    }
}
