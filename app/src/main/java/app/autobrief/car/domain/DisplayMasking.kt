package app.autobrief.car.domain

/**
 * Masks what THIS app shows (it cannot change the built-in navigation screen): apartment
 * building/unit numbers and private bookmark aliases. Administrative districts such as 역삼1동,
 * subway lines such as 2호선 and branch names such as 3호점 are left alone.
 */
object DisplayMasking {
    private val buildingUnit = Regex("""\d{1,4}\s*동\s*\d{1,5}\s*호(?![선점실])""")
    private val unitAfterComplex = Regex("""(?<=\s)\d{3,5}\s*호(?![선점실])""")
    private val alias = Regex("""^(우리집|내 집|집|자택)\s*[:：]?\s*""")

    fun mask(address: String): String = address
        .replace(alias, "")
        .replace(buildingUnit, "동·호수 비공개")
        .replace(unitAfterComplex, "호수 비공개")
        .trim()
}
