package org.jetbrains.dokka

import java.util.LinkedHashMap

public data class FormatLink(val text: String, val location: Location)

public abstract class StructuredFormatService(val locationService: LocationService,
                                              val languageService: LanguageService) : FormatService {

    abstract public fun appendBlockCode(to: StringBuilder, line: String)
    abstract public fun appendBlockCode(to: StringBuilder, lines: Iterable<String>)
    abstract public fun appendHeader(to: StringBuilder, text: String, level: Int = 1)
    abstract public fun appendText(to: StringBuilder, text: String)
    abstract public fun appendLine(to: StringBuilder, text: String)
    public abstract fun appendLine(to: StringBuilder)

    public abstract fun appendTable(to: StringBuilder, body: () -> Unit)
    public abstract fun appendTableHeader(to: StringBuilder, body: () -> Unit)
    public abstract fun appendTableBody(to: StringBuilder, body: () -> Unit)
    public abstract fun appendTableRow(to: StringBuilder, body: () -> Unit)
    public abstract fun appendTableCell(to: StringBuilder, body: () -> Unit)

    public abstract fun formatText(text: String): String
    public abstract fun formatSymbol(text: String): String
    public abstract fun formatKeyword(text: String): String
    public abstract fun formatIdentifier(text: String): String
    public abstract fun formatLink(text: String, location: Location): String
    public abstract fun formatLink(text: String, href: String): String
    public open fun formatLink(link: FormatLink): String = formatLink(formatText(link.text), link.location)
    public abstract fun formatStrong(text: String): String
    public abstract fun formatEmphasis(text: String): String
    public abstract fun formatCode(code: String): String
    public abstract fun formatList(text: String): String
    public abstract fun formatListItem(text: String): String
    public abstract fun formatBreadcrumbs(items: Iterable<FormatLink>): String

    open fun formatText(location: Location, nodes: Iterable<ContentNode>): String {
        return nodes.map { formatText(location, it) }.join("")
    }

    open fun formatText(location: Location, content: ContentNode): String {
        return StringBuilder {
            when (content) {
                is ContentText -> append(content.text)
                is ContentSymbol -> append(formatSymbol(content.text))
                is ContentKeyword -> append(formatKeyword(content.text))
                is ContentIdentifier -> append(formatIdentifier(content.text))
                is ContentStrong -> append(formatStrong(formatText(location, content.children)))
                is ContentCode -> append(formatCode(formatText(location, content.children)))
                is ContentEmphasis -> append(formatEmphasis(formatText(location, content.children)))
                is ContentList -> append(formatList(formatText(location, content.children)))
                is ContentListItem -> append(formatListItem(formatText(location, content.children)))

                is ContentNodeLink -> {
                    val linkTo = locationService.relativeLocation(location, content.node, extension)
                    val linkText = formatText(location, content.children)
                    append(formatLink(linkText, linkTo))
                }
                is ContentExternalLink -> {
                    val linkText = formatText(location, content.children)
                    append(formatLink(linkText, content.href))
                }
                is ContentParagraph -> {
                    appendText(this, formatText(location, content.children))
                }
                is ContentBlockCode -> {
                    appendBlockCode(this, formatText(location, content.children))
                }
                else -> append(formatText(location, content.children))
            }
        }.toString()
    }

    open public fun link(from: DocumentationNode, to: DocumentationNode): FormatLink = link(from, to, extension)

    open public fun link(from: DocumentationNode, to: DocumentationNode, extension: String): FormatLink {
        return FormatLink(to.name, locationService.relativeLocation(from, to, extension))
    }

    fun appendDescription(location: Location, to: StringBuilder, nodes: Iterable<DocumentationNode>) {
        val described = nodes.filter { !it.content.isEmpty }
        if (described.any()) {
            val single = described.size == 1
            appendHeader(to, "Description", 3)
            for (node in described) {
                if (!single) {
                    appendBlockCode(to, formatText(location, languageService.render(node)))
                }
                appendLine(to, formatText(location, node.content.description))
                appendLine(to)
                for ((label, section) in node.content.sections) {
                    if (label.startsWith("$"))
                        continue
                    if (node.members.any { it.name == label })
                        continue
                    appendLine(to, formatStrong(formatText(label)))
                    appendLine(to, formatText(location, section))
                }
            }
        }
    }

    fun appendSummary(location: Location, to: StringBuilder, nodes: Iterable<DocumentationNode>) {
        val breakdownBySummary = nodes.groupByTo(LinkedHashMap()) { node ->
            formatText(location, node.summary)
        }

        for ((summary, items) in breakdownBySummary) {
            items.forEach {
                appendBlockCode(to, formatText(location, languageService.render(it)))
            }
            appendLine(to, summary)
            appendLine(to)
        }
    }

    fun appendLocation(location: Location, to: StringBuilder, nodes: Iterable<DocumentationNode>) {
        val breakdownByName = nodes.groupBy { node -> node.name }
        for ((name, items) in breakdownByName) {
            appendHeader(to, formatText(name))
            appendSummary(location, to, items)
            appendDescription(location, to, items)
        }
    }

    private fun StructuredFormatService.appendSection(location: Location, caption: String, nodes: List<DocumentationNode>, node: DocumentationNode, to: StringBuilder) {
        if (nodes.any()) {
            appendHeader(to, caption, 3)

            val children = nodes.sortBy { it.name }
            val membersMap = children.groupBy { link(node, it) }

            appendTable(to) {
                appendTableBody(to) {
                    for ((memberLocation, members) in membersMap) {
                        appendTableRow(to) {
                            appendTableCell(to) {
                                appendText(to, formatLink(memberLocation))
                            }
                            appendTableCell(to) {
                                val breakdownBySummary = members.groupBy { formatText(location, it.summary) }
                                for ((summary, items) in breakdownBySummary) {
                                    for (signature in items) {
                                        appendBlockCode(to, formatText(location, languageService.render(signature)))
                                    }

                                    if (!summary.isEmpty()) {
                                        appendText(to, summary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun appendNodes(location: Location, to: StringBuilder, nodes: Iterable<DocumentationNode>) {
        val breakdownByLocation = nodes.groupBy { node ->
            formatBreadcrumbs(node.path.map { link(node, it) })
        }

        for ((breadcrumbs, items) in breakdownByLocation) {
            appendLine(to, breadcrumbs)
            appendLine(to)
            appendLocation(location, to, items)
        }

        for (node in nodes) {
            appendSection(location, "Packages", node.members(DocumentationNode.Kind.Package), node, to)
            appendSection(location, "Types", node.members.filter {
                it.kind in setOf(
                        DocumentationNode.Kind.Class,
                        DocumentationNode.Kind.Interface,
                        DocumentationNode.Kind.Enum,
                        DocumentationNode.Kind.Object)
            }, node, to)
            appendSection(location, "Constructors", node.members(DocumentationNode.Kind.Constructor), node, to)
            appendSection(location, "Properties", node.members(DocumentationNode.Kind.Property), node, to)
            appendSection(location, "Functions", node.members(DocumentationNode.Kind.Function), node, to)
            appendSection(location, "Accessors", node.members(DocumentationNode.Kind.PropertyAccessor), node, to)
            appendSection(location, "Other members", node.members.filter {
                it.kind !in setOf(
                        DocumentationNode.Kind.Class,
                        DocumentationNode.Kind.Interface,
                        DocumentationNode.Kind.Object,
                        DocumentationNode.Kind.Constructor,
                        DocumentationNode.Kind.Property,
                        DocumentationNode.Kind.Package,
                        DocumentationNode.Kind.Function,
                        DocumentationNode.Kind.PropertyAccessor
                        )
            }, node, to)
            appendSection(location, "Extensions", node.extensions, node, to)
            appendSection(location, "Inheritors", node.inheritors, node, to)
            appendSection(location, "Links", node.links, node, to)

        }
    }

    abstract public fun appendOutlineHeader(to: StringBuilder, node: DocumentationNode)
    abstract public fun appendOutlineChildren(to: StringBuilder, nodes: Iterable<DocumentationNode>)

    public override fun appendOutline(location: Location, to: StringBuilder, nodes: Iterable<DocumentationNode>) {
        for (node in nodes) {
            appendOutlineHeader(to, node)
            if (node.members.any()) {
                appendOutlineChildren(to, node.members)
            }
        }
    }
}