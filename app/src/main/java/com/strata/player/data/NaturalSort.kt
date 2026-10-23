package com.strata.player.data

/**
 * "Natural" text order, like foobar2000 mobile 2.25 and most file managers:
 * case-insensitive, and runs of digits compare as numbers, so "Track 2" comes before "Track 10".
 */
object Natural : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // Skip leading zeros, then the longer number is bigger; equal length compares digit by digit.
                var si = i
                while (si < a.length && a[si] == '0') si++
                var sj = j
                while (sj < b.length && b[sj] == '0') sj++
                var ei = si
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = sj
                while (ej < b.length && b[ej].isDigit()) ej++
                val la = ei - si
                val lb = ej - sj
                if (la != lb) return la - lb
                for (k in 0 until la) {
                    val d = a[si + k] - b[sj + k]
                    if (d != 0) return d
                }
                // "007" vs "7": fewer leading zeros first, so the order is stable.
                val za = si - i
                val zb = sj - j
                if (za != zb) return za - zb
                i = ei
                j = ej
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la - lb
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        if (rest != 0) return rest
        return a.compareTo(b) // only differs in case: keep a fixed order
    }

    /** Sort key helper for `sortedWith(compareBy(Natural) { … })`. */
    fun <T> by(selector: (T) -> String): Comparator<T> = Comparator { x, y -> compare(selector(x), selector(y)) }
}
