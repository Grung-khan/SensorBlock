/*
 * The interface implemented by ShellUserService, which Shizuku instantiates
 * inside a process running as uid 2000 (shell). That identity is what lets us
 * run the handful of commands that have no Binder entry point we can mirror
 * safely:
 *
 *     appops set <pkg> CAMERA|RECORD_AUDIO|BODY_SENSORS|ACTIVITY_RECOGNITION ...
 *     cmd sensorservice set-uid-state <pkg> idle|active|reset
 *     dumpsys sensorservice ...
 *
 * Method 16777114 is reserved by the Shizuku server for the lifecycle
 * `destroy()` callback and MUST keep that exact id. All app-defined methods
 * must therefore start at a low number and never collide with it.
 */
package com.sensorblock.app.shell;

interface IShellService {
    /** Reserved by the Shizuku server; do not renumber. */
    void destroy() = 16777114;

    /** Runs `cmd` through `sh -c` as uid 2000 and returns stdout+stderr, exit code prefixed. */
    String runCommand(String cmd) = 1;

    /** Convenience: runs `cmd` and returns only stdout, trimmed. */
    String runCommandStdout(String cmd) = 2;

    /** uid the service is actually running as (should be 2000). */
    int uid() = 3;
}
