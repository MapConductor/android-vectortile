package com.mapconductor.vectortile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mapconductor.core.tileserver.TileRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Each tile is answered once, complete.
 *
 * Reported after zooming in: the raster layers showed their tiles, then the
 * whole layer loaded again. Tiles were drawn without the glyphs (and, on a
 * slow network, the sources) still on their way, and the arrival handed the
 * layer over, which made the map ask for every visible tile a second time.
 * A tile now waits for what it needs, so the first answer is the last.
 */
@RunWith(AndroidJUnit4::class)
class SinglePassTileTest {
    private lateinit var styleJson: String
    private lateinit var tileBytes: ByteArray
    private lateinit var glyphBytes: ByteArray
    private lateinit var directory: File

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        styleJson = assets.open("demo-style.json").use { it.readBytes().decodeToString() }
        tileBytes = assets.open("tile-0-0-0.pbf").use { it.readBytes() }
        glyphBytes = assets.open("glyphs-0-255.pbf").use { it.readBytes() }
        directory = File(instrumentation.targetContext.cacheDir, "single-pass-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun isGlyph(url: String) = url.endsWith("0-255.pbf") && "/font/" in url

    /** Labels at z2, where the demo style's country names start. */
    private val labelled = TileRequest(x = 2, y = 1, z = 2)

    private fun provider(
        fetch: (String) -> ByteArray?,
        cacheDir: File? = null,
    ) = VectorTileProvider.create(
        styleJson = styleJson,
        tileSize = 512,
        renderScale = 1,
        renderMode = VectorTileProvider.RenderMode.CPU,
        diskCacheDir = cacheDir,
        fetchTile = fetch,
    )

    /** The label tile with no glyphs to be had: what "drawn without labels" looks like. */
    private fun bareLabels(): ByteArray =
        provider({ url -> if (isGlyph(url)) null else tileBytes }).use {
            it.labelTiles.renderTile(labelled)!!
        }

    @Test
    fun aLabelTileWaitsForItsGlyphsAndIsAnsweredOnce() {
        val handedOver = CountDownLatch(1)
        val first =
            provider({ url ->
                if (isGlyph(url)) {
                    Thread.sleep(300)
                    glyphBytes
                } else {
                    tileBytes
                }
            }).use { provider ->
                provider.onGlyphsLoaded = { handedOver.countDown() }
                val started = System.nanoTime()
                val png = provider.labelTiles.renderTile(labelled)
                val seconds = (System.nanoTime() - started) / 1e9
                assertNotNull(png)
                assertTrue("waited ${seconds}s for a 0.3s range", seconds < 3.0)
                // Nothing to hand over: the tile already has its labels.
                assertFalse("the layer was handed over", handedOver.await(2, TimeUnit.SECONDS))
                png!!
            }
        assertFalse("the first answer has no labels on it", first.contentEquals(bareLabels()))
        // The same as a tile drawn with the glyphs already in.
        val warm =
            provider({ url -> if (isGlyph(url)) glyphBytes else tileBytes }).use {
                it.labelTiles.renderTile(labelled)
                it.labelTiles.renderTile(labelled)!!
            }
        assertArrayEquals(warm, first)
    }

    @Test
    fun aTileWaitsForASlowSourceAndIsKept() {
        val handedOver = CountDownLatch(1)
        provider(
            { url ->
                if (isGlyph(url)) {
                    null
                } else {
                    Thread.sleep(1_000)
                    tileBytes
                }
            },
            cacheDir = directory,
        ).use { provider ->
            provider.onSourcesLoaded = { handedOver.countDown() }
            val request = TileRequest(x = 0, y = 0, z = 0)
            val png = provider.geometryTiles.renderTile(request)
            assertNotNull(png)
            assertArrayEquals("drawn short of a source a second away", wholeGround(request), png)
            assertEquals("the complete tile was not kept", 1, renderedTiles().size)
            assertFalse("the layer was handed over", handedOver.await(2, TimeUnit.SECONDS))
        }
    }

    private fun wholeGround(request: TileRequest): ByteArray =
        provider({ tileBytes }).use { it.geometryTiles.renderTile(request)!! }

    private fun renderedTiles(): List<File> =
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".png") } ?: emptyList()
}
