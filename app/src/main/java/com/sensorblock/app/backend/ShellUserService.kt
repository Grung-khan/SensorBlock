package com.sensorblock.app.backend

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.sensorblock.app.shell.IShellService

/**
 * This class is instantiated by the **Shizuku server**, inside a process that
 * runs as uid 2000 (shell), and is then bound from [ShellServiceClient] in the
 * app process.
 *
 * That is the whole trick: the shell identity holds `MANAGE_APP_OPS_MODES`,
 * `MANAGE_SENSORS`, `MANAGE_CAMERA` and `DUMP` (verified in
 * `packages/Shell/AndroidManifest.xml`), so the few primitives that have no
 * safe Binder entry point can be driven with ordinary shell commands.
 *
 * It must be listed in `proguard-rules.pro` so R8 never strips it or its
 * constructors.
 */
class ShellUserService : IShellService.Stub {

    /** Required by Shizuku. */
    constructor() : super()

    /**
     * Optional constructor, available from Shizuku API v13. The [Context] is
     * created by the Shizuku server with `createPackageContextAsUser`.
     */
    @Keep
    constructor(context: Context) : super() {
        this.context = context
    }

    private var context: Context? = null

    override fun uid(): Int = Process.myUid()

    override fun runCommandStdout(cmd: String): String {
        val merged = exec(cmd)
        return merged.substringAfter('\n', "")
    }

    override fun runCommand(cmd: String): String = exec(cmd)

    /**
     * Runs [cmd] through `sh -c` and returns a result whose first line is
     * `exit=<code>` followed by the combined stdout+stderr.
     *
     * stderr is merged into stdout (`redirectErrorStream`) so the two pipes can
     * never deadlock each other on a large write.
     */
    private fun exec(cmd: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit != 0) Log.w(TAG, "command failed (exit=$exit): $cmd\n$output")
            "exit=$exit\n$output"
        } catch (t: Throwable) {
            Log.e(TAG, "command threw: $cmd", t)
            "exit=-1\n${t.javaClass.simpleName}: ${t.message}"
        }
    }

    companion object {
        private const val TAG = "ShellUserService"
    }
}
