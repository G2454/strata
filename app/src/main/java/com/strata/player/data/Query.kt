package com.strata.player.data

/**
 * foobar2000-flavoured query language, kept small and predictable:
 *   plain words        match title / artist / album / genre / composer / codec / path
 *   field:value        contains (case-insensitive); value may list alternatives a|b|c
 *   field=value        same as ':'
 *   field>n field>=n field<n field<=n   numeric comparisons
 *   -word / -field:value                negation
 *   "quoted words"     phrase match
 * Fields: title artist album albumartist genre composer format codec bits rate (kHz) year kbps
 *         rating plays length (seconds) path folder fav
 */
class Query private constructor(private val terms: List<Term>) {

    private data class Term(val field: String?, val op: String, val value: String, val negate: Boolean)

    val isEmpty: Boolean get() = terms.isEmpty()

    interface Fields {
        fun rating(t: Track): Int
        fun plays(t: Track): Int
        fun tech(t: Track): Tech?
        fun favorite(t: Track): Boolean
    }

    fun matches(t: Track, f: Fields): Boolean = terms.all { term ->
        val r = matchTerm(term, t, f)
        if (term.negate) !r else r
    }

    private fun matchTerm(term: Term, t: Track, f: Fields): Boolean {
        val field = term.field
        if (field == null) {
            val hay = "${t.title} ${t.artist} ${t.album} ${t.albumArtist} ${t.genre} ${t.composer} ${t.codec} ${t.path}".lowercase()
            return hay.contains(term.value)
        }
        val value: Any = when (field) {
            "title" -> t.title
            "artist" -> t.artist
            "album" -> t.album
            "albumartist" -> t.albumArtist
            "genre" -> t.genre
            "composer" -> t.composer
            "format", "codec" -> t.codec
            "bits" -> f.tech(t)?.bits ?: if (t.lossless) 16 else 0
            "rate" -> (f.tech(t)?.sampleRate ?: 0) / 1000.0
            "year", "date" -> t.year
            "kbps", "bitrate" -> t.bitrateBps / 1000
            "rating" -> f.rating(t)
            "plays", "playcount" -> f.plays(t)
            "length", "duration" -> t.durationMs / 1000
            "path" -> t.path
            "folder" -> t.folder
            "fav", "favorite" -> if (f.favorite(t)) 1 else 0
            else -> return false
        }
        return when (term.op) {
            ":", "=" -> term.value.split('|').any { alt ->
                val s = if (value is Double) trimNum(value) else value.toString()
                if (value is Number) s == alt || s.startsWith("$alt.") else s.lowercase().contains(alt)
            }
            else -> {
                val a = (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull() ?: return false
                val b = term.value.toDoubleOrNull() ?: return false
                when (term.op) {
                    ">" -> a > b
                    ">=" -> a >= b
                    "<" -> a < b
                    "<=" -> a <= b
                    else -> false
                }
            }
        }
    }

    private fun trimNum(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    companion object {
        private val fieldRe = Regex("^([a-z]+)(>=|<=|:|=|>|<)(.+)$")

        fun parse(input: String): Query {
            val tokens = ArrayList<String>()
            val sb = StringBuilder()
            var inQuote = false
            for (ch in input.trim()) {
                when {
                    ch == '"' -> inQuote = !inQuote
                    ch.isWhitespace() && !inQuote -> {
                        if (sb.isNotEmpty()) tokens += sb.toString(); sb.clear()
                    }
                    else -> sb.append(ch)
                }
            }
            if (sb.isNotEmpty()) tokens += sb.toString()
            val terms = tokens.map { raw ->
                var tok = raw.lowercase()
                var neg = false
                if (tok.length > 1 && tok.startsWith("-")) {
                    neg = true; tok = tok.substring(1)
                }
                val m = fieldRe.find(tok)
                if (m != null) Term(m.groupValues[1], m.groupValues[2], m.groupValues[3], neg)
                else Term(null, ":", tok, neg)
            }
            return Query(terms)
        }
    }
}
