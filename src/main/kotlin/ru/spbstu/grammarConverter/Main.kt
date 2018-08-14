package ru.spbstu.grammarConverter

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import ru.spbstu.grammarConverter.antlr4.ANTLRv4Lexer
import ru.spbstu.grammarConverter.antlr4.ANTLRv4Parser
import ru.spbstu.grammarConverter.antlr4.ANTLRv4ParserBaseVisitor
import java.io.File
import java.io.InputStream

sealed class GElement {
    open fun pToString(): String = "($this)"
}
data class Atom(val name: String) : GElement() {
    override fun toString(): String = "$name"
    override fun pToString(): String = toString()
}
data class RuleRef(val name: String) : GElement() {
    override fun toString(): String = name
    override fun pToString(): String = toString()
}
data class Optional(val element: GElement) : GElement() {
    override fun toString(): String = "${element.pToString()}?"
    override fun pToString(): String = toString()
}
data class Plus(val element: GElement) : GElement() {
    override fun toString(): String = "${element.pToString()}+"
    override fun pToString(): String = toString()
}
data class Star(val element: GElement) : GElement() {
    override fun toString(): String = "${element.pToString()}*"
    override fun pToString(): String = toString()
}
data class Choice(val elements: List<GElement>) : GElement() {
    override fun toString(): String = elements.joinToString(" | ") { it.pToString() }
}
fun mkChoice(elements: List<GElement>) = when {
    elements.size == 1 -> elements.first()
    else -> Choice(elements)
}
data class Seq(val elements: List<GElement>) : GElement() {
    override fun toString(): String = elements.joinToString(" ") { it.pToString() }
}
fun mkSeq(elements: List<GElement>) = when {
    elements.size == 1 -> elements.first()
    else -> Seq(elements)
}

data class Rule(val left: String, val right: GElement) {
    override fun toString(): String = "$left : $right"
}

fun Rule.toMarkdown() =
        // language=Markdown
        """
**_${left}_:**
 ~ ${right.toMarkdown(true)}
        """.trim()

fun List<Rule>.toMarkdown(setupDivs: Boolean) =
        joinToString("\n\n") {
            val ruleMD = it.toMarkdown()
            when {
                !setupDivs -> ruleMD
                else -> """
:::{ .grammar-rule #grammar-rule-${it.left} }
$ruleMD
:::
                """.trim()
            }
        }

private fun GElement.toMarkdownGroup(): String = when(this) {
    is Seq, is Plus, is Choice -> "(${toMarkdown()})"
    else -> toMarkdown()
}

const val MD_LB = "  \n"

private fun GElement.toMarkdown(topLevel: Boolean = false): String = when(this) {
    is Atom -> "`'${name}'`"
    is RuleRef -> "_[${name}](#grammar-rule-${name})_"
    is Optional -> "[${element.toMarkdown()}]"
    is Plus -> "${element.toMarkdown()} {${element.toMarkdown()}}"
    is Star -> "{${element.toMarkdown()}}"
    is Choice -> {
        val sep = when { topLevel -> MD_LB; else -> "" } + " | "
        elements.joinToString(sep) { it.toMarkdownGroup() }
    }
    is Seq -> {
        val sep = when { topLevel && elements.size > 4 -> "$MD_LB   "; else -> " " }
        elements.joinToString(sep) { it.toMarkdownGroup() }
    }
}

fun Rule.toCommonEBNF() =
        """
            $left ::= ${right.toCommonEBNF()}
        """.trim()

fun List<Rule>.toCommonEBNF() = joinToString("\n") { it.toCommonEBNF() }

private fun GElement.toCommonEBNFG(): String = when(this) {
    is Seq, is Choice -> "(${toCommonEBNF()})"
    else -> toCommonEBNF()
}

fun GElement.toCommonEBNF() = when(this) {
    is Atom -> "'$name'"
    is RuleRef -> name
    is Optional -> "${element.toCommonEBNFG()}?"
    is Plus -> "${element.toCommonEBNFG()}+"
    is Star -> "${element.toCommonEBNFG()}*"
    is Choice -> elements.joinToString(" | ") { it.toCommonEBNFG() }
    is Seq -> elements.joinToString(" ") { it.toCommonEBNFG() }
}


object Visitor : ANTLRv4ParserBaseVisitor<List<GElement>>() {
    override fun defaultResult(): List<GElement> = listOf()
    override fun aggregateResult(aggregate: List<GElement>, nextResult: List<GElement>): List<GElement> =
            aggregate + nextResult

    fun GElement.applySuffix(suffix: ANTLRv4Parser.EbnfSuffixContext?): GElement {
        suffix?.PLUS()?.let { return Plus(this) }
        suffix?.STAR()?.let { return Star(this) }
        suffix?.QUESTION()?.let { return Optional(this) }
        return this
    }

    override fun visitEbnf(ctx: ANTLRv4Parser.EbnfContext): List<GElement> {
        val suffix = ctx.blockSuffix()?.ebnfSuffix()
        val block = ctx.block()
        return visit(block).map { it.applySuffix(suffix) }
    }

    override fun visitBlock(ctx: ANTLRv4Parser.BlockContext): List<GElement> {
        return visit(ctx.altList())
    }

    override fun visitAltList(ctx: ANTLRv4Parser.AltListContext): List<GElement> {
        return listOf(mkChoice(ctx.alternative().flatMap { visit(it) }))
    }

    override fun visitRuleAltList(ctx: ANTLRv4Parser.RuleAltListContext): List<GElement> {
        return listOf(mkChoice(ctx.labeledAlt().flatMap { visit(it.alternative()) }))
    }

    override fun visitAlternative(ctx: ANTLRv4Parser.AlternativeContext): List<GElement> {
        return listOf(mkSeq(ctx.element().flatMap { visit(it) }))
    }

    override fun visitElement(ctx: ANTLRv4Parser.ElementContext): List<GElement> {
        val others=  super.visitElement(ctx)
        val suffix = ctx.ebnfSuffix()
        return others.map { it.applySuffix(suffix) }
    }

    override fun visitAtom(ctx: ANTLRv4Parser.AtomContext): List<GElement> {
        ctx.terminal()?.STRING_LITERAL()?.let { return listOf(Atom(it.symbol.text.removePrefix("'").removeSuffix("'"))) }
        ctx.terminal()?.TOKEN_REF()?.let { return listOf(RuleRef(it.symbol.text)) }
        ctx.ruleref()?.let { return listOf(RuleRef(it.text)) }
        throw IllegalArgumentException()
    }
}

fun ANTLRv4Parser.GrammarSpecContext.toUnified() =
        rules()
                .ruleSpec()
                .asSequence()
                .mapNotNull { it.parserRuleSpec() }
                .map {
                    Rule(it.RULE_REF().symbol.text, it.accept(Visitor).first())
                }
                .toList()

fun parseRules(ins: InputStream): List<Rule> {
    val lex = ANTLRv4Lexer(CharStreams.fromStream(ins))
    val p = ANTLRv4Parser(CommonTokenStream(lex))
    return p.grammarSpec().toUnified()
}

class Args(parser: ArgParser) {
    val inputFile
            by parser.positional(help = ".g4 file") {
                when(this) {
                    "-" -> System.`in`
                    else -> File(this).inputStream()
                }
            }.default(System.`in`)
    val markdown
            by parser.flagging("-m", "--markdown", help = "Output markdown").default(true)
    val divs
            by parser.flagging("-d", "--divs", help = "Setup div elements for markdown").default(false)
    val ebnf
            by parser.flagging("-e", "--ebnf", help = "Output ebnf").default(false)
    val outputFile
            by parser.storing("-o", "--output", help = "Destination file") {
                when(this) {
                    "-" -> System.`out`
                    else -> File(this).outputStream()
                }
            }.default(System.`out`)
}

fun main(args: Array<String>) = mainBody {
    val arguments = ArgParser(args).parseInto(::Args)

    val rules = parseRules(arguments.inputFile)
    val output = arguments.outputFile.bufferedWriter()

    if(arguments.ebnf) output.append(rules.toCommonEBNF()).append("\n").flush()
    else output.append(rules.toMarkdown(arguments.divs)).append("\n").flush()
}

