package com.example.card_scanner

import java.util.Calendar

/**
 * Kotlin port of `lib/src/card_parser.dart` (the reference implementation).
 * Must pass `test_vectors/card_parser_vectors.json`.
 */
data class CardExpiry(val month: Int, val year: Int)

data class FrameParseResult(
    val number: String? = null,
    val expiry: CardExpiry? = null,
    val holderName: String? = null,
)

enum class CardBrand {
    VISA, MASTERCARD, AMEX, TROY, DISCOVER, JCB, DINERS_CLUB, UNION_PAY, MAESTRO, UNKNOWN;

    fun isValidLength(length: Int): Boolean = when (this) {
        AMEX -> length == 15
        DINERS_CLUB -> length in 14..19
        VISA -> length == 13 || length == 16 || length == 19
        MASTERCARD, TROY -> length == 16
        MAESTRO -> length in 12..19
        else -> length in 16..19
    }

    companion object {
        fun detect(digits: String): CardBrand {
            fun prefix(n: Int) = if (digits.length >= n) digits.substring(0, n).toInt() else -1
            if (digits.startsWith("9792")) return TROY
            if (digits.startsWith("4")) return VISA
            val p2 = prefix(2)
            val p3 = prefix(3)
            val p4 = prefix(4)
            val p6 = prefix(6)
            if (p2 == 34 || p2 == 37) return AMEX
            if (p2 in 51..55 || p6 in 222100..272099) return MASTERCARD
            if (p4 == 6011 || p2 == 65 || p3 in 644..649) return DISCOVER
            if (p4 in 3528..3589) return JCB
            if (p2 == 36 || p2 == 38 || p2 == 39 || p3 in 300..305) return DINERS_CLUB
            if (p2 == 62) return UNION_PAY
            if (p2 == 50 || p2 in 56..69) return MAESTRO
            return UNKNOWN
        }
    }
}

class CardParser(private val now: () -> Calendar = { Calendar.getInstance() }) {

    fun parse(lines: List<String>) = FrameParseResult(
        number = findCardNumber(lines),
        expiry = findExpiry(lines),
        holderName = findHolderName(lines),
    )

    // ---------------------------------------------------------------- number

    fun findCardNumber(lines: List<String>): String? {
        val candidates = lines + lines.zipWithNext { a, b -> "$a $b" }
        for (line in candidates) {
            extractNumber(line)?.let { return it }
        }
        return null
    }

    private fun extractNumber(line: String): String? {
        val groups = line.split(SEPARATOR).filter { it.isNotEmpty() }.map(::normalizeGroup)
        var best: String? = null
        for (i in groups.indices) {
            val joined = StringBuilder()
            for (j in i until groups.size) {
                val group = groups[j] ?: break
                joined.append(group)
                if (joined.length > 19) break
                val candidate = joined.toString()
                if (isPlausibleCardNumber(candidate) && candidate.length > (best?.length ?: 0)) {
                    best = candidate
                }
            }
        }
        return best
    }

    // ---------------------------------------------------------------- expiry

    fun findExpiry(lines: List<String>): CardExpiry? {
        val currentYear = now().get(Calendar.YEAR)
        var best: CardExpiry? = null
        for (line in lines) {
            val normalized = line.replace('O', '0').replace('o', '0')
            for (m in EXPIRY.findAll(normalized)) {
                val month = m.groupValues[1].toInt()
                var year = m.groupValues[2].toInt()
                if (year < 100) year += 2000
                if (year < currentYear - 1 || year > currentYear + 20) continue
                val candidate = CardExpiry(month, year)
                val b = best
                if (b == null || candidate.year > b.year ||
                    (candidate.year == b.year && candidate.month > b.month)
                ) {
                    best = candidate
                }
            }
        }
        return best
    }

    // ---------------------------------------------------------------- name

    fun findHolderName(lines: List<String>): String? {
        for (raw in lines) {
            val line = raw.trim().replace(WHITESPACE, " ")
            if (line.length !in 5..26) continue
            if (!NAME.matches(line)) continue
            val words = line.split(' ')
            if (words.size !in 2..4) continue
            if (words.any { it.length < 2 }) continue
            if (words.any { it.replace(".", "") in NAME_STOP_WORDS }) continue
            return line
        }
        return null
    }

    companion object {
        private val SEPARATOR = Regex("[\\s\\-]+")
        private val WHITESPACE = Regex("\\s+")
        private val DIGIT = Regex("\\d")
        private val ALL_DIGITS = Regex("^\\d+$")
        private val EXPIRY = Regex("(?<!\\d)(0[1-9]|1[0-2])\\s?[/\\\\\\-]\\s?(\\d{4}|\\d{2})(?!\\d)")
        private val NAME = Regex("^[A-ZÇĞİÖŞÜ][A-ZÇĞİÖŞÜ.'\\- ]+$")

        private val DIGIT_CONFUSIONS = mapOf(
            'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'U' to '0',
            'I' to '1', 'l' to '1', 'i' to '1', '|' to '1', 'L' to '1', 'T' to '1',
            'Z' to '2', 'z' to '2',
            'S' to '5', 's' to '5',
            'G' to '6', 'b' to '6',
            'B' to '8',
            'g' to '9', 'q' to '9',
        )

        private val NAME_STOP_WORDS = setOf(
            "VISA", "MASTERCARD", "MASTER", "CARD", "AMEX", "AMERICAN", "EXPRESS",
            "TROY", "MAESTRO", "DISCOVER", "JCB", "UNIONPAY", "DINERS", "CLUB",
            "DEBIT", "CREDIT", "PREPAID", "BUSINESS", "CORPORATE", "COMMERCIAL",
            "PLATINUM", "GOLD", "CLASSIC", "STANDARD", "WORLD", "ELITE", "SIGNATURE",
            "INFINITE", "ELECTRON", "PREMIER", "PREMIUM", "BLACK", "TITANIUM",
            "VALID", "THRU", "FROM", "UNTIL", "GOOD", "EXPIRES", "EXPIRY", "END",
            "MONTH", "YEAR", "MEMBER", "SINCE", "BANK", "BANKASI", "INTERNATIONAL",
            "CONTACTLESS", "ELECTRONIC", "USE", "ONLY", "AUTHORIZED",
            "KART", "KARTI", "BONUS", "AXESS", "MAXIMUM", "PARAF", "CARDFINANS",
            "ADVANTAGE", "MILES", "SMILES", "BANKKART", "WINGS", "SAGLAM",
            "CUSTOMER", "SERVICE", "MUSTERI", "HIZMETLERI", "HIZMETLER",
        )

        private fun normalizeGroup(token: String): String? {
            if (!DIGIT.containsMatchIn(token)) return null
            val fixed = token.map { DIGIT_CONFUSIONS[it] ?: it }.joinToString("")
            return if (ALL_DIGITS.matches(fixed)) fixed else null
        }

        fun isPlausibleCardNumber(digits: String): Boolean {
            if (digits.length !in 13..19) return false
            val brand = CardBrand.detect(digits)
            if (brand == CardBrand.UNKNOWN || !brand.isValidLength(digits.length)) return false
            return luhnValid(digits)
        }

        fun luhnValid(digits: String): Boolean {
            var sum = 0
            var doubleIt = false
            for (i in digits.indices.reversed()) {
                var d = digits[i] - '0'
                if (d !in 0..9) return false
                if (doubleIt) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
                doubleIt = !doubleIt
            }
            return sum % 10 == 0
        }
    }
}

/** Votes across frames and decides when the scan is locked. */
class ScanAggregator(
    private val wantsExpiry: Boolean = true,
    private val wantsName: Boolean = true,
    private val numberVotesRequired: Int = 3,
    private val expiryVotesRequired: Int = 2,
    private val nameVotesRequired: Int = 3,
    private val extraFieldsGraceMs: Long = 1500,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Result(
        val number: String,
        val expiry: CardExpiry?,
        val holderName: String?,
        val framesProcessed: Int,
    )

    private val numberVotes = HashMap<String, Int>()
    private val expiryVotes = HashMap<CardExpiry, Int>()
    private val nameVotes = HashMap<String, Int>()
    private var numberLockedAt: Long? = null
    var framesProcessed = 0
        private set

    val leadingNumber: String? get() = leader(numberVotes)

    fun add(frame: FrameParseResult): Result? {
        framesProcessed++
        frame.number?.let { numberVotes.merge(it, 1, Int::plus) }
        if (wantsExpiry) frame.expiry?.let { expiryVotes.merge(it, 1, Int::plus) }
        if (wantsName) frame.holderName?.let { nameVotes.merge(it, 1, Int::plus) }

        val number = winner(numberVotes, numberVotesRequired) ?: return null
        val lockedAt = numberLockedAt ?: clock().also { numberLockedAt = it }

        val expiry = winner(expiryVotes, expiryVotesRequired)
        val name = winner(nameVotes, nameVotesRequired)
        val allLocked = (!wantsExpiry || expiry != null) && (!wantsName || name != null)
        val graceOver = clock() - lockedAt >= extraFieldsGraceMs
        if (!allLocked && !graceOver) return null

        return Result(number, expiry ?: leader(expiryVotes), name, framesProcessed)
    }

    private fun <T> leader(votes: Map<T, Int>): T? = votes.maxByOrNull { it.value }?.key

    private fun <T> winner(votes: Map<T, Int>, required: Int): T? =
        leader(votes)?.takeIf { votes.getValue(it) >= required }
}
