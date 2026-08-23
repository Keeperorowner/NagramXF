package org.telegram.chaquopy

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

internal fun findHostPython(): String {
    val candidates = if (OperatingSystem.current().isWindows) listOf("python", "python3") else listOf("python3", "python")
    val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
    for (name in candidates) {
        val exe = if (OperatingSystem.current().isWindows) "$name.exe" else name
        for (dir in pathDirs) {
            if (File(dir, exe).isFile) {
                return File(dir, exe).absolutePath
            }
        }
        // Fall back to a bare invocation (e.g. python from the Windows store alias or a shell shim).
        try {
            if (ProcessBuilder(name, "--version").start().waitFor() == 0) {
                return name
            }
        } catch (_: Exception) {
        }
    }
    throw GradleException(
        "Python not found on PATH. It is required to build the Chaquopy bridge from source " +
            "(sdk_chaqsrc/); install Python 3.11+ and run: pip install Cython==3.0.11"
    )
}

internal fun checkCythonVersion(python: String) {
    val out = ByteArrayOutputStream()
    val proc = ProcessBuilder(python, "-c", "import cython; print(cython.__version__)")
        .redirectErrorStream(true).start()
    proc.outputStream.close()
    proc.inputStream.copyTo(out)
    val version = out.toString().trim()
    if (proc.waitFor() != 0 || version != "3.0.11") {
        throw GradleException(
            "Cython 3.0.11 is required to build the Chaquopy bridge (found: '$version'). " +
                "Install: $python -m pip install Cython==3.0.11"
        )
    }
}

/**
 * Runs Cython on a .pyx and applies the same generated-C post-processing as upstream
 * Chaquopy's product/runtime build: JNIEXPORT/JNICALL on exported functions (JNICALL matters
 * on some targets) and the workaround for https://github.com/cython/cython/issues/3725.
 */
abstract class ChaquopyCythonize @Inject constructor() : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pyxFile: RegularFileProperty

    /** Extra Cython inputs (.pxi/.pxd); only feed up-to-date checks. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includes: ConfigurableFileCollection

    @get:OutputFile
    abstract val outCFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun generate() {
        val pyx = pyxFile.get().asFile
        val out = outCFile.get().asFile
        out.parentFile.mkdirs()
        val python = findHostPython()
        checkCythonVersion(python)
        execOps.exec {
            workingDir(pyx.parentFile)
            commandLine(python, "-m", "cython", "-Wextra", pyx.name, "-o", out.absolutePath)
        }.assertNormalExitValue()

        val externC = Regex("^__PYX_EXTERN_C (\\w+)")
        val insertCode = Regex("__pyx_insert_code_object\\(c_line")
        val lines = out.readLines().map { line ->
            line.replace(externC, "/* cythonTask altered */ __PYX_EXTERN_C JNIEXPORT ${'$'}1 JNICALL")
                .replace(insertCode, "// cythonTask disabled: ${'$'}0")
        }
        out.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

/** Compiles and links one Chaquopy bridge shared library with the NDK clang. */
abstract class ChaquopyClangLink @Inject constructor() : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection

    /** Directory containing libpython3.11.so for this ABI (from the Chaquopy target artifact). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val linkDir: DirectoryProperty

    @get:Input
    abstract val linkLibs: ListProperty<String>

    @get:Input
    abstract val targetTriple: Property<String>

    @get:Input
    @get:Optional
    abstract val ndkDirPath: Property<String>

    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun link() {
        val ndk = ndkDirPath.orNull
            ?: throw GradleException("Android NDK directory is unavailable, cannot build the Chaquopy bridge.")
        val os = OperatingSystem.current()
        val hostTag = when {
            os.isWindows -> "windows-x86_64"
            os.isLinux -> "linux-x86_64"
            os.isMacOsX -> if (System.getProperty("os.arch") == "aarch64") "darwin-arm64" else "darwin-x86_64"
            else -> throw GradleException("Unsupported host OS for NDK: $os")
        }
        val ext = if (os.isWindows) ".exe" else ""
        val clang = File(ndk, "toolchains/llvm/prebuilt/$hostTag/bin/clang$ext")
        if (!clang.isFile) {
            throw GradleException("NDK clang not found: $clang")
        }
        val args = mutableListOf(
            clang.absolutePath, "--target=${targetTriple.get()}",
            "-shared", "-fPIC", "-O2", "-DNDEBUG", "-Wno-deprecated-declarations"
        )
        includeDirs.files.forEach { args.addAll(listOf("-I", it.absolutePath)) }
        sources.files.sortedBy { it.absolutePath }.forEach { args.add(it.absolutePath) }
        args.addAll(listOf("-L", linkDir.get().asFile.absolutePath))
        linkLibs.get().forEach { args.add("-l$it") }
        args.addAll(listOf("-o", outFile.get().asFile.absolutePath))
        outFile.get().asFile.parentFile.mkdirs()
        execOps.exec { commandLine(args) }.assertNormalExitValue()
    }
}

/**
 * Generates chaquopy/build.json with the sha1 of every packaged runtime asset. The hashes gate
 * asset re-extraction on app update, so they must always match the assets of this exact build
 * (the bridge .so hashes change whenever it is rebuilt).
 */
abstract class ChaquopyBuildJson @Inject constructor() : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val staticAssets: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stagedAssets: DirectoryProperty

    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val entries = sortedMapOf<String, String>()
        listOf(staticAssets.get().asFile, stagedAssets.get().asFile).forEach { base ->
            base.walkTopDown().filter { it.isFile && it.name != "build.json" }.forEach { f ->
                val digest = MessageDigest.getInstance("SHA-1")
                digest.update(f.readBytes())
                entries[base.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/')] =
                    digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
        val out = outFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(buildString {
            append("{\n    \"assets\": {\n")
            entries.entries.forEachIndexed { i, e ->
                append("        \"").append(e.key).append("\": \"").append(e.value)
                    .append(if (i < entries.size - 1) "\",\n" else "\"\n")
            }
            append("    },\n    \"extract_packages\": [],\n    \"python_version\": \"3.11\"\n}\n")
        })
    }
}
