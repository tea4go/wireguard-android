/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.sync

/**
 * Minimal JSON5 parser for the sync snapshot format: plain JSON plus unquoted keys,
 * single-quoted strings, line continuations, comments and trailing commas.
 * Produces [String], [Double]/[Long], [Boolean], [Map] and [List] values.
 */
object Json5 {
    fun parse(source: String): Any? {
        val parser = Parser(source)
        val value = parser.parseValue()
        parser.skipWhitespaceAndComments()
        if (!parser.atEnd()) throw IllegalArgumentException("Trailing content at ${parser.index}")
        return value
    }

    private class Parser(private val source: String) {
        var index = 0
            private set

        fun atEnd(): Boolean = index >= source.length

        fun skipWhitespaceAndComments() {
            while (index < source.length) {
                val current = source[index]
                if (current.isWhitespace()) {
                    index++
                } else if (current == '/' && index + 1 < source.length && source[index + 1] == '/') {
                    index += 2
                    while (index < source.length && source[index] != '\n' && source[index] != '\r') index++
                } else if (current == '/' && index + 1 < source.length && source[index + 1] == '*') {
                    index += 2
                    while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) index++
                    index = minOf(index + 2, source.length)
                } else {
                    break
                }
            }
        }

        fun parseValue(): Any? {
            skipWhitespaceAndComments()
            if (atEnd()) throw IllegalArgumentException("Unexpected end of input")
            return when (val current = source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '\'', '"' -> parseString()
                else -> {
                    if (current == '-' || current == '+' || current == '.' || current.isDigit()) parseNumber()
                    else if (current.isJavaIdentifierStart() || current == '$') parseLiteral()
                    else throw IllegalArgumentException("Unexpected character '$current' at $index")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index++ // {
            val result = LinkedHashMap<String, Any?>()
            while (true) {
                skipWhitespaceAndComments()
                if (atEnd()) throw IllegalArgumentException("Unterminated object")
                if (source[index] == '}') {
                    index++
                    return result
                }
                val key = when (val current = source[index]) {
                    '\'', '"' -> parseString()
                    else -> {
                        if (current == '}') {
                            index++
                            return result
                        }
                        parseIdentifier()
                    }
                }
                skipWhitespaceAndComments()
                if (atEnd() || source[index] != ':') throw IllegalArgumentException("Expected ':' at $index")
                index++
                result[key] = parseValue()
                skipWhitespaceAndComments()
                if (atEnd()) throw IllegalArgumentException("Unterminated object")
                when (source[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $index")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            index++ // [
            val result = ArrayList<Any?>()
            while (true) {
                skipWhitespaceAndComments()
                if (atEnd()) throw IllegalArgumentException("Unterminated array")
                if (source[index] == ']') {
                    index++
                    return result
                }
                result.add(parseValue())
                skipWhitespaceAndComments()
                if (atEnd()) throw IllegalArgumentException("Unterminated array")
                when (source[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $index")
                }
            }
        }

        private fun parseIdentifier(): String {
            val start = index
            if (atEnd() || !(source[index].isJavaIdentifierStart() || source[index] == '$'))
                throw IllegalArgumentException("Expected identifier at $index")
            index++
            while (index < source.length && (source[index].isJavaIdentifierPart() || source[index] == '$')) index++
            return source.substring(start, index)
        }

        private fun parseLiteral(): Any? {
            return when (val token = parseIdentifier()) {
                "true" -> true
                "false" -> false
                "null" -> null
                "Infinity", "NaN" -> throw IllegalArgumentException("Unsupported literal $token")
                else -> throw IllegalArgumentException("Unexpected identifier $token")
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (!atEnd() && (source[index] == '-' || source[index] == '+')) index++
            if (!atEnd() && source[index] == '0' && index + 1 < source.length &&
                (source[index + 1] == 'x' || source[index + 1] == 'X')
            ) {
                index += 2
                val hexStart = index
                while (index < source.length && source[index].isHexDigit()) index++
                return source.substring(hexStart, index).toLong(16)
            }
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '-' || source[index] == '+')) index++
                while (index < source.length && source[index].isDigit()) index++
            }
            val text = source.substring(start, index)
            return if (text.contains('.') || text.contains('e') || text.contains('E')) text.toDouble()
            else text.toLong()
        }

        private fun parseString(): String {
            val quote = source[index]
            index++
            val sb = StringBuilder()
            while (index < source.length) {
                val current = source[index]
                if (current == quote) {
                    index++
                    return sb.toString()
                }
                if (current != '\\') {
                    sb.append(current)
                    index++
                    continue
                }
                index++
                if (index >= source.length) break
                when (val escaped = source[index]) {
                    '\n' -> index++
                    '\r' -> {
                        index++
                        if (index < source.length && source[index] == '\n') index++
                    }
                    'x' -> {
                        if (index + 2 >= source.length) throw IllegalArgumentException("Invalid \\x escape at $index")
                        sb.append(source.substring(index + 1, index + 3).toInt(16).toChar())
                        index += 3
                    }
                    'u' -> {
                        if (index + 4 >= source.length) throw IllegalArgumentException("Invalid \\u escape at $index")
                        sb.append(source.substring(index + 1, index + 5).toInt(16).toChar())
                        index += 5
                    }
                    'n' -> { sb.append('\n'); index++ }
                    'r' -> { sb.append('\r'); index++ }
                    't' -> { sb.append('\t'); index++ }
                    'b' -> { sb.append('\b'); index++ }
                    'f' -> { sb.append('\u000C'); index++ }
                    'v' -> { sb.append('\u000B'); index++ }
                    '0' -> {
                        if (index + 1 < source.length && source[index + 1].isDigit())
                            throw IllegalArgumentException("Invalid octal escape at $index")
                        sb.append('\u0000')
                        index++
                    }
                    else -> { sb.append(escaped); index++ }
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
