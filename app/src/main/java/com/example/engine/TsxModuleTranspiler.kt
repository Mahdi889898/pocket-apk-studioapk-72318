package com.example.engine

import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

data class TranspileReport(
    val transpiledFileCount: Int,
    val injectedImportMap: Boolean,
    val warnings: List<String>
)

/**
 * Offline TypeScript / TSX / JSX -> standard ES Module (.js) transpiler
 * specifically tailored for Google AI Studio web & 3D game exports.
 *
 * Handles:
 * - TypeScript stripping (interfaces, type aliases, `import type`, `export type`, type annotations,
 *   generics, generic arrows `<T,>(...) =>`, `as`, `satisfies`, `enum`, `const enum`, non-null `!`)
 * - Source-accurate JSX/TSX -> `React.createElement(...)` lowering with direct JSX PCDATA scanning
 *   (safe with apostrophes, quotes, slashes, emojis, Persian text, `{/* comments */}`, nested `{expr}`, spreads)
 * - Relative & `@/` import resolution (extensionless `./App` -> `./App.js`, `./App.tsx` -> `./App.js`)
 * - CSS imports (`import './index.css'` -> auto-generated CSS style injector `.js` module) & `.json` module proxies
 * - `process.env` polyfill & automatic `importmap` synthesis from `package.json` if missing
 */
object TsxModuleTranspiler {

    private val SCRIPT_SRC_REGEX = Regex(
        """(<script\b[^>]*\bsrc\s*=\s*["'])([^"']+\.(tsx|ts|jsx))(\?[^"']*)?(["'][^>]*>)""",
        RegexOption.IGNORE_CASE
    )
    private val ROOT_ATTR_REGEX = Regex("""\b(src|href)\s*=\s*(["'])/([^/"'][^"']*)\2""")
    private val CSS_EXTS = setOf("css")
    private val ASSET_EXTS = setOf("png", "jpg", "jpeg", "webp", "svg", "gif", "glb", "gltf", "obj", "mtl", "wasm", "mp3", "wav", "ogg", "mp4", "json")

    fun processStagedProject(rootDir: File, entryHtmlRelPath: String): TranspileReport {
        if (entryHtmlRelPath.isBlank()) return TranspileReport(0, false, emptyList())
        val htmlFile = File(rootDir, entryHtmlRelPath)
        if (!htmlFile.exists()) return TranspileReport(0, false, emptyList())

        val allFiles = rootDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(rootDir).path.replace('\\', '/') }
            .toSet()

        val sourceFiles = allFiles.filter { rel ->
            val lower = rel.lowercase(Locale.US)
            !lower.startsWith("node_modules/") &&
                !lower.startsWith("dist/") &&
                !lower.startsWith("build/") &&
                (lower.endsWith(".tsx") || lower.endsWith(".ts") || lower.endsWith(".jsx")) &&
                !lower.endsWith(".d.ts")
        }

        var entryDirRel = entryHtmlRelPath.replace('\\', '/').substringBeforeLast('/', "")

        var transpiledCount = 0
        val warnings = mutableListOf<String>()

        for (rel in sourceFiles) {
            val srcFile = File(rootDir, rel)
            val isJsx = rel.endsWith(".tsx", true) || rel.endsWith(".jsx", true)
            val isTs = rel.endsWith(".ts", true) || rel.endsWith(".tsx", true)
            val source = srcFile.readText(StandardCharsets.UTF_8)

            val outputJs = runCatching {
                transpileModule(
                    code = source,
                    relPath = rel,
                    entryDirRel = entryDirRel,
                    rootDir = rootDir,
                    allFiles = allFiles,
                    isTs = isTs,
                    isJsx = isJsx
                )
            }.getOrElse { err ->
                warnings.add("$rel: ${err.message}")
                source
            }

            val outRel = rel.replace(Regex("""\.(tsx|ts|jsx)$""", RegexOption.IGNORE_CASE), ".js")
            val outFile = File(rootDir, outRel)
            outFile.parentFile?.mkdirs()
            outFile.writeText(outputJs, StandardCharsets.UTF_8)
            transpiledCount++
        }

        // Rewrite HTML entry: root-relative paths -> ./, .tsx/.ts/.jsx scripts -> .js, process shim + importmap
        var html = htmlFile.readText(StandardCharsets.UTF_8)
        html = ROOT_ATTR_REGEX.replace(html) { m ->
            val attr = m.groupValues[1]
            val quote = m.groupValues[2]
            val path = m.groupValues[3]
            "$attr=$quote./$path$quote"
        }
        html = SCRIPT_SRC_REGEX.replace(html) { m ->
            val prefix = m.groupValues[1]
            val srcPath = m.groupValues[2].replace(Regex("""\.(tsx|ts|jsx)$""", RegexOption.IGNORE_CASE), ".js")
            val query = m.groupValues[4]
            val suffix = m.groupValues[5]
            "$prefix$srcPath$query$suffix"
        }
        html = html.replace(
            Regex("""(<script\b[^>]*type\s*=\s*["']module["'][^>]*>)(.*?)(</script>)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        ) { m ->
            val body = m.groupValues[2].replace(Regex("""(\bfrom\s*["'][^"']+)\.(tsx|ts|jsx)(["'])""", RegexOption.IGNORE_CASE), "$1.js$3")
                .replace(Regex("""(\bimport\s*\(\s*["'][^"']+)\.(tsx|ts|jsx)(["']\s*\))""", RegexOption.IGNORE_CASE), "$1.js$3")
                .replace(Regex("""(\bimport\s*["'][^"']+)\.(tsx|ts|jsx)(["'])""", RegexOption.IGNORE_CASE), "$1.js$3")
            m.groupValues[1] + body + m.groupValues[3]
        }

        var injectedImportMap = false
        if (!html.contains("importmap", ignoreCase = true) && transpiledCount > 0) {
            val importMapTag = buildImportMapFromPackageJson(File(rootDir, "package.json"))
                ?: buildFallbackImportMap()
            html = injectIntoHead(html, importMapTag)
            injectedImportMap = true
        }

        if (!html.contains("window.process")) {
            val processShim = """<script>window.process=window.process||{env:{NODE_ENV:"production"}};globalThis.process=window.process;</script>"""
            html = injectIntoHead(html, processShim)
        }

        htmlFile.writeText(html, StandardCharsets.UTF_8)
        return TranspileReport(transpiledCount, injectedImportMap, warnings)
    }

    private fun injectIntoHead(html: String, snippet: String): String {
        val headOpen = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
        val match = headOpen.find(html)
        return if (match != null) {
            html.substring(0, match.range.last + 1) + "\n  " + snippet + html.substring(match.range.last + 1)
        } else {
            "$snippet\n$html"
        }
    }

    private fun extractCleanSemver(rawSpec: String, fallback: String): String {
        val match = Regex("""\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?""").find(rawSpec)
        return match?.value ?: fallback
    }

    private fun buildImportMapFromPackageJson(pkgFile: File): String? {
        if (!pkgFile.exists()) return null
        return runCatching {
            val json = JSONObject(pkgFile.readText(StandardCharsets.UTF_8))
            val deps = json.optJSONObject("dependencies") ?: return@runCatching null
            val imports = JSONObject()
            val reactVer = extractCleanSemver(deps.optString("react"), "19.0.0")
            val threeVer = deps.optString("three").takeIf { it.isNotBlank() }?.let { extractCleanSemver(it, "0.170.0") }
            val keys = deps.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val rawVer = extractCleanSemver(deps.optString(pkg), "latest")
                if (pkg == "react") {
                    imports.put("react", "https://esm.sh/react@$reactVer")
                    imports.put("react/", "https://esm.sh/react@$reactVer/")
                    imports.put("react/jsx-runtime", "https://esm.sh/react@$reactVer/jsx-runtime")
                } else if (pkg == "react-dom") {
                    imports.put("react-dom", "https://esm.sh/react-dom@$reactVer")
                    imports.put("react-dom/", "https://esm.sh/react-dom@$reactVer/")
                    imports.put("react-dom/client", "https://esm.sh/react-dom@$reactVer/client")
                } else {
                    val depsList = mutableListOf<String>()
                    if (pkg.contains("react")) {
                        depsList.add("react@$reactVer")
                        depsList.add("react-dom@$reactVer")
                    }
                    if (threeVer != null && (pkg.contains("three") || pkg.contains("drei"))) {
                        depsList.add("three@$threeVer")
                    }
                    val url = if (depsList.isNotEmpty()) {
                        "https://esm.sh/$pkg@$rawVer?deps=${depsList.joinToString(",")}"
                    } else {
                        "https://esm.sh/$pkg@$rawVer"
                    }
                    imports.put(pkg, url)
                    imports.put("$pkg/", "https://esm.sh/$pkg@$rawVer/")
                }
            }
            if (imports.length() == 0) return@runCatching null
            val wrapper = JSONObject().put("imports", imports)
            """<script type="importmap">${wrapper.toString(2)}</script>"""
        }.getOrNull()
    }

    private fun buildFallbackImportMap(): String {
        val json = """
            {
              "imports": {
                "react": "https://esm.sh/react@19.0.0",
                "react/": "https://esm.sh/react@19.0.0/",
                "react-dom": "https://esm.sh/react-dom@19.0.0",
                "react-dom/": "https://esm.sh/react-dom@19.0.0/",
                "react-dom/client": "https://esm.sh/react-dom@19.0.0/client",
                "three": "https://esm.sh/three@0.170.0",
                "three/": "https://esm.sh/three@0.170.0/",
                "lucide-react": "https://esm.sh/lucide-react@0.475.0?deps=react@19.0.0"
              }
            }
        """.trimIndent()
        return """<script type="importmap">$json</script>"""
    }

    fun transpileModule(
        code: String,
        relPath: String,
        rootDir: File,
        allFiles: Set<String>,
        isTs: Boolean,
        isJsx: Boolean,
        entryDirRel: String = ""
    ): String {
        val cleanCode = code.removePrefix("\uFEFF")
        val parser = Transpiler(
            src = cleanCode,
            baseOffset = 0,
            relPath = relPath,
            entryDirRel = entryDirRel,
            rootDir = rootDir,
            allFiles = allFiles,
            isTs = isTs,
            isJsx = isJsx,
            stopOnUnmatchedCloseBrace = false
        )
        var out = parser.transformProgram()
        if (isJsx && parser.usedReactCreateElement && !hasReactBinding(out)) {
            out = "import React from 'react';\n$out"
        }
        return out
    }

    private fun hasReactBinding(js: String): Boolean {
        return Regex("""\bimport\s+(?:\*\s+as\s+)?React\b|\b(?:const|let|var|function)\s+React\b""")
            .containsMatchIn(js)
    }

    // --- Tokenizer ---

    private enum class TokType {
        IDENT, NUMBER, STRING, TEMPLATE_FULL, TEMPLATE_HEAD, TEMPLATE_MID, TEMPLATE_TAIL,
        REGEX, PUNCT, EOF
    }

    private data class Tok(
        val type: TokType,
        val text: String,
        val leadingWs: String,
        val startOffset: Int,
        val endOffset: Int
    )

    private fun tokenize(src: String, startFrom: Int = 0): List<Tok> {
        val n = src.length
        val out = ArrayList<Tok>(((n - startFrom) / 4).coerceAtLeast(8))
        var i = startFrom.coerceIn(0, n)
        var wsBuf = StringBuilder()

        val templateBraceStack = ArrayDeque<Int>()
        var braceDepth = 0

        fun prevCanPrecedeRegex(): Boolean {
            val prev = out.lastOrNull() ?: return true
            return when (prev.type) {
                TokType.IDENT -> prev.text in setOf(
                    "return", "throw", "case", "delete", "void", "typeof",
                    "await", "yield", "in", "of", "instanceof", "else", "do", "new"
                )
                TokType.PUNCT -> prev.text !in setOf(")", "]", "++", "--")
                TokType.NUMBER, TokType.STRING, TokType.TEMPLATE_FULL, TokType.TEMPLATE_TAIL, TokType.REGEX -> false
                TokType.TEMPLATE_HEAD, TokType.TEMPLATE_MID, TokType.EOF -> true
            }
        }

        fun scanTemplatePart(isHead: Boolean, tokStart: Int): Tok {
            val sb = StringBuilder()
            sb.append(if (isHead) '`' else '}')
            while (i < n) {
                val c = src[i]
                if (c == '\\' && i + 1 < n) {
                    sb.append(c).append(src[i + 1])
                    i += 2
                } else if (c == '`') {
                    sb.append('`')
                    i++
                    val type = if (isHead) TokType.TEMPLATE_FULL else TokType.TEMPLATE_TAIL
                    val ws = wsBuf.toString()
                    wsBuf = StringBuilder()
                    return Tok(type, sb.toString(), ws, tokStart, i)
                } else if (c == '$' && i + 1 < n && src[i + 1] == '{') {
                    sb.append("\${")
                    i += 2
                    braceDepth++
                    templateBraceStack.addLast(braceDepth)
                    val type = if (isHead) TokType.TEMPLATE_HEAD else TokType.TEMPLATE_MID
                    val ws = wsBuf.toString()
                    wsBuf = StringBuilder()
                    return Tok(type, sb.toString(), ws, tokStart, i)
                } else {
                    sb.append(c)
                    i++
                }
            }
            val ws = wsBuf.toString()
            wsBuf = StringBuilder()
            return Tok(if (isHead) TokType.TEMPLATE_FULL else TokType.TEMPLATE_TAIL, sb.toString(), ws, tokStart, i)
        }

        while (i < n) {
            val c = src[i]
            if (c.isWhitespace()) {
                wsBuf.append(c)
                i++
                continue
            }
            // Line comment
            if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                i += 2
                while (i < n && src[i] != '\n') i++
                wsBuf.append(' ')
                continue
            }
            // Block comment
            if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                if (i + 1 < n) i += 2 else i = n
                wsBuf.append(' ')
                continue
            }
            // Template literal resume on matching `}`
            if (c == '}' && templateBraceStack.isNotEmpty() && templateBraceStack.last() == braceDepth) {
                val tokStart = i
                templateBraceStack.removeLast()
                braceDepth--
                i++
                out.add(scanTemplatePart(isHead = false, tokStart = tokStart))
                continue
            }
            // Template literal start
            if (c == '`') {
                val tokStart = i
                i++
                out.add(scanTemplatePart(isHead = true, tokStart = tokStart))
                continue
            }
            // String literal
            if (c == '\'' || c == '"') {
                val quote = c
                val start = i++
                while (i < n) {
                    val ch = src[i]
                    if (ch == '\\' && i + 1 < n) {
                        i += 2
                    } else if (ch == quote) {
                        i++
                        break
                    } else {
                        i++
                    }
                }
                out.add(Tok(TokType.STRING, src.substring(start, i), wsBuf.toString(), start, i))
                wsBuf = StringBuilder()
                continue
            }
            // Regex literal
            if (c == '/' && prevCanPrecedeRegex()) {
                val start = i++
                var inClass = false
                while (i < n) {
                    val ch = src[i]
                    if (ch == '\\' && i + 1 < n) {
                        i += 2
                    } else if (ch == '[') {
                        inClass = true
                        i++
                    } else if (ch == ']') {
                        inClass = false
                        i++
                    } else if (ch == '/' && !inClass) {
                        i++
                        break
                    } else if (ch == '\n') {
                        break
                    } else {
                        i++
                    }
                }
                while (i < n && src[i].isLetter()) i++
                out.add(Tok(TokType.REGEX, src.substring(start, i), wsBuf.toString(), start, i))
                wsBuf = StringBuilder()
                continue
            }
            // Number literal
            if (c.isDigit() || (c == '.' && i + 1 < n && src[i + 1].isDigit())) {
                val start = i
                if (c == '0' && i + 1 < n && (src[i + 1] == 'x' || src[i + 1] == 'X' || src[i + 1] == 'b' || src[i + 1] == 'o')) {
                    i += 2
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                } else {
                    while (i < n && (src[i].isDigit() || src[i] == '_')) i++
                    if (i < n && src[i] == '.') {
                        i++
                        while (i < n && (src[i].isDigit() || src[i] == '_')) i++
                    }
                    if (i < n && (src[i] == 'e' || src[i] == 'E')) {
                        i++
                        if (i < n && (src[i] == '+' || src[i] == '-')) i++
                        while (i < n && (src[i].isDigit() || src[i] == '_')) i++
                    }
                    if (i < n && src[i] == 'n') i++
                }
                val num = src.substring(start, i).replace("_", "")
                out.add(Tok(TokType.NUMBER, num, wsBuf.toString(), start, i))
                wsBuf = StringBuilder()
                continue
            }
            // Identifier
            if (c.isLetter() || c == '_' || c == '$') {
                val start = i++
                while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
                out.add(Tok(TokType.IDENT, src.substring(start, i), wsBuf.toString(), start, i))
                wsBuf = StringBuilder()
                continue
            }
            // Multi-char punctuators
            val start = i
            val three = if (i + 3 <= n) src.substring(i, i + 3) else ""
            val two = if (i + 2 <= n) src.substring(i, i + 2) else ""
            val punct = when {
                three in setOf("===", "!==", "...", ">>>", "**=", "&&=", "||=", "??=") -> three
                two in setOf("=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=", "%=", "**", "<<", ">>", "</", "/>", "::") -> two
                else -> src.substring(i, i + 1)
            }
            if (punct == "{") braceDepth++
            if (punct == "}") braceDepth = (braceDepth - 1).coerceAtLeast(0)
            i += punct.length
            out.add(Tok(TokType.PUNCT, punct, wsBuf.toString(), start, i))
            wsBuf = StringBuilder()
        }
        out.add(Tok(TokType.EOF, "", wsBuf.toString(), n, n))
        return out
    }

    // --- Recursive Transpiler ---

    private enum class BraceKind { CLASS_BODY, OTHER }

    private class Transpiler(
        private val src: String,
        baseOffset: Int,
        private val relPath: String,
        private val entryDirRel: String,
        private val rootDir: File,
        private val allFiles: Set<String>,
        private val isTs: Boolean,
        private val isJsx: Boolean,
        private val stopOnUnmatchedCloseBrace: Boolean
    ) {
        private var toks: List<Tok> = tokenize(src, baseOffset)
        private var pos = 0
        var endOffsetAfterStop: Int = src.length
            private set
        var usedReactCreateElement = false

        private val braceKinds = ArrayDeque<BraceKind>()
        private var parenDepth = 0
        private var bracketDepth = 0
        private var pendingClassBody = false
        private var inCaseExpr = false
        private var varDeclActive = false
        private var varDeclAfterEquals = false
        private var varDeclBaseDepth = 0
        private var classFieldAfterEquals = false
        private var classTernaryDepth = 0
        private var classMemberModifierSkipped = false
        private var pendingCtorAssignProps: List<String> = emptyList()

        private fun currentDepthSum(): Int = parenDepth + bracketDepth + braceKinds.size

        private fun peek(offset: Int = 0): Tok = toks[(pos + offset).coerceAtMost(toks.size - 1)]
        private fun next(): Tok = toks[pos++].also { if (pos >= toks.size) pos = toks.size - 1 }
        private fun isEof(): Boolean = peek().type == TokType.EOF

        private fun resyncAtSrcOffset(targetOffset: Int) {
            // Advance `pos` while tokens end at or before targetOffset
            while (pos < toks.size - 1 && toks[pos].endOffset <= targetOffset) {
                pos++
            }
            val cur = toks[pos]
            // If an unclosed quote inside JSX text corrupted the outer token stream (token straddles
            // targetOffset, or tokens skipped real code after targetOffset), re-tokenize cleanly from targetOffset.
            val misaligned = (cur.startOffset < targetOffset && cur.type != TokType.EOF) ||
                !onlyWsAndCommentsBetween(targetOffset, cur.startOffset)
            if (misaligned) {
                toks = tokenize(src, targetOffset)
                pos = 0
            }
        }

        private fun onlyWsAndCommentsBetween(from: Int, to: Int): Boolean {
            var k = from
            val limit = to.coerceAtMost(src.length)
            while (k < limit) {
                val c = src[k]
                if (c.isWhitespace()) {
                    k++
                } else if (c == '/' && k + 1 < limit && src[k + 1] == '/') {
                    k += 2
                    while (k < limit && src[k] != '\n') k++
                } else if (c == '/' && k + 1 < limit && src[k + 1] == '*') {
                    k += 2
                    while (k + 1 < limit && !(src[k] == '*' && src[k + 1] == '/')) k++
                    if (k + 1 < limit) k += 2 else return false
                } else {
                    return false
                }
            }
            return true
        }

        private val lastEmittedStack = ArrayDeque<Tok>()

        private fun emitToken(out: StringBuilder, tok: Tok, overrideText: String? = null) {
            val text = overrideText ?: tok.text
            out.append(tok.leadingWs).append(text)
            lastEmittedStack.addLast(tok.copy(text = text))
            if (lastEmittedStack.size > 4) lastEmittedStack.removeFirst()

            // Update structural tracking for accurate TS colon & class field handling
            classMemberModifierSkipped = false
            val prevEmittedText = lastEmittedStack.dropLast(1).lastOrNull()?.text
            if (braceKinds.lastOrNull() == BraceKind.CLASS_BODY && parenDepth == 0 && bracketDepth == 0 &&
                tok.leadingWs.contains('\n') &&
                prevEmittedText !in setOf("=", "?", ":", "+", "-", "*", "/", "&&", "||", "??", ",", "(") &&
                text !in setOf("?", ":", ".", "?.", "&&", "||", "??", "+", "-", "*", "/")
            ) {
                classFieldAfterEquals = false
                classTernaryDepth = 0
            }
            when (text) {
                "class" -> if (prevEmittedText != ".") {
                    pendingClassBody = true
                    varDeclActive = false
                }
                "case" -> inCaseExpr = true
                "default" -> if (peek().text == ":") inCaseExpr = true
                "const", "let", "var" -> if (prevEmittedText != ".") {
                    varDeclActive = true
                    varDeclAfterEquals = false
                    varDeclBaseDepth = currentDepthSum()
                }
                "return", "if", "for", "while", "switch", "try", "throw", "function" -> {
                    if (currentDepthSum() <= varDeclBaseDepth) {
                        varDeclActive = false
                    }
                }
                "{" -> {
                    braceKinds.addLast(if (pendingClassBody) BraceKind.CLASS_BODY else BraceKind.OTHER)
                    pendingClassBody = false
                    classFieldAfterEquals = false
                    classTernaryDepth = 0
                    if (pendingCtorAssignProps.isNotEmpty()) {
                        pendingCtorAssignProps.forEach { propName ->
                            out.append(" this.").append(propName).append(" = ").append(propName).append(";")
                        }
                        pendingCtorAssignProps = emptyList()
                    }
                }
                "}" -> {
                    if (braceKinds.isNotEmpty()) braceKinds.removeLast()
                    if (currentDepthSum() < varDeclBaseDepth) varDeclActive = false
                    classFieldAfterEquals = false
                    classTernaryDepth = 0
                }
                "(" -> parenDepth++
                ")" -> {
                    if (parenDepth > 0) parenDepth--
                    if (currentDepthSum() < varDeclBaseDepth) varDeclActive = false
                }
                "[" -> bracketDepth++
                "]" -> {
                    if (bracketDepth > 0) bracketDepth--
                    if (currentDepthSum() < varDeclBaseDepth) varDeclActive = false
                }
                "?" -> {
                    if (braceKinds.lastOrNull() == BraceKind.CLASS_BODY && parenDepth == 0 && bracketDepth == 0 && classFieldAfterEquals) {
                        classTernaryDepth++
                    }
                }
                ":" -> {
                    if (classTernaryDepth > 0 && braceKinds.lastOrNull() == BraceKind.CLASS_BODY && parenDepth == 0 && bracketDepth == 0) {
                        classTernaryDepth--
                    }
                }
                "=" -> {
                    if (varDeclActive && currentDepthSum() == varDeclBaseDepth) {
                        varDeclAfterEquals = true
                    }
                    if (braceKinds.lastOrNull() == BraceKind.CLASS_BODY && parenDepth == 0 && bracketDepth == 0) {
                        classFieldAfterEquals = true
                    }
                }
                "," -> {
                    if (varDeclActive && currentDepthSum() == varDeclBaseDepth) {
                        varDeclAfterEquals = false
                    }
                }
                ";", "in", "of", "=>" -> {
                    if (currentDepthSum() <= varDeclBaseDepth) {
                        varDeclActive = false
                        varDeclAfterEquals = false
                    }
                    if (text == ";") {
                        pendingClassBody = false
                        inCaseExpr = false
                        classFieldAfterEquals = false
                        classTernaryDepth = 0
                    }
                }
            }
            if (tok.type == TokType.TEMPLATE_HEAD) {
                braceKinds.addLast(BraceKind.OTHER)
            } else if (tok.type == TokType.TEMPLATE_MID || tok.type == TokType.TEMPLATE_TAIL) {
                if (braceKinds.isNotEmpty()) braceKinds.removeLast()
                if (tok.type == TokType.TEMPLATE_MID) braceKinds.addLast(BraceKind.OTHER)
            }
        }

        private fun lastMeaningfulToken(): Tok? = lastEmittedStack.lastOrNull()

        fun transformProgram(): String {
            val out = StringBuilder()
            while (!isEof()) {
                val cur = peek()
                if (stopOnUnmatchedCloseBrace && cur.text == "}" && braceKinds.isEmpty()) {
                    next()
                    endOffsetAfterStop = cur.endOffset
                    return out.toString()
                }
                transformNextItem(out, lastMeaningfulToken())
            }
            val eof = peek()
            out.append(eof.leadingWs)
            endOffsetAfterStop = eof.endOffset
            return out.toString()
        }

        private fun transformNextItem(out: StringBuilder, prevToken: Tok?) {
            val t = peek()
            if (t.type == TokType.EOF) return

            if (braceKinds.lastOrNull() == BraceKind.CLASS_BODY && parenDepth == 0 && bracketDepth == 0 &&
                classFieldAfterEquals && t.leadingWs.contains('\n') &&
                prevToken?.text !in setOf("=", "?", ":", "+", "-", "*", "/", "&&", "||", "??", ",", "(") &&
                t.text !in setOf("?", ":", ".", "?.", "&&", "||", "??", "+", "-", "*", "/")
            ) {
                classFieldAfterEquals = false
                classTernaryDepth = 0
            }

            // 1. TypeScript `import` / `export` handling & module specifier rewriting
            if (t.type == TokType.IDENT && t.text == "import" &&
                prevToken?.text != "." && prevToken?.text != "?." &&
                peek(1).text !in setOf("(", ".", ":", "=")
            ) {
                handleImport(out)
                return
            }

            if (isTs && t.type == TokType.IDENT && t.text == "export") {
                if (handleExport(out)) return
            }

            if (isTs && t.type == TokType.IDENT && t.text in setOf("interface", "type", "declare") &&
                prevToken?.text != "." && prevToken?.text != "?."
            ) {
                if (trySkipTypeDeclaration()) return
            }

            if (isTs && t.type == TokType.IDENT && t.text == "const" && peek(1).text == "enum") {
                next() // const
                handleEnum(out, exported = false)
                return
            }

            if (isTs && t.type == TokType.IDENT && t.text == "enum" && prevToken?.text != ".") {
                handleEnum(out, exported = false)
                return
            }

            // Strip TS accessibility/class modifiers only in class bodies or `abstract class`
            if (isTs && t.type == TokType.IDENT &&
                t.text in setOf("public", "private", "protected", "readonly", "override", "abstract") &&
                prevToken?.text != "." && prevToken?.text != "?."
            ) {
                val nxt = peek(1)
                val inClassBody = braceKinds.lastOrNull() == BraceKind.CLASS_BODY && parenDepth == 0 && bracketDepth == 0
                if (t.text == "abstract" && nxt.text == "class") {
                    next()
                    return
                }
                if (inClassBody) {
                    if (t.text == "abstract" && !classFieldAfterEquals &&
                        prevToken?.text !in setOf(".", "?.", "new", "=") &&
                        nxt.type == TokType.IDENT && looksLikeMethodSignatureWithoutBody(1)
                    ) {
                        skipMethodSignatureWithoutBody()
                        return
                    }
                    if (nxt.type == TokType.IDENT || nxt.text == "[" || nxt.text == "*") {
                        classMemberModifierSkipped = true
                        next()
                        return
                    }
                }
            }

            // Strip `implements A, B` before `{`
            if (isTs && t.type == TokType.IDENT && t.text == "implements" && prevToken?.text != ".") {
                next()
                while (!isEof() && peek().text != "{") next()
                return
            }

            // 2. Generic TS arrow `<T,>(x: T) => ...` BEFORE JSX / angle cast check
            if (isTs && t.text == "<" && isJsxStartContext(prevToken) && looksLikeGenericArrowPrefix()) {
                val ws = t.leadingWs
                skipBalancedAngles()
                out.append(ws)
                if (peek().text == "(") {
                    val isArrow = isArrowAfterParams(0)
                    transformParamList(out)
                    skipOptionalReturnType(isArrow)
                }
                return
            }

            // 2b. Old-style TS angle-bracket cast `<Type>expr` in `.ts` (non-JSX) files
            if (isTs && !isJsx && t.text == "<" && isJsxStartContext(prevToken) && looksLikeAngleTypeAssertion()) {
                skipBalancedAngles()
                return
            }

            // 3. JSX Element or Fragment `<Tag>` / `<>` parsed directly from `src`
            if (isJsx && t.text == "<" && isJsxStartContext(prevToken) && looksLikeJsxOpen(t.startOffset)) {
                val (jsxCode, afterOffset) = parseJsxElementFromSrc(t.startOffset)
                if (t.leadingWs.isNotEmpty()) {
                    out.append(t.leadingWs)
                } else if (out.isNotEmpty() && isJsIdentPart(out[out.length - 1])) {
                    out.append(' ')
                }
                out.append(jsxCode)
                lastEmittedStack.addLast(Tok(TokType.IDENT, "__jsx", "", t.startOffset, afterOffset))
                if (lastEmittedStack.size > 4) lastEmittedStack.removeFirst()
                resyncAtSrcOffset(afterOffset)
                return
            }

            // 4. Function declaration `function foo<T>(...): Ret` (or overload without body)
            if (isTs && t.type == TokType.IDENT && t.text == "function") {
                if (looksLikeFunctionOverloadWithoutBody(0)) {
                    skipFunctionOverloadSignature()
                    return
                }
                emitToken(out, next())
                if (peek().text == "*") emitToken(out, next())
                if (peek().type == TokType.IDENT) emitToken(out, next())
                if (peek().text == "<") skipBalancedAngles()
                if (peek().text == "(") {
                    transformParamList(out)
                    skipOptionalReturnType(isArrow = false)
                }
                return
            }

            // Class method overload signature without body: `play(x: number): void;`
            if (isTs && braceKinds.lastOrNull() == BraceKind.CLASS_BODY && parenDepth == 0 && bracketDepth == 0 &&
                !classFieldAfterEquals &&
                (prevToken == null || classMemberModifierSkipped || prevToken.text in setOf("{", ";", "}", "static", "async", "abstract") || t.leadingWs.contains('\n')) &&
                prevToken?.text !in setOf(".", "?.", "new", "=", ":", "+", "-", "*", "/", "(", "[", ",", "?", "&&", "||", "??") &&
                t.type == TokType.IDENT && (peek(1).text == "(" || peek(1).text == "<") &&
                looksLikeMethodSignatureWithoutBody(0)
            ) {
                classMemberModifierSkipped = false
                skipMethodSignatureWithoutBody()
                return
            }

            // Generic class `class Foo<T> extends Bar<T>`
            if (isTs && t.type == TokType.IDENT && t.text == "class" && prevToken?.text != ".") {
                emitToken(out, next())
                if (peek().type == TokType.IDENT) emitToken(out, next())
                if (peek().text == "<") skipBalancedAngles()
                if (peek().type == TokType.IDENT && peek().text == "extends") {
                    emitToken(out, next())
                    while (!isEof() && peek().text != "{" && peek().text != "implements") {
                        if (peek().text == "<") skipBalancedAngles()
                        else emitToken(out, next())
                    }
                }
                return
            }

            // Arrow function or method `(a: T, b?: U): Ret =>` or `constructor(x: T) {` or `render(): void {`
            if (isTs && t.text == "(" && looksLikeTypedCallableParams(prevToken)) {
                val isArrow = isArrowAfterParams(0)
                val isCtor = prevToken?.text == "constructor" && braceKinds.lastOrNull() == BraceKind.CLASS_BODY
                transformParamList(out, isConstructor = isCtor)
                skipOptionalReturnType(isArrow)
                return
            }

            // Method / function call with generic args: `useState<number>(0)` or `new Map<string, number>()`
            if (isTs && t.text == "<" && prevToken != null &&
                (prevToken.type == TokType.IDENT || prevToken.text in setOf(")", "]", "?.")) &&
                looksLikeCallTypeArgs()
            ) {
                skipBalancedAngles()
                return
            }

            // `case X:` or `default:`
            if (t.text == ":" && inCaseExpr) {
                inCaseExpr = false
                emitToken(out, next())
                return
            }

            // Parameter / variable / class field `: Type` annotation
            if (isTs && t.text == ":" && shouldStripTypeColon(prevToken)) {
                next() // consume ':'
                skipTypeExpression(stopAt = setOf("=", ",", ")", ";", "of", "in"))
                if (varDeclActive && peek().text !in setOf("=", ",")) {
                    varDeclActive = false
                    varDeclAfterEquals = false
                }
                return
            }

            // `as Type` / `satisfies Type`
            if (isTs && t.type == TokType.IDENT && (t.text == "as" || t.text == "satisfies") &&
                prevToken != null && (prevToken.type != TokType.PUNCT || prevToken.text in setOf(")", "]", "}"))
            ) {
                next()
                skipTypeExpression(
                    stopAt = setOf(
                        "=", "+=", "-=", "*=", "/=", "%=", "**=", "&&=", "||=", "??=",
                        ",", ")", ";", "}", "]", "?.", "?", ":",
                        "+", "-", "*", "/", "%", "**", "&&", "||", "??",
                        "===", "!==", "==", "!=", "<=", ">=", "<", ">", "<<", ">>", ">>>",
                        "in", "instanceof", "as", "satisfies"
                    )
                )
                return
            }

            // Optional class field `?` before `:` or `;`
            if (isTs && t.text == "?" && braceKinds.lastOrNull() == BraceKind.CLASS_BODY &&
                parenDepth == 0 && bracketDepth == 0 && !classFieldAfterEquals &&
                prevToken?.type == TokType.IDENT && (peek(1).text == ":" || peek(1).text == ";")
            ) {
                next()
                return
            }

            // Non-null assertion `foo!` or definite assignment `speed!: number`
            if (isTs && t.text == "!" && !t.leadingWs.contains('\n') && prevToken != null &&
                (prevToken.text == ")" || prevToken.text == "]" ||
                    (prevToken.type == TokType.IDENT && prevToken.text !in setOf(
                        "return", "throw", "await", "yield", "typeof", "void",
                        "delete", "in", "of", "case", "else", "new", "do"
                    ))) &&
                peek(1).text != "="
            ) {
                next()
                return
            }

            // Safe `import.meta.env` polyfill so Vite env reads do not throw TypeError in raw browser ESM
            if (t.type == TokType.IDENT && t.text == "import" && peek(1).text == "." &&
                peek(2).text == "meta" && peek(3).text == "." && peek(4).text == "env" &&
                prevToken?.text != "."
            ) {
                val imp = next(); next(); next(); next(); next()
                out.append(imp.leadingWs).append("(import.meta.env || window.process?.env || {})")
                lastEmittedStack.addLast(Tok(TokType.PUNCT, ")", "", imp.startOffset, imp.endOffset))
                if (lastEmittedStack.size > 4) lastEmittedStack.removeFirst()
                return
            }

            // Dynamic import('./App')
            if (t.type == TokType.IDENT && t.text == "import" && peek(1).text == "(" && peek(2).type == TokType.STRING) {
                emitToken(out, next()) // import
                emitToken(out, next()) // (
                val strTok = next()
                val resolved = rewriteModuleSpecifier(strTok.text.trim('"', '\''))
                emitToken(out, strTok, "\"$resolved\"")
                return
            }

            emitToken(out, next())
        }

        private fun handleImport(out: StringBuilder) {
            val importTok = next()
            // `import type Foo from '...'` or `import type { A, B } from '...'` or `import type * as T from '...'`
            if (isTs && peek().type == TokType.IDENT && peek().text == "type" &&
                peek(1).text != "from" && peek(1).text != ","
            ) {
                skipTypeImportOrExportClause()
                return
            }

            val collected = mutableListOf<Tok>()
            while (!isEof()) {
                val cur = next()
                collected.add(cur)
                if (cur.text == ";") break
                if (cur.type == TokType.STRING && collected.size >= 2 && collected[collected.size - 2].text == "from") {
                    if (peek().text in setOf("with", "assert") && peek(1).text == "{") {
                        next()
                        skipBalanced("{", "}")
                    }
                    if (peek().text == ";") collected.add(next())
                    break
                }
                if (cur.type == TokType.STRING && collected.size == 1) {
                    if (peek().text == ";") collected.add(next())
                    break
                }
            }

            // Side-effect import: `import './index.css';`
            if (collected.size in 1..2 && collected[0].type == TokType.STRING) {
                val spec = collected[0].text.trim('"', '\'')
                val rewritten = rewriteModuleSpecifier(spec)
                out.append(importTok.leadingWs).append("import \"").append(rewritten).append("\";")
                return
            }

            val cleaned = mutableListOf<Tok>()
            var idx = 0
            var hadNonTypeBinding = false
            var sawBraces = false
            while (idx < collected.size) {
                val c = collected[idx]
                if (c.text == "{") {
                    sawBraces = true
                    idx++
                    val inner = mutableListOf<Tok>()
                    while (idx < collected.size && collected[idx].text != "}") {
                        inner.add(collected[idx])
                        idx++
                    }
                    val parts = splitByComma(inner).filter { part ->
                        !(isTs && part.size >= 2 && part[0].type == TokType.IDENT && part[0].text == "type" && part[1].text != "as")
                    }
                    if (parts.isNotEmpty()) {
                        hadNonTypeBinding = true
                        cleaned.add(c)
                        parts.forEachIndexed { pIdx, part ->
                            if (pIdx > 0) cleaned.add(Tok(TokType.PUNCT, ",", "", 0, 0))
                            part.forEach { cleaned.add(it) }
                        }
                        if (idx < collected.size && collected[idx].text == "}") {
                            cleaned.add(collected[idx])
                            idx++
                        }
                    } else {
                        // Drop trailing comma before empty `{}` when default import also exists (e.g. `import React, { type FC }`)
                        if (cleaned.lastOrNull()?.text == ",") {
                            cleaned.removeAt(cleaned.lastIndex)
                        }
                        if (idx < collected.size && collected[idx].text == "}") idx++
                    }
                    continue
                }
                if (c.type == TokType.IDENT && c.text !in setOf("from", "as")) {
                    hadNonTypeBinding = true
                }
                if (c.type == TokType.STRING) {
                    val spec = c.text.trim('"', '\'')
                    val rewritten = rewriteModuleSpecifier(spec)
                    cleaned.add(c.copy(text = "\"$rewritten\""))
                } else {
                    cleaned.add(c)
                }
                idx++
            }

            if (sawBraces && !hadNonTypeBinding) {
                return
            }
            emitToken(out, importTok)
            cleaned.forEach { emitToken(out, it) }
        }

        private fun skipTypeImportOrExportClause() {
            if (peek().text == "type") next()
            if (peek().text == "{") {
                skipBalanced("{", "}")
            } else if (peek().text == "*") {
                next()
                if (peek().text == "as") {
                    next()
                    if (peek().type == TokType.IDENT) next()
                }
            } else if (peek().type == TokType.IDENT) {
                next()
                if (peek().text == ",") {
                    next()
                    if (peek().text == "{") skipBalanced("{", "}")
                }
            }
            if (peek().text == "from") {
                next()
                if (peek().type == TokType.STRING) next()
            }
            if (peek().text == ";") next()
        }

        private fun handleExport(out: StringBuilder): Boolean {
            val n1 = peek(1)
            // `export type ...` or `export interface ...` or `export declare ...`
            if (n1.type == TokType.IDENT && n1.text in setOf("type", "interface", "declare")) {
                next() // export
                if (n1.text == "type" && (peek(1).text == "{" || peek(1).text == "*")) {
                    skipTypeImportOrExportClause()
                    return true
                }
                if (!trySkipTypeDeclaration()) {
                    skipStatement()
                }
                return true
            }
            if (n1.type == TokType.IDENT && n1.text == "const" && peek(2).text == "enum") {
                next() // export
                next() // const
                handleEnum(out, exported = true)
                return true
            }
            if (n1.type == TokType.IDENT && n1.text == "enum") {
                next() // export
                handleEnum(out, exported = true)
                return true
            }
            if (n1.type == TokType.IDENT && n1.text == "function" && looksLikeFunctionOverloadWithoutBody(1)) {
                next() // export
                skipFunctionOverloadSignature()
                return true
            }
            // `export { A, type B } from './foo'` or `export * from './foo'`
            if (n1.text == "{" || n1.text == "*") {
                val exportTok = next() // export
                val collected = mutableListOf<Tok>()
                while (!isEof()) {
                    val cur = next()
                    collected.add(cur)
                    if (cur.text == ";") break
                    if (cur.type == TokType.STRING) {
                        if (peek().text in setOf("with", "assert") && peek(1).text == "{") {
                            next()
                            skipBalanced("{", "}")
                        }
                        if (peek().text == ";") collected.add(next())
                        break
                    }
                    if (cur.text == "}" && peek().text != "from") {
                        if (peek().text == ";") collected.add(next())
                        break
                    }
                }
                val outTokens = mutableListOf<Tok>()
                var i = 0
                var sawBraces = false
                var hadValueExport = false
                while (i < collected.size) {
                    val c = collected[i]
                    if (c.text == "{") {
                        sawBraces = true
                        outTokens.add(c)
                        i++
                        val inner = mutableListOf<Tok>()
                        while (i < collected.size && collected[i].text != "}") {
                            inner.add(collected[i])
                            i++
                        }
                        val parts = splitByComma(inner).filter { part ->
                            !(isTs && part.size >= 2 && part[0].type == TokType.IDENT && part[0].text == "type" && part[1].text != "as")
                        }
                        if (parts.isNotEmpty()) hadValueExport = true
                        parts.forEachIndexed { pIdx, part ->
                            if (pIdx > 0) outTokens.add(Tok(TokType.PUNCT, ",", "", 0, 0))
                            part.forEach { outTokens.add(it) }
                        }
                        if (i < collected.size && collected[i].text == "}") {
                            outTokens.add(collected[i])
                            i++
                        }
                    } else {
                        if (c.text == "*") hadValueExport = true
                        if (c.type == TokType.STRING) {
                            val rewritten = rewriteModuleSpecifier(c.text.trim('"', '\''))
                            outTokens.add(c.copy(text = "\"$rewritten\""))
                        } else {
                            outTokens.add(c)
                        }
                        i++
                    }
                }
                if (sawBraces && !hadValueExport) return true
                emitToken(out, exportTok)
                outTokens.forEach { emitToken(out, it) }
                return true
            }
            return false
        }

        private fun handleEnum(out: StringBuilder, exported: Boolean) {
            val enumTok = next() // enum
            val nameTok = if (peek().type == TokType.IDENT) next() else return
            if (peek().text != "{") return
            next() // {
            val entries = mutableListOf<Pair<String, String>>()
            var autoNum = 0
            while (!isEof() && peek().text != "}") {
                if (peek().text == ",") {
                    next()
                    continue
                }
                val keyTok = next()
                if (peek().text == "=") {
                    next()
                    val valSb = StringBuilder()
                    var depth = 0
                    while (!isEof()) {
                        val p = peek()
                        if (depth == 0 && (p.text == "," || p.text == "}")) break
                        if (p.text in setOf("(", "[", "{")) depth++
                        if (p.text in setOf(")", "]", "}")) depth--
                        val tk = next()
                        if (valSb.isNotEmpty()) valSb.append(tk.leadingWs)
                        valSb.append(tk.text)
                    }
                    val vStr = valSb.toString().trim()
                    vStr.toIntOrNull()?.let { autoNum = it + 1 }
                    entries.add(keyTok.text to vStr)
                } else {
                    entries.add(keyTok.text to autoNum.toString())
                    autoNum++
                }
            }
            if (peek().text == "}") next()
            out.append(enumTok.leadingWs)
            if (exported) out.append("export ")
            out.append("const ").append(nameTok.text).append(" = { ")
            entries.forEachIndexed { idx, (k, v) ->
                if (idx > 0) out.append(", ")
                out.append(k).append(": ").append(v)
            }
            out.append(" };")
        }

        private fun trySkipTypeDeclaration(): Boolean {
            val kw = peek()
            if (kw.text == "interface" && peek(1).type == TokType.IDENT) {
                next()
                var ang = 0
                while (!isEof() && (ang > 0 || peek().text != "{")) {
                    val pt = next()
                    when (pt.text) {
                        "<" -> ang++
                        ">" -> if (ang > 0) ang--
                        ">>" -> ang = (ang - 2).coerceAtLeast(0)
                        ">>>" -> ang = (ang - 3).coerceAtLeast(0)
                    }
                }
                if (peek().text == "{") skipBalanced("{", "}")
                return true
            }
            if (kw.text == "type") {
                if (peek(1).type == TokType.IDENT && (peek(2).text == "=" || peek(2).text == "<")) {
                    skipTypeAliasStatement()
                    return true
                }
            }
            if (kw.text == "declare" && peek(1).text in setOf(
                    "module", "namespace", "global", "const", "let", "var",
                    "function", "class", "enum", "type", "interface", "async", "abstract"
                )
            ) {
                next()
                if (peek().text in setOf("module", "namespace", "global")) {
                    while (!isEof() && peek().text != "{") next()
                    if (peek().text == "{") skipBalanced("{", "}")
                } else {
                    skipStatement()
                }
                return true
            }
            return false
        }

        private fun skipTypeAliasStatement() {
            var depth = 0
            var angleDepth = 0
            var sawEquals = false
            while (!isEof()) {
                val t = next()
                if (t.text == "=" && depth == 0 && angleDepth == 0) sawEquals = true
                when (t.text) {
                    "<" -> if (depth == 0 && !sawEquals) angleDepth++
                    ">" -> if (depth == 0 && !sawEquals && angleDepth > 0) angleDepth--
                    ">>" -> if (depth == 0 && !sawEquals && angleDepth > 0) angleDepth = (angleDepth - 2).coerceAtLeast(0)
                    ">>>" -> if (depth == 0 && !sawEquals && angleDepth > 0) angleDepth = (angleDepth - 3).coerceAtLeast(0)
                    "{", "(", "[" -> depth++
                    "}", ")", "]" -> {
                        depth--
                        if (depth <= 0 && angleDepth == 0 && t.text == "}" && sawEquals &&
                            peek().text !in setOf("|", "&", "[", "(", "extends")
                        ) {
                            if (peek().text == ";") next()
                            break
                        }
                    }
                    ";" -> if (depth <= 0 && angleDepth == 0) break
                }
                if (depth == 0 && angleDepth == 0 && sawEquals && t.text != ";" && peek().leadingWs.contains('\n') &&
                    peek().text !in setOf("|", "&", "=", "extends", "{", "(", "[", ";") &&
                    t.text !in setOf("|", "&", "=", ",", "<")
                ) {
                    break
                }
            }
        }

        private fun skipStatement() {
            var depth = 0
            while (!isEof()) {
                val t = next()
                when (t.text) {
                    "{", "(", "[" -> depth++
                    "}", ")", "]" -> {
                        depth--
                        if (depth <= 0 && t.text == "}" && peek().text !in setOf("|", "&", "from", ";")) {
                            if (peek().text == ";") next()
                            break
                        }
                    }
                    ";" -> if (depth <= 0) break
                }
                if (depth == 0 && t.text != ";" && peek().leadingWs.contains('\n') &&
                    peek().text !in setOf("|", "&", "=", "extends", "{", "(", "from", "as") &&
                    t.text !in setOf("|", "&", "=", ",", "<", "from", "as")
                ) {
                    break
                }
            }
        }

        private fun skipBalanced(open: String, close: String) {
            if (peek().text != open) return
            next()
            var depth = 1
            while (!isEof() && depth > 0) {
                val t = next()
                if (t.text == open) depth++
                else if (t.text == close) depth--
            }
        }

        private fun skipBalancedAngles() {
            if (peek().text != "<") return
            next()
            var depth = 1
            while (!isEof() && depth > 0) {
                val t = next()
                when (t.text) {
                    "<" -> depth++
                    ">" -> depth--
                    ">>" -> depth = (depth - 2).coerceAtLeast(0)
                    ">>>" -> depth = (depth - 3).coerceAtLeast(0)
                }
            }
        }

        private fun looksLikeGenericArrowPrefix(): Boolean {
            var idx = 1
            var depth = 1
            var sawCommaOrExtends = false
            while (idx < 400) {
                val t = peek(idx)
                if (t.type == TokType.EOF || t.text == ";" || t.text == "/>") return false
                when (t.text) {
                    "<" -> depth++
                    ">", ">>", ">>>" -> {
                        depth = (depth - t.text.length).coerceAtLeast(0)
                        if (depth == 0) {
                            val after = peek(idx + 1).text
                            if (after != "(") return false
                            // Check if after matching `)` we see `=>` or `:`
                            var pIdx = idx + 2
                            var pDepth = 1
                            while (pIdx < idx + 1200) {
                                val pt = peek(pIdx)
                                if (pt.type == TokType.EOF) return false
                                if (pt.text == "(") pDepth++
                                else if (pt.text == ")") {
                                    pDepth--
                                    if (pDepth == 0) {
                                        val afterParen = peek(pIdx + 1).text
                                        return afterParen == "=>" || afterParen == ":"
                                    }
                                }
                                pIdx++
                            }
                            return sawCommaOrExtends
                        }
                    }
                    else -> if (depth == 1 && (t.text == "," || t.text == "extends")) {
                        sawCommaOrExtends = true
                    }
                }
                idx++
            }
            return false
        }

        private fun looksLikeCallTypeArgs(): Boolean {
            var idx = 1
            var angle = 1
            var brace = 0
            var paren = 0
            var bracket = 0
            while (idx < 250) {
                val t = peek(idx)
                if (t.type == TokType.EOF) return false
                if (brace == 0 && paren == 0 && bracket == 0) {
                    if (t.text == ";") return false
                    if (angle == 1 && t.text in setOf("&&", "||", "==", "!=", "===", "!==", "<=", ">=", "+", "-", "*", "/", "%", "??")) {
                        return false
                    }
                }
                when (t.text) {
                    "{" -> brace++
                    "}" -> if (brace > 0) brace-- else return false
                    "(" -> paren++
                    ")" -> if (paren > 0) paren-- else return false
                    "[" -> bracket++
                    "]" -> if (bracket > 0) bracket-- else return false
                    "<" -> angle++
                    ">", ">>", ">>>" -> {
                        val dec = t.text.length
                        if (angle > dec) {
                            angle -= dec
                        } else if (angle == dec && brace == 0 && paren == 0 && bracket == 0) {
                            return peek(idx + 1).text == "("
                        } else if (brace == 0 && paren == 0 && bracket == 0) {
                            return false
                        } else {
                            angle = (angle - dec).coerceAtLeast(0)
                        }
                    }
                }
                idx++
            }
            return false
        }

        private fun looksLikeAngleTypeAssertion(): Boolean {
            var idx = 1
            var angle = 1
            var brace = 0
            var paren = 0
            var bracket = 0
            while (idx < 80) {
                val t = peek(idx)
                if (t.type == TokType.EOF || t.text == ";" || (angle == 1 && brace == 0 && paren == 0 && bracket == 0 &&
                        t.text in setOf("&&", "||", "==", "!=", "===", "!==", "<=", ">=", "+", "-", "*", "/"))) {
                    return false
                }
                when (t.text) {
                    "{" -> brace++
                    "}" -> if (brace > 0) brace-- else return false
                    "(" -> paren++
                    ")" -> if (paren > 0) paren-- else return false
                    "[" -> bracket++
                    "]" -> if (bracket > 0) bracket-- else return false
                    "<" -> angle++
                    ">", ">>", ">>>" -> {
                        val dec = t.text.length
                        if (angle > dec) {
                            angle -= dec
                        } else if (angle == dec && brace == 0 && paren == 0 && bracket == 0) {
                            val after = peek(idx + 1)
                            return after.type in setOf(TokType.IDENT, TokType.NUMBER, TokType.STRING, TokType.TEMPLATE_FULL, TokType.TEMPLATE_HEAD) ||
                                after.text in setOf("(", "[", "{", "!", "-", "+")
                        } else {
                            return false
                        }
                    }
                }
                idx++
            }
            return false
        }

        private fun looksLikeTypedCallableParams(prevToken: Tok?): Boolean {
            if (prevToken?.text in setOf("if", "while", "for", "switch", "with")) return false
            var idx = 1
            var depth = 1
            var brace = 0
            var bracket = 0
            var sawTypeColon = false
            var sawParamModifier = false
            var sawQuestion = false
            while (idx < 2000) {
                val t = peek(idx)
                if (t.type == TokType.EOF) return false
                when (t.text) {
                    "(" -> depth++
                    ")" -> {
                        depth--
                        if (depth == 0) {
                            val after = peek(idx + 1).text
                            if ((sawTypeColon || sawParamModifier) && (after == "=>" || after == ":" || after == "{")) return true
                            if (after == ":") {
                                return looksLikeReturnTypeBeforeBodyOrArrow(idx + 2)
                            }
                            return false
                        }
                    }
                    "{" -> if (depth == 1) brace++
                    "}" -> if (depth == 1 && brace > 0) brace--
                    "[" -> if (depth == 1) bracket++
                    "]" -> if (depth == 1 && bracket > 0) bracket--
                    "?" -> if (depth == 1 && brace == 0 && bracket == 0 && peek(idx + 1).text !in setOf(":", ",", ")")) sawQuestion = true
                    ":" -> if (depth == 1 && brace == 0 && bracket == 0) {
                        if (sawQuestion) sawQuestion = false else sawTypeColon = true
                    }
                    "public", "private", "protected", "readonly" -> if (depth == 1 && brace == 0 && bracket == 0 &&
                        (peek(idx + 1).type == TokType.IDENT || peek(idx + 1).text == "readonly")
                    ) {
                        sawParamModifier = true
                    }
                }
                idx++
            }
            return false
        }

        private fun isArrowAfterParams(openParenOffset: Int): Boolean {
            var idx = openParenOffset + 1
            var depth = 1
            while (idx < openParenOffset + 2000) {
                val t = peek(idx)
                if (t.type == TokType.EOF) return false
                if (t.text == "(") depth++
                else if (t.text == ")") {
                    depth--
                    if (depth == 0) {
                        if (peek(idx + 1).text == "=>") return true
                        if (peek(idx + 1).text == ":") {
                            return isArrowAfterReturnType(idx + 2)
                        }
                        return false
                    }
                }
                idx++
            }
            return false
        }

        private fun isArrowAfterReturnType(startIdx: Int): Boolean {
            var idx = startIdx
            var angle = 0; var paren = 0; var brace = 0; var bracket = 0
            while (idx < startIdx + 1000) {
                val t = peek(idx)
                if (t.type == TokType.EOF) return false
                if (angle == 0 && paren == 0 && brace == 0 && bracket == 0) {
                    if (t.text == "=>") return true
                    if (t.text == ";" || t.text == "{") {
                        // Could be `{ a: number } =>` vs body `{`
                        if (t.text == "{") {
                            val afterBrace = indexAfterMatching(idx, "{", "}")
                            if (afterBrace >= 0 && peek(afterBrace).text == "=>") return true
                        }
                        return false
                    }
                }
                when (t.text) {
                    "<" -> angle++
                    ">" -> if (angle > 0) angle--
                    ">>" -> if (angle > 0) angle = (angle - 2).coerceAtLeast(0)
                    ">>>" -> if (angle > 0) angle = (angle - 3).coerceAtLeast(0)
                    "(" -> paren++
                    ")" -> if (paren > 0) paren-- else return false
                    "{" -> brace++
                    "}" -> if (brace > 0) brace-- else return false
                    "[" -> bracket++
                    "]" -> if (bracket > 0) bracket-- else return false
                }
                idx++
            }
            return false
        }

        private fun indexAfterMatching(startIdx: Int, open: String, close: String): Int {
            if (peek(startIdx).text != open) return -1
            var idx = startIdx + 1
            var depth = 1
            while (idx < startIdx + 1000 && peek(idx).type != TokType.EOF) {
                val cur = peek(idx).text
                if (cur == open) depth++
                else if (cur == close) {
                    depth--
                    if (depth == 0) return idx + 1
                } else if (open == "<" && (cur == ">>" || cur == ">>>")) {
                    depth = (depth - cur.length).coerceAtLeast(0)
                    if (depth == 0) return idx + 1
                }
                idx++
            }
            return -1
        }

        private fun looksLikeReturnTypeBeforeBodyOrArrow(startIdx: Int): Boolean {
            var idx = startIdx
            var angle = 0
            var paren = 0
            var brace = 0
            var bracket = 0
            while (idx < startIdx + 1000) {
                val t = peek(idx)
                if (t.type == TokType.EOF) return false
                if (angle == 0 && paren == 0 && brace == 0 && bracket == 0) {
                    if (t.text == ";") return false
                    if (t.text == "=>" || t.text == "{") return true
                }
                when (t.text) {
                    "<" -> angle++
                    ">" -> if (angle > 0) angle--
                    ">>" -> if (angle > 0) angle = (angle - 2).coerceAtLeast(0)
                    ">>>" -> if (angle > 0) angle = (angle - 3).coerceAtLeast(0)
                    "(" -> paren++
                    ")" -> if (paren > 0) paren-- else return false
                    "{" -> brace++
                    "}" -> if (brace > 0) brace-- else return false
                    "[" -> bracket++
                    "]" -> if (bracket > 0) bracket-- else return false
                }
                idx++
            }
            return false
        }

        private fun looksLikeFunctionOverloadWithoutBody(funcOffset: Int): Boolean {
            var idx = funcOffset + 1
            if (peek(idx).text == "*") idx++
            if (peek(idx).type != TokType.IDENT) return false
            idx++
            if (peek(idx).text == "<") {
                val afterAng = indexAfterMatching(idx, "<", ">")
                if (afterAng < 0) return false
                idx = afterAng
            }
            if (peek(idx).text != "(") return false
            val afterPar = indexAfterMatching(idx, "(", ")")
            if (afterPar < 0) return false
            return scanReturnEndsWithSemicolon(afterPar)
        }

        private fun looksLikeMethodSignatureWithoutBody(identOffset: Int): Boolean {
            var idx = identOffset + 1
            if (peek(idx).text == "<") {
                val afterAng = indexAfterMatching(idx, "<", ">")
                if (afterAng < 0) return false
                idx = afterAng
            }
            if (peek(idx).text != "(") return false
            val afterPar = indexAfterMatching(idx, "(", ")")
            if (afterPar < 0) return false
            return scanReturnEndsWithSemicolon(afterPar)
        }

        private fun scanReturnEndsWithSemicolon(startIdx: Int): Boolean {
            var idx = startIdx
            if (peek(idx).text == ":") {
                idx++
                var angle = 0; var paren = 0; var brace = 0; var bracket = 0
                while (idx < startIdx + 500) {
                    val t = peek(idx)
                    if (t.type == TokType.EOF) return true
                    if (angle == 0 && paren == 0 && brace == 0 && bracket == 0) {
                        if (t.text == ";") return true
                        if (t.text == "{") {
                            val afterB = indexAfterMatching(idx, "{", "}")
                            if (afterB >= 0 && peek(afterB).text in setOf(";", "|", "&", "[")) {
                                idx = afterB
                                continue
                            }
                            return false
                        }
                        if (t.leadingWs.contains('\n') && t.text !in setOf("|", "&", "is")) return true
                    }
                    when (t.text) {
                        "<" -> angle++
                        ">" -> if (angle > 0) angle--
                        ">>" -> if (angle > 0) angle = (angle - 2).coerceAtLeast(0)
                        ">>>" -> if (angle > 0) angle = (angle - 3).coerceAtLeast(0)
                        "(" -> paren++
                        ")" -> if (paren > 0) paren-- else return false
                        "{" -> brace++
                        "}" -> if (brace > 0) brace-- else return false
                        "[" -> bracket++
                        "]" -> if (bracket > 0) bracket-- else return false
                    }
                    idx++
                }
            }
            return peek(idx).text == ";"
        }

        private fun skipFunctionOverloadSignature() {
            next() // function
            if (peek().text == "*") next()
            if (peek().type == TokType.IDENT) next()
            if (peek().text == "<") skipBalancedAngles()
            if (peek().text == "(") skipBalanced("(", ")")
            if (peek().text == ":") {
                next()
                skipTypeExpression(stopAt = setOf(";", "{"))
            }
            if (peek().text == ";") next()
        }

        private fun skipMethodSignatureWithoutBody() {
            if (peek().type == TokType.IDENT) next()
            if (peek().text == "<") skipBalancedAngles()
            if (peek().text == "(") skipBalanced("(", ")")
            if (peek().text == ":") {
                next()
                skipTypeExpression(stopAt = setOf(";", "{"))
            }
            if (peek().text == ";") next()
        }

        private fun transformParamList(out: StringBuilder, isConstructor: Boolean = false) {
            if (peek().text != "(") return
            emitToken(out, next()) // (
            var pDepth = 1
            var bDepth = 0
            var brDepth = 0
            var inDefaultExpr = false
            val ctorProps = if (isConstructor) mutableListOf<String>() else null
            while (!isEof() && pDepth > 0) {
                val t = peek()
                when {
                    t.text == "(" -> {
                        pDepth++
                        emitToken(out, next())
                    }
                    t.text == ")" -> {
                        pDepth--
                        emitToken(out, next())
                    }
                    t.text == "{" -> {
                        bDepth++
                        emitToken(out, next())
                    }
                    t.text == "}" -> {
                        if (bDepth > 0) bDepth--
                        emitToken(out, next())
                    }
                    t.text == "[" -> {
                        brDepth++
                        emitToken(out, next())
                    }
                    t.text == "]" -> {
                        if (brDepth > 0) brDepth--
                        emitToken(out, next())
                    }
                    pDepth == 1 && bDepth == 0 && brDepth == 0 && t.text == "=" -> {
                        inDefaultExpr = true
                        emitToken(out, next())
                    }
                    pDepth == 1 && bDepth == 0 && brDepth == 0 && t.text == "," -> {
                        inDefaultExpr = false
                        emitToken(out, next())
                    }
                    pDepth == 1 && bDepth == 0 && brDepth == 0 && !inDefaultExpr && isTs && t.text == "?" &&
                        (peek(1).text == ":" || peek(1).text == "," || peek(1).text == ")" || peek(1).text == "=") -> {
                        next() // strip optional `?`
                    }
                    pDepth == 1 && bDepth == 0 && brDepth == 0 && !inDefaultExpr && isTs && t.text == ":" -> {
                        next() // consume ':'
                        skipTypeExpression(stopAt = setOf("=", ",", ")"))
                    }
                    pDepth == 1 && bDepth == 0 && brDepth == 0 && !inDefaultExpr && isTs && t.type == TokType.IDENT &&
                        t.text in setOf("public", "private", "protected", "readonly") &&
                        (peek(1).type == TokType.IDENT || peek(1).text == "readonly") -> {
                        next() // strip visibility/readonly modifier
                        if (peek().text == "readonly" && peek(1).type == TokType.IDENT) next()
                        if (ctorProps != null && peek().type == TokType.IDENT) {
                            ctorProps.add(peek().text)
                        }
                    }
                    else -> transformNextItem(out, lastMeaningfulToken())
                }
            }
            if (!ctorProps.isNullOrEmpty() && peek().text == "{") {
                pendingCtorAssignProps = ctorProps
            }
        }

        private fun skipOptionalReturnType(isArrow: Boolean = false) {
            if (!isTs || peek().text != ":") return
            next()
            if (isArrow) {
                skipTypeExpression(stopAt = setOf("=>", ";"), allowObjectReturnBefore = setOf("=>"))
            } else {
                skipTypeExpression(stopAt = setOf("{", "=>", ";"), allowObjectReturnBefore = setOf("{"))
            }
        }

        private fun shouldStripTypeColon(prevToken: Tok?): Boolean {
            if (prevToken == null) return false
            if (prevToken.type != TokType.IDENT && prevToken.text != "}" && prevToken.text != "]") return false
            // 1. Variable declaration `const/let/var x: Type = ...` BEFORE `=`
            if (varDeclActive && !varDeclAfterEquals && currentDepthSum() == varDeclBaseDepth) {
                return true
            }
            // 2. Class field declaration `class C { speed: number = 0; name: string; }`
            if (braceKinds.lastOrNull() == BraceKind.CLASS_BODY && parenDepth == 0 && bracketDepth == 0 &&
                !classFieldAfterEquals && classTernaryDepth == 0
            ) {
                val after = peek(1)
                if (after.type == TokType.IDENT || after.type == TokType.STRING || after.text in setOf("{", "(", "[")) {
                    return true
                }
            }
            return false
        }

        private fun skipTypeExpression(stopAt: Set<String>, allowObjectReturnBefore: Set<String> = emptySet()) {
            var angle = 0
            var paren = 0
            var brace = 0
            var bracket = 0
            var prevTokText = ":"
            while (!isEof()) {
                val t = peek()
                if (angle == 0 && paren == 0 && brace == 0 && bracket == 0) {
                    if (t.text == "{" && allowObjectReturnBefore.isNotEmpty()) {
                        val afterObj = indexAfterMatching(0, "{", "}")
                        if (afterObj > 0 && peek(afterObj).text in allowObjectReturnBefore) {
                            // Object return type `{ a: number }` before `{` body or `=>`
                            brace++
                            prevTokText = t.text
                            next()
                            continue
                        }
                    }
                    if (t.text in stopAt) break
                    if (t.leadingWs.contains('\n') &&
                        prevTokText !in setOf(":", "|", "&", "=>", "<", "(", "[", "{", ",", "?", "extends", "is") &&
                        t.text !in setOf("|", "&", "=>", "{", "(", "[", "extends", "is", "=")
                    ) {
                        break
                    }
                }
                when (t.text) {
                    "<" -> angle++
                    ">" -> if (angle > 0) angle-- else if (angle == 0 && t.text in stopAt) break
                    ">>" -> if (angle > 0) angle = (angle - 2).coerceAtLeast(0) else if (t.text in stopAt) break
                    ">>>" -> if (angle > 0) angle = (angle - 3).coerceAtLeast(0) else if (t.text in stopAt) break
                    "(" -> paren++
                    ")" -> if (paren > 0) paren-- else break
                    "{" -> brace++
                    "}" -> if (brace > 0) brace-- else break
                    "[" -> bracket++
                    "]" -> if (bracket > 0) bracket-- else break
                }
                prevTokText = t.text
                next()
            }
        }

        private fun splitByComma(items: List<Tok>): List<List<Tok>> {
            val res = mutableListOf<List<Tok>>()
            var cur = mutableListOf<Tok>()
            for (t in items) {
                if (t.text == ",") {
                    if (cur.isNotEmpty()) res.add(cur)
                    cur = mutableListOf()
                } else {
                    cur.add(t)
                }
            }
            if (cur.isNotEmpty()) res.add(cur)
            return res
        }

        // --- Source-Accurate JSX / TSX Lowering ---

        private fun isJsxStartContext(prev: Tok?): Boolean {
            if (prev == null) return true
            if (prev.type == TokType.TEMPLATE_HEAD || prev.type == TokType.TEMPLATE_MID) return true
            if (prev.type == TokType.IDENT) {
                return prev.text in setOf("return", "throw", "case", "default", "await", "yield", "else", "do")
            }
            if (prev.type == TokType.PUNCT) {
                return prev.text in setOf("=>", "(", "[", "{", ",", ":", "?", "=", "&&", "||", "??", "!", ";", "+")
            }
            return false
        }

        private fun looksLikeJsxOpen(ltOffset: Int): Boolean {
            if (ltOffset >= src.length || src[ltOffset] != '<') return false
            if (ltOffset + 1 >= src.length) return false
            val c1 = src[ltOffset + 1]
            return c1 == '>' || c1.isLetter() || c1 == '_' || c1 == '$'
        }

        private fun parseJsxElementFromSrc(startLtOffset: Int): Pair<String, Int> {
            usedReactCreateElement = true
            var p = startLtOffset + 1 // past '<'
            val n = src.length

            fun skipJsxWs() {
                while (p < n) {
                    if (src[p].isWhitespace()) {
                        p++
                    } else if (src[p] == '/' && p + 1 < n && src[p + 1] == '/') {
                        p += 2
                        while (p < n && src[p] != '\n') p++
                    } else if (src[p] == '/' && p + 1 < n && src[p + 1] == '*') {
                        p += 2
                        while (p + 1 < n && !(src[p] == '*' && src[p + 1] == '/')) p++
                        if (p + 1 < n) p += 2 else p = n
                    } else {
                        break
                    }
                }
            }

            fun readTagName(): String {
                val start = p
                while (p < n && (src[p].isLetterOrDigit() || src[p] == '_' || src[p] == '$' || src[p] == '.' || src[p] == '-')) {
                    p++
                }
                return src.substring(start, p)
            }

            fun readAttrName(): String {
                val start = p
                while (p < n && (src[p].isLetterOrDigit() || src[p] == '_' || src[p] == '$' || src[p] == '-' || src[p] == ':')) {
                    p++
                }
                return src.substring(start, p)
            }

            val isFragment = p < n && src[p] == '>'
            val tagExpr: String
            if (isFragment) {
                p++ // consume '>'
                tagExpr = "React.Fragment"
            } else {
                val tagName = readTagName()
                tagExpr = if (tagName.isNotEmpty() && tagName.first().isLowerCase() && !tagName.contains('.')) {
                    "\"$tagName\""
                } else {
                    tagName.ifBlank { "\"div\"" }
                }
                // Skip generic type args on JSX tag e.g. <Select<Option> value={x} />
                skipJsxWs()
                if (p < n && src[p] == '<') {
                    p++
                    var aDepth = 1
                    while (p < n && aDepth > 0) {
                        if (src[p] == '=' && p + 1 < n && src[p + 1] == '>') {
                            p += 2
                            continue
                        }
                        if (src[p] == '<') aDepth++
                        else if (src[p] == '>') aDepth--
                        else if (src[p] == '\'' || src[p] == '"') {
                            val q = src[p++]
                            while (p < n && src[p] != q) {
                                if (src[p] == '\\' && p + 1 < n) p += 2 else p++
                            }
                        }
                        p++
                    }
                }
            }

            // Parse attributes
            val propsParts = mutableListOf<String>()
            var hasSpread = false
            while (!isFragment && p < n) {
                skipJsxWs()
                if (p >= n) break
                if (src[p] == '/' && p + 1 < n && src[p + 1] == '>') {
                    p += 2
                    val propsArg = formatPropsArg(propsParts, hasSpread)
                    return "React.createElement($tagExpr, $propsArg)" to p
                }
                if (src[p] == '>') {
                    p++
                    break
                }
                if (src[p] == '{') {
                    val (exprRaw, nextP) = transpileBraceBlockFromSrc(p)
                    p = nextP
                    val trimmed = exprRaw.trim()
                    if (trimmed.startsWith("...")) {
                        hasSpread = true
                        propsParts.add(trimmed)
                    }
                    continue
                }
                if (src[p].isLetter() || src[p] == '_' || src[p] == '$') {
                    val attrName = readAttrName()
                    skipJsxWs()
                    val safeKey = if (attrName.contains('-') || attrName.contains(':')) "\"$attrName\"" else attrName
                    if (p < n && src[p] == '=') {
                        p++ // consume '='
                        skipJsxWs()
                        val valExpr = when {
                            p < n && (src[p] == '"' || src[p] == '\'') -> {
                                val quote = src[p++]
                                val sb = StringBuilder()
                                while (p < n && src[p] != quote) {
                                    if (src[p] == '\\' && p + 1 < n) {
                                        val esc = src[p + 1]
                                        when (esc) {
                                            'n' -> sb.append('\n')
                                            'r' -> sb.append('\r')
                                            't' -> sb.append('\t')
                                            '\\' -> sb.append('\\')
                                            '\'' -> sb.append('\'')
                                            '"' -> sb.append('"')
                                            else -> sb.append(esc)
                                        }
                                        p += 2
                                    } else {
                                        sb.append(src[p++])
                                    }
                                }
                                if (p < n && src[p] == quote) p++
                                "\"${escapeJsString(sb.toString())}\""
                            }
                            p < n && src[p] == '{' -> {
                                val (exprRaw, nextP) = transpileBraceBlockFromSrc(p)
                                p = nextP
                                exprRaw.trim().ifEmpty { "true" }
                            }
                            else -> "true"
                        }
                        propsParts.add("$safeKey: $valExpr")
                    } else {
                        propsParts.add("$safeKey: true")
                    }
                } else {
                    p++
                }
            }

            // Parse JSX children directly from `src` so apostrophes, quotes, and slashes never corrupt tokenization
            val children = mutableListOf<String>()
            val textBuf = StringBuilder()

            fun flushText() {
                if (textBuf.isEmpty()) return
                val raw = textBuf.toString()
                textBuf.clear()
                val cleaned = decodeHtmlEntities(cleanJsxText(raw))
                if (cleaned.isNotEmpty()) {
                    children.add("\"${escapeJsString(cleaned)}\"")
                }
            }

            while (p < n) {
                val c = src[p]
                if (c == '<' && p + 1 < n && src[p + 1] == '/') {
                    flushText()
                    p += 2 // past '</'
                    while (p < n && src[p] != '>') p++
                    if (p < n && src[p] == '>') p++
                    break
                }
                if (c == '<' && looksLikeJsxOpen(p)) {
                    flushText()
                    val (childJsx, nextP) = parseJsxElementFromSrc(p)
                    children.add(childJsx)
                    p = nextP
                    continue
                }
                if (c == '{') {
                    flushText()
                    val (exprRaw, nextP) = transpileBraceBlockFromSrc(p)
                    p = nextP
                    val trimmed = exprRaw.trim()
                    if (trimmed.isNotEmpty()) {
                        children.add(trimmed)
                    }
                    continue
                }
                textBuf.append(c)
                p++
            }
            flushText()

            val propsArg = formatPropsArg(propsParts, hasSpread)
            val result = if (children.isEmpty()) {
                "React.createElement($tagExpr, $propsArg)"
            } else {
                "React.createElement($tagExpr, $propsArg, ${children.joinToString(", ")})"
            }
            return result to p
        }

        private fun transpileBraceBlockFromSrc(openBraceOffset: Int): Pair<String, Int> {
            val sub = Transpiler(
                src = src,
                baseOffset = openBraceOffset + 1,
                relPath = relPath,
                entryDirRel = entryDirRel,
                rootDir = rootDir,
                allFiles = allFiles,
                isTs = isTs,
                isJsx = isJsx,
                stopOnUnmatchedCloseBrace = true
            )
            val transpiled = sub.transformProgram()
            if (sub.usedReactCreateElement) usedReactCreateElement = true
            return transpiled to sub.endOffsetAfterStop
        }

        private fun formatPropsArg(parts: List<String>, hasSpread: Boolean): String {
            if (parts.isEmpty()) return "null"
            return if (hasSpread) {
                val args = mutableListOf<String>()
                val currentObj = mutableListOf<String>()
                for (p in parts) {
                    if (p.startsWith("...")) {
                        if (currentObj.isNotEmpty()) {
                            args.add("{ ${currentObj.joinToString(", ")} }")
                            currentObj.clear()
                        }
                        args.add(p.removePrefix("..."))
                    } else {
                        currentObj.add(p)
                    }
                }
                if (currentObj.isNotEmpty()) {
                    args.add("{ ${currentObj.joinToString(", ")} }")
                }
                "Object.assign({}, ${args.joinToString(", ")})"
            } else {
                "{ ${parts.joinToString(", ")} }"
            }
        }

        private fun cleanJsxText(raw: String): String {
            if (!raw.contains('\n') && !raw.contains('\r')) return raw
            val lines = raw.replace("\r", "").split('\n')
            val processed = lines.mapIndexed { idx, line ->
                var s = line
                if (idx > 0) s = s.trimStart()
                if (idx < lines.lastIndex) s = s.trimEnd()
                s
            }.filter { it.isNotEmpty() }
            return processed.joinToString(" ")
        }

        private fun decodeHtmlEntities(s: String): String {
            if (!s.contains('&')) return s
            return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&nbsp;", "\u00A0")
        }

        private fun escapeJsString(s: String): String {
            return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")
        }

        private fun isJsIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

        // --- Module Specifier Resolution ---

        private fun rewriteModuleSpecifier(spec: String): String {
            val clean = spec.trim().substringBefore('?').substringBefore('#')
            if (!clean.startsWith(".") && !clean.startsWith("/") && !clean.startsWith("@/")) {
                return spec.trim()
            }
            val currentDir = relPath.substringBeforeLast('/', "")
            val candidateRel = when {
                clean.startsWith("@/") -> {
                    val sub = clean.removePrefix("@/")
                    if ("src/$sub" in allFiles || hasAnyExt("src/$sub")) "src/$sub" else sub
                }
                clean.startsWith("/") -> {
                    val sub = clean.removePrefix("/")
                    if (resolveOnDisk(sub) != null) sub else "src/$sub"
                }
                else -> normalizeRelPath(if (currentDir.isEmpty()) clean else "$currentDir/$clean")
            }

            val resolved = resolveOnDisk(candidateRel)
            if (resolved == null) {
                if (clean.lowercase(Locale.US).endsWith(".css")) {
                    val missingCssProxy = "_missing_css_proxy.js"
                    File(rootDir, missingCssProxy).writeText("export default '';\n", StandardCharsets.UTF_8)
                    return relativeImportBetween(currentDir, missingCssProxy)
                }
                return clean
            }
            val ext = resolved.substringAfterLast('.', "").lowercase(Locale.US)

            if (ext in CSS_EXTS) {
                val cssProxyRel = "$resolved.css-proxy.js"
                val cssProxyFile = File(rootDir, cssProxyRel)
                cssProxyFile.parentFile?.mkdirs()
                val href = relativeImportBetween(entryDirRel, resolved)
                cssProxyFile.writeText(
                    """
                    (function(){
                      const href = "$href";
                      if (!document.querySelector('link[data-studio-css="' + href + '"]')) {
                        const l = document.createElement('link');
                        l.rel = 'stylesheet';
                        l.href = href;
                        l.setAttribute('data-studio-css', href);
                        document.head.appendChild(l);
                      }
                    })();
                    export default "$href";
                    """.trimIndent(),
                    StandardCharsets.UTF_8
                )
                return relativeImportBetween(currentDir, cssProxyRel)
            }

            if (ext in ASSET_EXTS) {
                val assetProxyRel = "$resolved.asset-proxy.js"
                val assetProxyFile = File(rootDir, assetProxyRel)
                assetProxyFile.parentFile?.mkdirs()
                if (ext == "json") {
                    val jsonBody = File(rootDir, resolved).readText(StandardCharsets.UTF_8).trim().ifEmpty { "{}" }
                    assetProxyFile.writeText("export default $jsonBody;\n", StandardCharsets.UTF_8)
                } else {
                    val href = relativeImportBetween(entryDirRel, resolved)
                    assetProxyFile.writeText("export default \"$href\";\n", StandardCharsets.UTF_8)
                }
                return relativeImportBetween(currentDir, assetProxyRel)
            }

            val finalTarget = if (ext in setOf("ts", "tsx", "jsx")) {
                resolved.replace(Regex("""\.(tsx|ts|jsx)$""", RegexOption.IGNORE_CASE), ".js")
            } else {
                resolved
            }
            return relativeImportBetween(currentDir, finalTarget)
        }

        private fun hasAnyExt(base: String): Boolean {
            return listOf(".tsx", ".ts", ".jsx", ".js").any { "$base$it" in allFiles }
        }

        private fun resolveOnDisk(candidate: String): String? {
            if (candidate in allFiles) return candidate
            val exts = listOf(".tsx", ".ts", ".jsx", ".js", ".mjs", ".css", ".json")
            for (ext in exts) {
                if ("$candidate$ext" in allFiles) return "$candidate$ext"
            }
            for (ext in exts) {
                if ("$candidate/index$ext" in allFiles) return "$candidate/index$ext"
            }
            return null
        }

        private fun normalizeRelPath(path: String): String {
            val parts = ArrayDeque<String>()
            for (seg in path.split('/')) {
                when {
                    seg.isEmpty() || seg == "." -> Unit
                    seg == ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else -> parts.addLast(seg)
                }
            }
            return parts.joinToString("/")
        }

        private fun relativeImportBetween(fromDir: String, targetPath: String): String {
            val fromSegs = if (fromDir.isEmpty()) emptyList() else fromDir.split('/')
            val toSegs = targetPath.split('/')
            var common = 0
            while (common < fromSegs.size && common < toSegs.size && fromSegs[common] == toSegs[common]) {
                common++
            }
            val ups = fromSegs.size - common
            val prefix = if (ups == 0) "./" else "../".repeat(ups)
            return prefix + toSegs.drop(common).joinToString("/")
        }
    }
}
