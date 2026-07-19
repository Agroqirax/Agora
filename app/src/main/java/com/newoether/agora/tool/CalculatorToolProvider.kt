package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Arithmetic tool the model can call instead of doing multi-digit or multi-step math in its
 * head, where transcription/arithmetic slips are most likely. Deliberately a single
 * `calculate` tool taking a free-form expression string, rather than separate add/subtract/etc
 * tools — the model already knows algebraic notation, so the cheapest and most flexible
 * interface is just letting it write the expression it wants evaluated.
 *
 * Expressions are evaluated by [ExpressionEvaluator], a small hand-rolled recursive-descent
 * parser — NOT [javax.script] or any `eval`-style mechanism — so the tool can only ever
 * produce a number from arithmetic, never execute arbitrary code. Supports +, -, *, /, %,
 * ^ (power), unary minus, parentheses, the constants `pi`/`e`, and common single-argument
 * functions (sqrt, abs, sin, cos, tan, asin, acos, atan, ln, log10, log2, exp, floor, ceil,
 * round).
 *
 * Pure computation, no device state or permission touched, so — like [DeviceInfoToolProvider]
 * — this has no confirm/permission gate, just a single enable toggle.
 */
class CalculatorToolProvider : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.calculatorEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = CALCULATE,
                description = "Evaluate a mathematical expression and return the numeric result. " +
                    "Supports +, -, *, /, % (modulo), ^ (power), parentheses, unary minus, the " +
                    "constants pi and e, and the functions sqrt, abs, sin, cos, tan, asin, acos, " +
                    "atan, ln (natural log), log10, log2, exp, floor, ceil, round. " +
                    "Use this instead of computing arithmetic yourself whenever precision matters.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "expression" to ToolProperty(
                            type = "string",
                            description = "The expression to evaluate, e.g. \"(12.5 * 3) - sqrt(49)\" or \"2^10 % 7\"."
                        )
                    ),
                    required = listOf("expression")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name == CALCULATE

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.calculatorEnabled) return err("disabled", "The calculator tool is disabled in settings.")

        val args = parseToolArgs(arguments)
        val expression = args["expression"]?.let { (it as? JsonPrimitive)?.content }
            ?: return err("invalid_argument", "expression is required.")

        return try {
            val result = ExpressionEvaluator(expression).evaluate()
            buildJsonObject {
                put("type", CALCULATE)
                put("expression", expression)
                put("result", result)
            }.toString()
        } catch (e: ExpressionEvaluator.EvaluationException) {
            err("evaluation_error", e.message)
        } catch (e: Exception) {
            DebugLog.e("CalculatorTool", "calculate failed", e)
            err("calculator_error", e.message)
        }
    }

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", CALCULATE)
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    companion object {
        const val CALCULATE = "calculate"
    }

    /**
     * Minimal recursive-descent parser/evaluator over the grammar:
     * ```
     * expr    := term (('+' | '-') term)*
     * term    := unary (('*' | '/' | '%') unary)*
     * unary   := '-' unary | power
     * power   := atom ('^' unary)?      // right-associative
     * atom    := NUMBER | CONST | FUNC '(' expr ')' | '(' expr ')'
     * ```
     * Never invokes a scripting/eval engine, so it can only ever produce a double from the
     * fixed set of operators/functions below — there's no way to reach arbitrary code
     * execution through this parser.
     */
    private class ExpressionEvaluator(expression: String) {
        private val text = expression
        private var pos = 0

        class EvaluationException(message: String) : Exception(message)

        fun evaluate(): Double {
            if (text.isBlank()) throw EvaluationException("Expression is empty.")
            val result = parseExpr()
            skipWhitespace()
            if (pos < text.length) {
                throw EvaluationException("Unexpected character '${text[pos]}' at position $pos.")
            }
            if (result.isNaN() || result.isInfinite()) {
                throw EvaluationException("Result is not a finite number (check for division by zero or invalid input).")
            }
            return result
        }

        private fun parseExpr(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                when (peek()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseUnary()
            while (true) {
                skipWhitespace()
                when (peek()) {
                    '*' -> { pos++; value *= parseUnary() }
                    '/' -> {
                        pos++
                        val divisor = parseUnary()
                        if (divisor == 0.0) throw EvaluationException("Division by zero.")
                        value /= divisor
                    }
                    '%' -> {
                        pos++
                        val divisor = parseUnary()
                        if (divisor == 0.0) throw EvaluationException("Division by zero.")
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parseUnary(): Double {
            skipWhitespace()
            return when (peek()) {
                '-' -> { pos++; -parseUnary() }
                '+' -> { pos++; parseUnary() }
                else -> parsePower()
            }
        }

        private fun parsePower(): Double {
            val base = parseAtom()
            skipWhitespace()
            if (peek() == '^') {
                pos++
                val exponent = parseUnary() // right-associative
                return base.pow(exponent)
            }
            return base
        }

        private fun parseAtom(): Double {
            skipWhitespace()
            if (pos >= text.length) throw EvaluationException("Unexpected end of expression.")
            val c = text[pos]
            return when {
                c == '(' -> {
                    pos++
                    val value = parseExpr()
                    skipWhitespace()
                    if (peek() != ')') throw EvaluationException("Missing closing parenthesis.")
                    pos++
                    value
                }
                c.isDigit() || c == '.' -> parseNumber()
                c.isLetter() -> parseIdentifier()
                else -> throw EvaluationException("Unexpected character '$c' at position $pos.")
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            // Support scientific notation, e.g. 1.5e-3
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E') &&
                pos + 1 < text.length && (text[pos + 1].isDigit() || text[pos + 1] == '+' || text[pos + 1] == '-')
            ) {
                pos++
                if (text[pos] == '+' || text[pos] == '-') pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val token = text.substring(start, pos)
            return token.toDoubleOrNull() ?: throw EvaluationException("Invalid number '$token'.")
        }

        private fun parseIdentifier(): Double {
            val start = pos
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
            val name = text.substring(start, pos).lowercase()

            skipWhitespace()
            if (peek() == '(') {
                pos++
                val arg = parseExpr()
                skipWhitespace()
                if (peek() != ')') throw EvaluationException("Missing closing parenthesis after $name(...).")
                pos++
                return applyFunction(name, arg)
            }

            return when (name) {
                "pi" -> PI
                "e" -> E
                else -> throw EvaluationException("Unknown identifier '$name'.")
            }
        }

        private fun applyFunction(name: String, arg: Double): Double = when (name) {
            "sqrt" -> {
                if (arg < 0) throw EvaluationException("Cannot take sqrt of a negative number.")
                sqrt(arg)
            }
            "abs" -> abs(arg)
            "sin" -> sin(arg)
            "cos" -> cos(arg)
            "tan" -> tan(arg)
            "asin" -> asin(arg)
            "acos" -> acos(arg)
            "atan" -> atan(arg)
            "ln" -> {
                if (arg <= 0) throw EvaluationException("Cannot take ln of a non-positive number.")
                ln(arg)
            }
            "log10" -> {
                if (arg <= 0) throw EvaluationException("Cannot take log10 of a non-positive number.")
                log10(arg)
            }
            "log2" -> {
                if (arg <= 0) throw EvaluationException("Cannot take log2 of a non-positive number.")
                ln(arg) / ln(2.0)
            }
            "exp" -> exp(arg)
            "floor" -> floor(arg)
            "ceil" -> ceil(arg)
            "round" -> round(arg)
            else -> throw EvaluationException("Unknown function '$name'.")
        }

        private fun peek(): Char? {
            skipWhitespace()
            return text.getOrNull(pos)
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
