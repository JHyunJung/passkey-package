package com.crosscert.passkey.admin.web.support

import org.springframework.stereotype.Component
import org.thymeleaf.context.ITemplateContext
import org.thymeleaf.dialect.AbstractProcessorDialect
import org.thymeleaf.engine.AttributeName
import org.thymeleaf.model.IProcessableElementTag
import org.thymeleaf.processor.IProcessor
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor
import org.thymeleaf.processor.element.IElementTagStructureHandler
import org.thymeleaf.standard.expression.StandardExpressions
import org.thymeleaf.templatemode.TemplateMode
import org.unbescape.html.HtmlEscape
import java.util.regex.Pattern

private val ATTR_NAME: Pattern = Pattern.compile("[A-Za-z_@:][A-Za-z0-9_@:.\\-]*")

/** setAttribute 는 값을 이스케이프하지 않으므로 직접 한다(속성 값 탈출 방지). */
private fun esc(value: String): String = HtmlEscape.escapeHtml4Xml(value)

/**
 * 공통 UI 조각이 호출자에게서 임의 속성을 받을 때 쓰는 `ui:attrs="${attrs}"`.
 * 맵의 각 항목을 요소 속성으로 쓴다. 값이 `null`·`false` 면 속성을 쓰지 않고, `true` 면
 * `disabled` 같은 값 없는 속성으로 쓴다. 이름이 `on` 으로 시작하는 인라인 이벤트 속성
 * (`onclick` 등)은 조용히 버린다.
 *
 * **보안 규약:** JS 로 평가되는 속성군(`x-on:*`, `@*`, `hx-on*`, `x-bind:*`, `:*`,
 * `x-data`, `hx-vals`)의 값은 사용자 입력이 아닌 템플릿 상수여야 한다. 값은 HTML 이스케이프만 되고
 * JS 로는 이스케이프되지 않는다. 데이터는 `data-*` 속성이나 `@fmt.json` 으로 넘긴다.
 *
 * `class` 는 기존 값에 덧붙인다. 빈 문자열 값은 `name=""` 로 나간다. 이름은 영숫자·_@:.- 만 허용한다.
 */
@Component
class UiDialect : AbstractProcessorDialect("Admin UI", PREFIX, 1000) {

    override fun getProcessors(dialectPrefix: String): Set<IProcessor> =
        java.util.Set.of(AttrsProcessor(dialectPrefix), XdataProcessor(dialectPrefix))

    internal class AttrsProcessor(dialectPrefix: String) :
        AbstractAttributeTagProcessor(TemplateMode.HTML, dialectPrefix, null, false, "attrs", true, 1000, true) {

        override fun doProcess(
            context: ITemplateContext,
            tag: IProcessableElementTag,
            attributeName: AttributeName,
            attributeValue: String?,
            structureHandler: IElementTagStructureHandler,
        ) {
            val parser = StandardExpressions.getExpressionParser(context.configuration)
            val expr = parser.parseExpression(context, attributeValue)
            val v: Any? = expr.execute(context)
            if (v !is Map<*, *>) return
            for ((key, value) in v) {
                val name = key.toString()
                if (!ATTR_NAME.matcher(name).matches() || name.regionMatches(0, "on", 0, 2, ignoreCase = true)) continue // on* 인라인 이벤트 차단
                if (value == null || false == value) continue // null·false 는 속성 생략, true 는 값 없는 속성
                val str = if (true == value) "" else value.toString()
                if (name == "class" && tag.hasAttribute("class")) {
                    structureHandler.setAttribute("class", "${tag.getAttributeValue("class")} ${esc(str)}")
                } else {
                    structureHandler.setAttribute(name, esc(str))
                }
            }
        }
    }

    /**
     * `ui:xdata="${@fmt.xdata('chipInput', 'values', values)}"` → `x-data="chipInput({...})"`.
     * Thymeleaf 의 일반 `th:*` 속성은 `@빈` 호출 표현식을 막기 때문에 Alpine 설정 JSON 은 이 속성으로 쓴다.
     */
    internal class XdataProcessor(dialectPrefix: String) :
        AbstractAttributeTagProcessor(TemplateMode.HTML, dialectPrefix, null, false, "xdata", true, 1000, true) {

        override fun doProcess(
            context: ITemplateContext,
            tag: IProcessableElementTag,
            attributeName: AttributeName,
            attributeValue: String?,
            structureHandler: IElementTagStructureHandler,
        ) {
            val parser = StandardExpressions.getExpressionParser(context.configuration)
            val v: Any? = parser.parseExpression(context, attributeValue).execute(context)
            if (v != null) structureHandler.setAttribute("x-data", esc(v.toString()))
        }
    }

    companion object {
        const val PREFIX = "ui"
    }
}
