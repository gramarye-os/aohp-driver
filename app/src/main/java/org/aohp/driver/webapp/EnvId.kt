// Copyright (C) 2026 Chris Anderson. Licensed under the Apache License, Version 2.0.

package org.aohp.driver.webapp

import android.content.Context
import android.util.Log
import org.aohp.driver.bridge.AohpContainerClient
import java.util.UUID

/**
 * Which env a web app was made in, beyond the env's name.
 *
 * An env has no identity of its own: destroy "oc" and make a new "oc", and everything that knew the
 * old one by name would be pointed at the new one. So the Driver puts a random id in a file in the
 * env, and a web app's record keeps the id of the env it was pinned from. A destroy or a reset
 * removes the env's files and the id with them; whatever exists under that name afterwards has no
 * id or another one. This also notices an env that was replaced without the Driver being involved.
 */
object EnvId {
    private const val TAG = "AohpWebApp"
    private const val FILE = "/etc/aohp/env-id"
    private const val END = "__env_id_end__"
    private const val TIMEOUT_MS = 10_000
    private val ID = Regex("^[0-9a-fA-F-]{16,64}$")

    enum class Verdict { SAME, GONE, UNKNOWN }

    /** The env's id, written first if the env has none. Null when the command could not run. */
    fun ensure(c: AohpContainerClient, env: String): String? {
        val fresh = UUID.randomUUID().toString()
        val cmd = "[ -s " + FILE + " ] || { mkdir -p /etc/aohp && echo " + fresh + " > " + FILE + "; }; cat " + FILE
        val r = try { c.execSync(env, cmd, TIMEOUT_MS) } catch (t: Throwable) { null } ?: return null
        if (!r.success) return null
        return (r.output ?: "").trim().takeIf { ID.matches(it) }
    }

    /** The env's id: "" when the env has none, null when the command could not run. */
    private fun read(c: AohpContainerClient, env: String): String? {
        val r = try { c.execSync(env, "cat " + FILE + " 2>/dev/null; echo; echo " + END, TIMEOUT_MS) } catch (t: Throwable) { null } ?: return null
        val out = r.output ?: ""
        if (!r.success || !out.contains(END)) return null
        return out.lineSequence().firstOrNull()?.trim()?.takeIf { ID.matches(it) } ?: ""
    }

    /**
     * Is [app]'s env still the one it was pinned from? Only a definite answer is GONE: when the env
     * list or the command is not to be had, the answer is UNKNOWN and the caller changes nothing.
     * A record from before env ids (no id stored) takes the id of the env it finds.
     */
    fun verify(ctx: Context, c: AohpContainerClient, app: WebApp): Verdict {
        val envs = try { c.listContainers() } catch (t: Throwable) { null }
        // An empty list can also mean that the container service did not answer.
        if (envs == null || envs.isEmpty()) return Verdict.UNKNOWN
        if (app.env !in envs) { Log.i(TAG, "verify id=" + app.id + ": env " + app.env + " is gone"); return Verdict.GONE }
        val now = read(c, app.env) ?: return Verdict.UNKNOWN
        if (app.envId.isEmpty()) {
            val id = now.ifEmpty { ensure(c, app.env) ?: return Verdict.UNKNOWN }
            WebAppRegistry.get(ctx).put(app.copy(envId = id))
            Log.i(TAG, "verify id=" + app.id + ": record had no env id, took " + id.take(8))
            return Verdict.SAME
        }
        if (now == app.envId) return Verdict.SAME
        Log.i(TAG, "verify id=" + app.id + ": env " + app.env + " is another one now (" + app.envId.take(8) + " -> " + (if (now.isEmpty()) "none" else now.take(8)) + ")")
        return Verdict.GONE
    }
}
