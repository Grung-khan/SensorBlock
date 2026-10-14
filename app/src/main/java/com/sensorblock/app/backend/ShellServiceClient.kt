package com.sensorblock.app.backend

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.sensorblock.app.shell.IShellService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Outcome of a shell command executed as uid 2000. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val raw: String,
) {
    val ok: Boolean get() = exitCode == 0
    fun stdoutTrimmed(): String = stdout.trim()

    companion object {
        val UNAVAILABLE = ShellResult(-2, "", "shell service unavailable")
        fun parse(raw: String): ShellResult {
            val nl = raw.indexOf('\n')
            if (nl < 0) return ShellResult(-1, "", raw)
            val code = raw.substring(0, nl).removePrefix("exit=").toIntOrNull() ?: -1
            return ShellResult(code, raw.substring(nl + 1), raw)
        }
    }
}

/**
 * Owns the Shizuku UserService that runs shell commands as uid 2000.
 *
 * Binding is asynchronous and its callbacks arrive on the main thread, so this
 * class never blocks a thread that the callback needs. Callers suspend instead.
 */
class ShellServiceClient(private val context: Context) {

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShellUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(false)
        .version(VERSION)

    private val mutex = Mutex()

    @Volatile
    private var service: IShellService? = null

    @Volatile
    private var pending: CompletableDeferred<IShellService>? = null

    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val resolved = binder?.let {
                runCatching { IShellService.Stub.asInterface(it) }.getOrNull()
            }
            if (resolved == null) {
                pending?.completeExceptionally(IllegalStateException("shell service binder unusable"))
                pending = null
                return
            }
            service = resolved
            pending?.complete(resolved)
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "shell service disconnected: $name")
            service = null
            bound = false
        }
    }

    /** `true` when a shell command can be attempted right now. */
    fun isConnected(): Boolean = service != null

    /** Binds the user service and returns the proxy, or null when Shizuku is not ready. */
    suspend fun acquire(timeoutMs: Long = 15_000): IShellService? {
        service?.let { return it }
        if (!Shizuku.pingBinder() || Shizuku.getVersion() < MIN_SHIZUKU_VERSION) return null
        mutex.withLock {
            service?.let { return it }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return null
            }
            val deferred = CompletableDeferred<IShellService>()
            pending?.completeExceptionally(IllegalStateException("superseded"))
            pending = deferred
            if (!bound) {
                bound = true
                try {
                    Shizuku.bindUserService(args, connection)
                } catch (t: Throwable) {
                    bound = false
                    pending = null
                    Log.e(TAG, "bindUserService failed", t)
                    return null
                }
            }
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        }
    }

    /** Runs [cmd] as uid 2000, or returns [ShellResult.UNAVAILABLE]. */
    suspend fun run(cmd: String): ShellResult {
        val proxy = acquire() ?: return ShellResult.UNAVAILABLE
        return try {
            ShellResult.parse(proxy.runCommand(cmd))
        } catch (t: Throwable) {
            Log.e(TAG, "run failed: $cmd", t)
            service = null
            bound = false
            ShellResult.UNAVAILABLE
        }
    }

    fun release() {
        service = null
        if (bound) {
            bound = false
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    companion object {
        private const val TAG = "ShellServiceClient"
        private const val MIN_SHIZUKU_VERSION = 10
        private const val VERSION = 1

        /** AppOps and `cmd sensorservice` output is small, but bound the wait anyway. */
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
