pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.byted.org/repository/android_public")
            name = "byte-internal"
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://artifact.bytedance.com/repository/Volcengine")
            name = ""
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("https://repo.maven.apache.org/maven2") } }
            filter { includeGroup("com.qualcomm.qti") }
        }
        maven {
            url = uri("https://maven.byted.org/repository/android_public")
            name = "byte-internal"
        }
        google()
        mavenCentral()
        maven {
            url = uri("https://artifact.bytedance.com/repository/Volcengine")
            name = ""
        }
    }
}

rootProject.name = "SpatialPhoto"

// Research model bytes are split only for Git transport. Restore the exact
// original before Android merges assets; the inference graph/weights are unchanged.
val modelFile = File(settingsDir, "app/src/main/assets/models/sharp-fp16.onnx.data")
fun sha256(file: File): String = java.security.MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
val expectedModelHash = "3bf4dc9f4a52ebd7eee9349f65f435cefb6d7e7a62dd88f560250437cbed2285"
if (!modelFile.isFile || modelFile.length() != 1306323328L || sha256(modelFile) != expectedModelHash) {
    val weightsParts = listOf(
    "weights-000.bin" to "34587bfd5931c21fe7a28e3fb3cc813b967da0f41838dedafd4fbe88ae55f72c",
    "weights-001.bin" to "d43be548b5e6706e445b2d58666dcc9d6b85ddbdd3c1660bb06e4d476d8f6d6e",
    "weights-002.bin" to "18540df1cebd28b85cf400d435987db760f6be3efc65936d59b512e9c6b7f0ec",
    "weights-003.bin" to "3711cc23f81d7af67ea6aaa095e7420317e80aebae3997398dae91b30027121c",
    "weights-004.bin" to "65c5e200bcea319d94fa4450f3aafae83db51e183fdae443ada4e353220438ab",
    "weights-005.bin" to "0865e821ce5a7dafd0ba7569240ede42ca0ef51fe6dd431bd6cf615652d07494",
    "weights-006.bin" to "17722e4ae2e781f01b0b68bec3bb16efb4d840ce1930a07daa85c993f2648c34",
    "weights-007.bin" to "36dc646b7e2d5a30609c3410374070b9badd7b0f944635f5c7f057a32657e2bb",
    "weights-008.bin" to "b3e7f3787bd30ec402e381a62453fc8d31ba33af01fe153c3a59ccc4cdd24654",
    "weights-009.bin" to "e188e748e23b95ae02820b6c905e8ce136cf7b5548ae609ccc632b3fb6915b7f",
    "weights-010.bin" to "3f31f16341598784ea4144ea301751260cf03414adcdc09f4c33ea0e0e25f4f1",
    "weights-011.bin" to "78648127e76b933f0b1d334e74f60123336cfec98bcfb104d9ba8479012854eb",
    "weights-012.bin" to "bbd73468133f54602da06045f8de275c92f38b5e0932419a653152561da0b216",
    "weights-013.bin" to "975d62bbd458d7ffbcb2b69fe1b007b74918ce0236f023c2b9e63320dc710fbd",
    "weights-014.bin" to "273daf463f19135b7c53f93ba9353643b7253144e2693be58f77b42506127d4e",
    "weights-015.bin" to "325afc71a6ebd82da9e11dbf464a1015950cc66e4a12e5a64bf8bc11630893f0"
    )
    val temporary = File(modelFile.parentFile, modelFile.name + ".restoring")
    try {
        temporary.outputStream().buffered().use { out ->
            for ((name, expected) in weightsParts) {
                val part = File(settingsDir, "model-weights/$name")
                check(part.isFile && sha256(part) == expected) { "Model transport part missing/corrupt: $name" }
                part.inputStream().buffered().use { it.copyTo(out, 1024 * 1024) }
            }
        }
        check(sha256(temporary) == expectedModelHash) { "Restored model checksum mismatch" }
        java.nio.file.Files.move(temporary.toPath(), modelFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}

include(":app")
