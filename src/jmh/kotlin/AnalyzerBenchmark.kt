package org.example

import analyzer.AnteReport
import analyzer.Item
import analyzer.SeedAnalyzer
import analyzer.Util.editionFromDisplayName
import analyzer.Util.jokerFromDisplayName
import analyzer.Util.jokerInAnte
import analyzer.Util.jokerInPack
import analyzer.Util.jokerInShop
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class JokerFilterBenchmark {

    // Build a realistic AnteReport once per benchmark run, not per invocation
    lateinit var report: AnteReport
    lateinit var target: Item
    lateinit var edition: Item

    @Setup(Level.Trial)
    fun setup() {
        val analyzer = SeedAnalyzer("5I2A9TS8")
        report = analyzer.ante(1, listOf("Planet_Merchant", "Magic_Trick"), 50)
        println("shopItems=${report.shopItems.size}, packs=${report.packs.size}") // sanity check, remove after
        target = jokerFromDisplayName("Blueprint")
        edition = editionFromDisplayName("Negative")
    }

    @Benchmark
    fun benchJokerInAnte(bh: Blackhole) {
        val result = jokerInAnte(report, target, 50, edition)
        bh.consume(result) // prevents JIT from eliminating the "unused" result
    }

    @Benchmark
    fun benchJokerInShop(bh: Blackhole) {
        bh.consume(jokerInShop(report, target, edition))
    }

    @Benchmark
    fun benchJokerInPack(bh: Blackhole) {
        bh.consume(jokerInPack(report, target, edition))
    }

    @Benchmark
    fun benchAnteSimulation(bh: Blackhole) {
        val analyzer = SeedAnalyzer("5I2A9TS8")
        bh.consume(analyzer.ante(1, listOf("Planet_Merchant", "Magic_Trick"), 50))
    }
}