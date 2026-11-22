package com.example.spatialphoto

import com.example.spatialphoto.domain.model.CapturePhoto
import com.example.spatialphoto.domain.model.GaussianOutput
import com.example.spatialphoto.domain.usecase.GaussianPlyUseCase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfficialCovarianceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportActualSharpFixture() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/sharp128.bin")).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun read(count: Int) = FloatArray(count) { buffer.float }
        val gaussians = GaussianOutput(read(384), read(384), read(512), read(384), read(128))
        val output = temporary.root.resolve("covariance-fixture.ply")
        val photo = CapturePhoto("", 1920, 1440, 1221f, 1197f, 945f, 700f)
        assertTrue(GaussianPlyUseCase.write(gaussians, photo, output) > 100)
        assertTrue(output.isFile)
    }
}
