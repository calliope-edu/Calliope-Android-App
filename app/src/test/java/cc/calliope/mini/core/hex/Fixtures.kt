package cc.calliope.mini.core.hex

import java.io.File

/**
 * Real files for the parser tests: the hex programs bundled with the app,
 * and the byte streams the app exchanged with a mini 3 while flashing
 * them (captured on 2026-10-20 from the partial-flashing characteristic).
 */
object Fixtures {

    /** Gradle runs unit tests with the module directory as working directory. */
    private val rawDir = File("src/main/res/raw")

    fun hex(name: String): IntelHexFile = IntelHexFile.read(File(rawDir, name).path)

    fun hexPath(name: String): String = File(rawDir, name).path

    /**
     * One captured partial-flash session as `TX <hex>` / `RX <hex>` lines,
     * in the order they went over the air.
     */
    fun capture(name: String): List<Pair<String, String>> =
        Fixtures::class.java.getResourceAsStream("/partialflashing/$name")!!
            .bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { line -> line.substringBefore(' ') to line.substringAfter(' ') }

    /** The flash-command writes (`0x01 …`) of a capture, in order. */
    fun capturedPackets(name: String): List<String> =
        capture(name).filter { (dir, bytes) -> dir == "TX" && bytes.startsWith("01") && bytes.length > 4 }
            .map { it.second }
}
