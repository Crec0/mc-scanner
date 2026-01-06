package de.skyrising.mc.scanner

import de.skyrising.mc.scanner.script.Scan
import de.skyrising.mc.scanner.script.ScannerScript
import it.unimi.dsi.fastutil.objects.Object2LongMap
import joptsimple.OptionException
import joptsimple.OptionParser
import joptsimple.ValueConverter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.IntArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.net.URI
import java.nio.file.*
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createParentDirectories
import kotlin.jvm.optionals.getOrElse
import kotlin.math.max
import kotlin.math.min
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

var DECOMPRESSOR = Decompressor.INTERNAL

fun main(args: Array<String>) {
    val parser = OptionParser()
    val helpArg = parser.accepts("help").forHelp()
    val nonOptions = parser.nonOptions()

    val blockArg = parser.acceptsAll(listOf("b", "block"), "Add a block to search for").withRequiredArg()
    val itemArg = parser.acceptsAll(listOf("i", "item"), "Add an item to search for").withRequiredArg()
    val inPathArg = parser.acceptsAll(listOf("p", "path"), "Paths to scan").withRequiredArg()
    val outPathArg = parser.acceptsAll(listOf("o", "out"), "Output path").withOptionalArg()

    val statsArg = parser.accepts("stats", "Calculate statistics for storage tech")
    val geode = parser.accepts("geode", "Calculate AFK spots for geodes")
    val threadsArg = parser
        .acceptsAll(listOf("t", "threads"), "Set the number of threads to use")
        .withRequiredArg()
        .ofType(Integer::class.java)

    val loopArg = parser.accepts("loop").withOptionalArg().ofType(Integer::class.java)

    val decompressorArg = parser
        .accepts("decompressor", "Decompressor to use")
        .withOptionalArg()
        .withValuesConvertedBy(object : ValueConverter<Decompressor> {
            override fun convert(value: String) = Decompressor.valueOf(value.uppercase())
            override fun valueType() = Decompressor::class.java
            override fun valuePattern() = "internal|java"
        })
    val needles = mutableListOf<Needle>()

    fun printUsage() {
        System.err.println("Usage: mc-scanner (-i <item> | -b <block>)* [options] <-p|path path>+ [-o|out output]")
        parser.printHelpOn(System.err)
    }

    var threads = 0
    var loopCount = 0
    val inPaths = mutableListOf<Path>()
    val outPath: Path
    val zip: FileSystem?
    val script: Path
    val outputJson: Boolean

    try {
        val options = parser.parse(*args)
        if (options.has(helpArg)) {
            printUsage()
            return
        }

        for (block in options.valuesOf(blockArg)) {
            val state = BlockState.parse(block)
            needles.add(state)
            needles.addAll(state.unflatten())
        }

        for (item in options.valuesOf(itemArg)) {
            val itemType = ItemType.parse(item)
            needles.add(itemType)
            needles.addAll(itemType.unflatten())
        }

        for (inPath in options.valuesOf(inPathArg)) {
            inPaths.add(Paths.get(inPath))
        }

        val out = Paths.get(options.valueOfOptional(outPathArg).getOrElse { "" })

        if (options.has(threadsArg)) threads = options.valueOf(threadsArg).toInt()
        if (options.has(decompressorArg)) DECOMPRESSOR = options.valueOf(decompressorArg)
        if (options.has(loopArg)) {
            loopCount = if (options.hasArgument(loopArg)) {
                options.valueOf(loopArg).toInt()
            } else {
                -1
            }
        }

        val nonOptionArgs = options.valuesOf(nonOptions).toMutableList()

        script = when {
            options.has(statsArg) -> builtinScript("stats")
            options.has(geode) -> builtinScript("geode")
            nonOptionArgs.isNotEmpty() && nonOptionArgs[0].endsWith(".scan.kts") -> Paths.get(nonOptionArgs.removeAt(0))
            else -> builtinScript("search")
        }

        if (nonOptionArgs.size > 1) throw IllegalArgumentException("Expected at most 1 optional arg")

        if (out.fileName.toString().endsWith(".json")) {
            out.createParentDirectories()
            outPath = out
            outputJson = true
            zip = null
        } else if (Files.exists(out) && Files.isDirectory(out)) {
            outPath = out
            outputJson = false
            zip = null
        } else if (!out.fileName.toString().endsWith(".zip")) {
            Files.createDirectories(out)
            outPath = out
            outputJson = false
            zip = null
        } else {
            val uri = out.toUri()
            val fsUri =
                URI("jar:${uri.scheme}", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment)
            zip = FileSystems.newFileSystem(fsUri, mapOf<String, Any>("create" to "true"))
            outputJson = false
            outPath = zip.getPath("/")
        }
    } catch (e: RuntimeException) {
        if (e is OptionException || e is IllegalArgumentException) {
            System.err.println(e.message)
        } else {
            e.printStackTrace()
        }
        println()
        printUsage()
        return
    }

    val executor = when {
        threads <= 0 -> Executors.newWorkStealingPool()
        threads == 1 -> Executors.newSingleThreadExecutor()
        else -> Executors.newWorkStealingPool(threads)
    }

    do {
        runScript(inPaths, outPath, outputJson, executor, needles, script)
    } while (loopCount == -1 || loopCount-- > 0)

    zip?.close()
    executor.shutdownNow()
}

fun getHaystack(paths: List<Path>): Set<Scannable> {
    val haystack = mutableSetOf<Scannable>()
    for (path in paths) {
        if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".mca")) {
            haystack.add(RegionFile(path))
        }

        val playerDataPath = path.resolve("playerdata")
        if (Files.exists(playerDataPath)) {
            Files.list(playerDataPath).forEach {
                val fileName = it.fileName.toString()
                if (fileName.endsWith(".dat") && fileName.split('-').size == 5) {
                    try {
                        haystack.add(PlayerFile(it))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        for (dim in listOf(".", "DIM-1", "DIM1")) {
            val dimPath = path.resolve(dim)
            if (!Files.exists(dimPath)) continue
            val dimRegionPath = dimPath.resolve("region")
            if (!Files.exists(dimRegionPath)) continue
            Files.list(dimRegionPath).forEach {
                if (it.fileName.toString().endsWith(".mca")) {
                    try {
                        haystack.add(RegionFile(it))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    return haystack
}

//fun builtinScript(name: String): Path {
//    val url = ScannerScript::class.java.getResource("/scripts/$name.scan.kts")
//    print(url?.toURI().toString())
//    return Paths.get(url?.toURI() ?: throw IllegalArgumentException("Script not found: $name"))
//}
fun builtinScript(name: String): Path {
    val uri = ScannerScript::class.java
        .getResource("/scripts/$name.scan.kts")
        ?.toURI()
        ?: throw IllegalArgumentException("Script not found: $name")

    if (uri.scheme == "jar") {
        // Ensure zipfs is created (no-op if already exists)
        FileSystems.newFileSystem(uri, emptyMap<String, Any>())
    }

    return Paths.get(uri)
}

fun evalScript(path: Path, scan: Scan): ResultWithDiagnostics<EvaluationResult> {
    val source = Files.readAllBytes(path).toString(Charsets.UTF_8).toScriptSource(path.fileName.toString())
    return BasicJvmScriptingHost().evalWithTemplate<ScannerScript>(source, evaluation = {
        constructorArgs(scan)
    })
}

fun runScript(path: List<Path>, outPath: Path, outputJson: Boolean, executor: ExecutorService, needles: List<Needle>, script: Path) {
    val scan = Scan(outPath, needles, outputJson)
    if (!outputJson) {
        evalScript(script, scan).valueOrThrow()
    }
    val haystack = getHaystack(path).filterTo(mutableSetOf(), scan.haystackPredicate)
    var totalSize = 0L
    for (s in haystack) totalSize += s.size
    val progressSize = AtomicLong()
    var speed = 0.0
    var resultCount = 0
    val lock = Object()
    fun printStatus(i: Int, current: Any? = null) {
        synchronized(lock) {
            print("\u001b[2K$i/${haystack.size} ")
            print("${formatSize(progressSize.toDouble())}/${formatSize(totalSize.toDouble())} ")
            print("${formatSize(speed)}/s $resultCount result${if (resultCount == 1) "" else "s"} ")
            if (current != null) print(current)
            print("\u001B[G")
        }
    }

    val json = Json {
        classDiscriminator = "class"
    }

    val searchResults = mutableListOf<SearchResult>()

    val index = AtomicInteger()
    printStatus(0)
    val before = System.nanoTime()
    val futures = haystack.map {
        CompletableFuture.runAsync({
            val results = try {
                scan.scanner(it)
            } catch (e: Exception) {
                print("\u001b[2K")
                System.err.println("Error scanning $it")
                e.printStackTrace()
                println()
                return@runAsync
            }
            val time = System.nanoTime() - before
            val progressAfter = progressSize.addAndGet(it.size)
            speed = progressAfter * 1e9 / time
            synchronized(scan) {
                scan.onResults(results)
                resultCount += results.size
                searchResults.addAll(results)
            }
            printStatus(index.incrementAndGet(), it)
        }, executor)
    }
    CompletableFuture.allOf(*futures.toTypedArray()).join()
    scan.postProcess()
    printStatus(haystack.size)

    if (outputJson) {
        val outputStream = BufferedOutputStream(Files.newOutputStream(outPath))
        val writer = outputStream.writer()
        writer.write(json.encodeToString(searchResults))
        writer.close()
        outputStream.close()
    }

    println()
}

interface Scannable {
    val size: Long
    fun scan(needles: Collection<Needle>, statsMode: Boolean): List<SearchResult>
}

@Serializable
sealed interface Location

@Serializable
data class SubLocation(val parent: Location, val index: Int) : Location

@Serializable
data class Container(val type: String, val location: Location) : Location

@Serializable
data class Entity(val type: String, val location: Location) : Location

@Serializable
data class ChunkPos(val dimension: String, val x: Int, val z: Int) : Location {
    fun inChunkRange(block0: BlockPos, block1: BlockPos): Boolean {
        return this.x in block0.sectionX..block1.sectionX && this.z in block0.sectionZ..block1.sectionZ
    }
}

@Serializable
data class BlockPos(val dimension: String, val x: Int, val y: Int, val z: Int) : Location {

    val sectionX: Int
        get() = x shr 4

    val sectionY: Int
        get() = y shr 4

    val sectionZ: Int
        get() = z shr 4

    fun inBlockRange(block0: BlockPos, block1: BlockPos): Boolean {
        return this.x in block0.x..block1.x && this.y in block0.y..block1.y && this.z in block0.z..block1.z
    }

    companion object {
        fun fromSection(chunkPos: ChunkPos, sectionY: Int, offsetX: Int, offsetY: Int, offsetZ: Int): BlockPos {
            val absoluteSectionX = chunkPos.x * 16
            val absoluteSectionY = sectionY * 16
            val absoluteSectionZ = chunkPos.z * 16
            return BlockPos(chunkPos.dimension, absoluteSectionX + offsetX, absoluteSectionY + offsetY, absoluteSectionZ + offsetZ)
        }
    }
}

@Serializable
data class Vec3d(val dimension: String, val x: Double, val y: Double, val z: Double) : Location

@Serializable
data class PlayerInventory(@Serializable(with = UUIDSerializer::class) val player: UUID, val enderChest: Boolean) :
    Location

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID {
        return UUID.fromString(decoder.decodeString())
    }
}

@Serializable
data class StatsResults(val types: Array<ItemType>, val matrix: DoubleArray) : Needle {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StatsResults

        if (!types.contentEquals(other.types)) return false
        if (!matrix.contentEquals(other.matrix)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = types.contentHashCode()
        result = 31 * result + matrix.contentHashCode()
        return result
    }
}

@Serializable(with = SearchResultSerializer::class)
data class SearchResult(val needle: Needle, val location: Location, val count: Long)

@OptIn(ExperimentalSerializationApi::class)
class SearchResultSerializer : KSerializer<SearchResult> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SearchResult") {
        element("nt", String.serializer().descriptor)
        element("id", String.serializer().descriptor)
        element("lt", String.serializer().descriptor)
        element("di", String.serializer().descriptor)
        element("lc", IntArraySerializer().descriptor)
        element("cn", Long.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: SearchResult) {
        encoder.encodeStructure(descriptor) {
            when (value.needle) {
                is BlockState -> {
                    encodeStringElement(descriptor, 0, "BlockState")
                    encodeStringElement(descriptor, 1, value.needle.id.path)
                }
                else -> {}
            }
            when (value.location) {
                is BlockPos -> {
                    encodeStringElement(descriptor, 2, "BlockPos")
                    encodeStringElement(descriptor, 3, value.location.dimension)
                    encodeSerializableElement(
                        descriptor,
                        4,
                        ListSerializer(Int.serializer()),
                        listOf(value.location.x, value.location.y, value.location.z)
                    )
                }
                is ChunkPos -> {
                    encodeStringElement(descriptor, 2, "ChunkPos")
                    encodeStringElement(descriptor, 3, value.location.dimension)
                    encodeSerializableElement(
                        descriptor,
                        4,
                        ListSerializer(Int.serializer()),
                        listOf(value.location.x, value.location.z)
                    )
                }
                is Vec3d -> {
                    encodeStringElement(descriptor, 2, "Vec3d")
                    encodeStringElement(descriptor, 3, value.location.dimension)
                }
                is PlayerInventory -> {
                    encodeStringElement(descriptor, 2, "PlayerInventory")
                    encodeStringElement(descriptor, 3, value.location.player.toString())
                }
                is Container -> {
                    encodeStringElement(descriptor, 2, "Container")
                    encodeStringElement(descriptor, 3, value.location.type)
                }
                is Entity -> {
                    encodeStringElement(descriptor, 2, "Entity")
                    encodeStringElement(descriptor, 3, value.location.type)
                }
                is SubLocation -> {
                    encodeStringElement(descriptor, 2, "SubLocation")
                    encodeStringElement(descriptor, 3, value.location.index.toString())
                }
            }

            encodeLongElement(descriptor, 5, value.count)
        }
    }

    override fun deserialize(decoder: Decoder): SearchResult {
        TODO("Not yet implemented")
    }
}

fun addResults(
    results: MutableList<SearchResult>,
    location: Location,
    contents: List<Object2LongMap<ItemType>>,
    statsMode: Boolean
) {
    for (e in contents[0].object2LongEntrySet()) {
        results.add(SearchResult(e.key, location, e.longValue))
    }
    if (statsMode) {
        results.add(SearchResult(tallyStats(contents[0]), location, 1))
        for (i in 1 until contents.size) {
            val subLocation = SubLocation(location, i)
            for (e in contents[i].object2LongEntrySet()) {
                results.add(SearchResult(e.key, subLocation, e.longValue))
            }
        }
    }
}