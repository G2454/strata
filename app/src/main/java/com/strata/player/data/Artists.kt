package com.strata.player.data

/**
 * Splits artist credits like "Eminem feat. Rihanna" or "Eminem & Dr. Dre" into separate artists, so a
 * track shows up on the page of everyone who performs on it.
 *
 * Two kinds of separators:
 *  - Strong ones always split: ";", " / ", a NUL (multi-value tags), and "feat." / "ft." / "featuring",
 *    both in the artist field and in the title ("Song (feat. X)", "Song [with X]").
 *  - Weak ones ("," "&" "and" "x" "+" "with" "vs" and a bare "/") appear inside real band names too
 *    (Simon & Garfunkel, AC/DC, Earth, Wind & Fire). They only split when at least one of the pieces is an
 *    artist that is already credited on its own somewhere in the library. Inside a "feat." clause they
 *    always split, because those are lists of guests.
 *
 * Names in [keepTogether] (and a built-in list of well-known bands) are never split.
 */
class ArtistSplitter(
    val enabled: Boolean = true,
    keepTogether: Collection<String> = emptyList(),
) {
    private val keep: Set<String> = (BUILT_IN_KEEP + keepTogether).map(::norm).filter { it.isNotEmpty() }.toSet()

    /** Lowercased names that are credited alone somewhere; filled by [learn]. */
    private val known = HashSet<String>()

    /** Most common spelling for each lowercased name. */
    private val spellings = HashMap<String, HashMap<String, Int>>()

    /** First pass over the library: remember every name that is credited on its own. */
    fun learn(tracks: List<Track>) {
        if (!enabled) return
        for (t in tracks) {
            for (field in listOf(t.artist, t.albumArtist)) {
                val (main, guests) = strongSplit(field)
                for (m in main) if (!hasWeak(m)) addKnown(m)
                for (g in guests) splitWeak(g, force = true).forEach(::addKnown)
            }
            for (g in titleGuests(t.title)) splitWeak(g, force = true).forEach(::addKnown)
        }
    }

    private fun addKnown(name: String) {
        val n = norm(name)
        if (n.isEmpty() || n == "<unknown>" || n == "unknown artist") return
        known += n
        val clean = clean(name)
        spellings.getOrPut(n) { HashMap() }.merge(clean, 1, Int::plus)
    }

    /** Every artist performing on [t], primary artist first. */
    fun artistsOf(t: Track): List<String> {
        if (!enabled) return listOf(t.artist)
        val out = LinkedHashMap<String, String>()
        fun add(name: String) {
            val n = norm(name)
            if (n.isEmpty()) return
            if (n !in out) out[n] = display(name)
        }
        val (main, guests) = strongSplit(t.artist)
        main.forEach { m -> splitWeak(m, force = false).forEach(::add) }
        guests.forEach { g -> splitWeak(g, force = true).forEach(::add) }
        titleGuests(t.title).forEach { g -> splitWeak(g, force = true).forEach(::add) }
        return if (out.isEmpty()) listOf(t.artist) else out.values.toList()
    }

    /** Splits a single credit string (no title) — handy for tests and the tag editor preview. */
    fun split(credit: String): List<String> {
        val (main, guests) = strongSplit(credit)
        val out = LinkedHashMap<String, String>()
        main.forEach { m -> splitWeak(m, false).forEach { out.putIfAbsent(norm(it), display(it)) } }
        guests.forEach { g -> splitWeak(g, true).forEach { out.putIfAbsent(norm(it), display(it)) } }
        out.remove("")
        return out.values.toList().ifEmpty { listOf(credit) }
    }

    private val displayCache = HashMap<String, String>()

    private fun display(name: String): String {
        val n = norm(name)
        return displayCache.getOrPut(n) { spellings[n]?.maxByOrNull { it.value }?.key ?: clean(name) }
    }

    /** Main credits and guest lists ("feat." clauses) of an artist field. */
    private fun strongSplit(field: String): Pair<List<String>, List<String>> {
        if (field.isBlank()) return emptyList<String>() to emptyList()
        val guests = ArrayList<String>()
        var s = field
        // "(feat. X)" / "[ft. X]" anywhere in the field
        s = FEAT_BRACKET.replace(s) { guests += it.groupValues[1]; " " }
        // "A feat. B" — everything after the first feat keyword is a guest list (which may contain more feats)
        val m = FEAT_INLINE.find(s)
        if (m != null) {
            FEAT_INLINE.split(s.substring(m.range.first)).filter { it.isNotBlank() }.forEach { guests += it }
            s = s.substring(0, m.range.first)
        }
        val main = STRONG.split(s).map { it.trim() }.filter { it.isNotEmpty() }
        val g = guests.flatMap { STRONG.split(it) }.map { it.trim() }.filter { it.isNotEmpty() }
        return main to g
    }

    /** Guests credited in a title: "Song (feat. X & Y)", "Song [with X]", "Song ft. X". */
    private fun titleGuests(title: String): List<String> {
        val out = ArrayList<String>()
        FEAT_BRACKET_TITLE.findAll(title).forEach { out += it.groupValues[1] }
        if (out.isEmpty()) FEAT_TITLE_TAIL.find(title)?.let { out += it.groupValues[1] }
        return out.flatMap { STRONG.split(it) }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun hasWeak(s: String) = WEAK.containsMatchIn(s)

    /**
     * Splits on weak separators, keeping multi-piece names together when the joined text is a name we know
     * ("Tyler, The Creator", "Earth, Wind & Fire") or looks like "X & the Y".
     */
    private fun splitWeak(chunk: String, force: Boolean): List<String> {
        val text = clean(chunk)
        if (text.isEmpty()) return emptyList()
        if (norm(text) in keep) return listOf(text)
        val pieces = ArrayList<String>()
        val seps = ArrayList<String>()
        var last = 0
        for (m in WEAK.findAll(text)) {
            pieces += text.substring(last, m.range.first).trim()
            seps += m.value
            last = m.range.last + 1
        }
        pieces += text.substring(last).trim()
        if (pieces.size == 1) return listOf(text)

        fun joined(i: Int, j: Int): String {
            val sb = StringBuilder(pieces[i])
            for (k in i + 1 until j) sb.append(seps[k - 1]).append(pieces[k])
            return sb.toString().trim()
        }
        fun isName(s: String) = norm(s).let { it in known || it in keep }

        val groups = ArrayList<String>()
        var i = 0
        while (i < pieces.size) {
            var taken = false
            for (j in pieces.size downTo i + 2) {
                val cand = joined(i, j)
                if (isName(cand)) { groups += cand; i = j; taken = true; break }
            }
            if (taken) continue
            val p = pieces[i]
            val nextIsThe = i + 1 < pieces.size && seps[i].trim().lowercase() in AND_LIKE &&
                pieces[i + 1].lowercase().startsWith("the ") && !isName(pieces[i + 1])
            if (!isName(p) && nextIsThe) { groups += joined(i, i + 2); i += 2 }
            else { groups += p; i++ }
        }
        val cleaned = groups.map(::clean).filter { it.isNotEmpty() }
        if (cleaned.size <= 1) return listOf(text)
        if (!force && cleaned.none(::isName)) return listOf(text)
        return cleaned
    }

    companion object {
        private val FEAT_WORD = """(?:feat\.?|ft\.|featuring|feat)"""
        private val FEAT_BRACKET = Regex("""[(\[]\s*$FEAT_WORD\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)
        private val FEAT_BRACKET_TITLE = Regex("""[(\[]\s*(?:$FEAT_WORD|with)\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)
        private val FEAT_INLINE = Regex("""\s+$FEAT_WORD\s+""", RegexOption.IGNORE_CASE)
        private val FEAT_TITLE_TAIL = Regex("""\s(?:feat\.|ft\.|featuring)\s+(.+)$""", RegexOption.IGNORE_CASE)
        private val STRONG = Regex("""\s*(?:;|\u0000|\s/\s|\|)\s*""")
        private val WEAK = Regex("""\s*,\s*|\s+&\s+|\s+and\s+|\s+x\s+|\s+\+\s+|\s+with\s+|\s+vs\.?\s+|\s*/\s*""", RegexOption.IGNORE_CASE)
        private val AND_LIKE = setOf("&", "and", "+")

        fun norm(s: String) = clean(s).lowercase()
        private val SPACES = Regex("\\s+")
        fun clean(s: String) = s.replace(SPACES, " ").trim().trim(',', '&', '+', '/').trim()

        /** Bands whose names contain a separator. Users can add more in Settings. */
        val BUILT_IN_KEEP = listOf(
            "AC/DC", "Simon & Garfunkel", "Earth, Wind & Fire", "Tyler, The Creator", "Crosby, Stills, Nash & Young",
            "Crosby, Stills & Nash", "Emerson, Lake & Palmer", "Hall & Oates", "Daryl Hall & John Oates", "Florence + the Machine",
            "Mumford & Sons", "Of Monsters and Men", "Peter, Bjorn and John", "Iron & Wine", "Belle & Sebastian",
            "Brooks & Dunn", "Kool & the Gang", "Sly & the Family Stone", "Marina and the Diamonds", "Echo & the Bunnymen",
            "Siouxsie and the Banshees", "Huey Lewis and the News", "Bob Marley & the Wailers", "Tom Petty and the Heartbreakers",
            "Prince and the Revolution", "Hootie & the Blowfish", "Edward Sharpe and the Magnetic Zeros", "Florence and the Machine",
            "Nick Cave and the Bad Seeds", "Bruce Springsteen & the E Street Band", "Booker T. & the M.G.'s", "Sam & Dave",
            "Ike & Tina Turner", "Peaches & Herb", "Salt-N-Pepa", "Big & Rich", "Dan + Shay", "Macklemore & Ryan Lewis",
            "Chase & Status", "Above & Beyond", "Years & Years", "Angus & Julia Stone", "She & Him", "Matt and Kim",
            "Captain & Tennille", "Love and Rockets", "Me First and the Gimme Gimmes", "Rage Against the Machine",
            "Tears for Fears", "Blood, Sweat & Tears", "Derek and the Dominos", "Martha and the Vandellas",
            "Smokey Robinson & the Miracles", "Diana Ross & the Supremes", "Gladys Knight & the Pips", "Hank Williams & His Drifting Cowboys",
            "Lyle Lovett and His Large Band", "Ladysmith Black Mambazo", "Coheed and Cambria", "Of Mice & Men",
            "Thompson Twins", "Women & Children", "Rocket from the Crypt", "The Mamas & the Papas", "Up, Bustle and Out",
            "Bachman-Turner Overdrive", "Herb Alpert & the Tijuana Brass", "Sergio Mendes & Brasil '66", "K.C. & the Sunshine Band",
            "KC and the Sunshine Band", "Sonny & Cher", "Ashford & Simpson", "Zager & Evans", "Jan & Dean", "Flatt & Scruggs",
            "Jay & the Americans", "Gerry & the Pacemakers", "Freddie & the Dreamers", "Paul Revere & the Raiders", "Sam the Sham & the Pharaohs",
            "Tom Tom Club", "Simon and Garfunkel", "Earth Wind & Fire", "Tyler the Creator", "Hall and Oates", "Mumford and Sons",
        )

        // Declared last: it needs the lists above to be initialized first.
        val DISABLED = ArtistSplitter(enabled = false)
    }
}
