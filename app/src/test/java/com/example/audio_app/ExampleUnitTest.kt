package com.example.audio_app

import com.example.audio_app.data.OpenMicProtocol
import org.junit.Test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows

class ExampleUnitTest {
    @Test
    fun normalizesDeviceAddress() {
        assertEquals("http://192.168.1.50", OpenMicProtocol.normalizeBaseUrl(" 192.168.1.50/ "))
        assertEquals("http://mic.local", OpenMicProtocol.normalizeBaseUrl("http://mic.local/"))
    }

    @Test
    fun parsesRecordingsFromOpenMicHtml() {
        val html = """
            <p>2 files, 3.2 MB</p>
            <table>
              <tr><td><a href="/files/download?name=0002-20261021T203015.WAV">0002-20261021T203015.WAV</a></td><td align=right>2.4 MB</td></tr>
              <tr><td><a href="/files/download?name=0001-.WAV">0001-.WAV</a></td><td align=right>800.0 kB</td></tr>
            </table>
        """.trimIndent()

        val recordings = OpenMicProtocol.parseFileList(html)

        assertEquals(2, recordings.size)
        assertEquals("0002-20261021T203015.WAV", recordings[0].name)
        assertEquals("2.4 MB", recordings[0].sizeLabel)
        assertEquals("录音 0001", OpenMicProtocol.displayName(recordings[1].name))
    }

    @Test
    fun rejectsUnsafeRecordingNames() {
        assertFalse(OpenMicProtocol.isValidFileName("../0001.WAV"))
        assertFalse(OpenMicProtocol.isValidFileName("0001.WAV"))
    }

    @Test
    fun rejectsAnUnrelatedHttpPage() {
        assertThrows(IllegalStateException::class.java) {
            OpenMicProtocol.parseFileList("<html><h1>Printer settings</h1></html>")
        }
    }
}
