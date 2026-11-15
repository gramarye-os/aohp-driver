// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.binder

import android.os.ParcelFileDescriptor
import android.util.Log
import com.android.internal.aohp.IAohpContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok get() = exitCode == 0
}

data class ServiceInfo(
    val serviceId: String,
    val pid: Int,
    val alive: Boolean,
    val startTime: Long,
    val uptimeSec: Long,
    val command: String,
)

data class CgroupDiag(
    val v2Detected: Boolean,
    val enabled: Boolean,
    val path: String,
    val memoryMaxConfigured: String,
)

data class Diagnose(
    val container: String,
    val template: String,
    val rootfsExists: Boolean,
    val npmCacheHostDir: Boolean,
    val openclawDevHostDir: Boolean,
    val cgroup: CgroupDiag?,
    val raw: String,
)

/** One unit as reported by containerd's UNIT op (aohp-driver docs/UNITS.md). */
data class UnitInfo(
    val name: String,
    val type: String,            // service | timer
    val serviceType: String,     // simple | oneshot
    val description: String,
    val loadState: String,       // loaded | error | transient
    val loadError: String,
    val enabled: Boolean,
    val active: String,          // active | activating | deactivating | inactive | failed
    val sub: String,
    val result: String,
    val mainPid: Int,
    val exitCode: Int,
    val exitSignalName: String,
    val nRestarts: Int,
    val uptimeSec: Long,
    val execStart: String,
    val restart: String,
    val restartInSec: Double,
    val timerUnit: String?,
    val nextElapse: Long,
    val lastTrigger: Long,
    val raw: JSONObject,
) {
    val isTimer get() = type == "timer"
    val baseName get() = name.removeSuffix(".service").removeSuffix(".timer")
    companion object {
        fun from(o: JSONObject): UnitInfo {
            val t = o.optJSONObject("timer")
            return UnitInfo(
                o.optString("name"), o.optString("type", "service"), o.optString("serviceType", "simple"),
                o.optString("description"), o.optString("loadState", "loaded"), o.optString("loadError"),
                o.optBoolean("enabled"), o.optString("active", "inactive"), o.optString("sub", "dead"),
                o.optString("result", "success"), o.optInt("mainPid"), o.optInt("exitCode", -1),
                o.optString("exitSignalName"), o.optInt("nRestarts"), o.optLong("uptimeSec"),
                o.optString("execStart"), o.optString("restart", "no"), o.optDouble("restartInSec", 0.0),
                t?.optString("unit"), t?.optLong("nextElapse") ?: 0L, t?.optLong("lastTrigger") ?: 0L, o)
        }
    }
}

/** Result of a UNIT op: the daemon's JSON (units list or one unit) or an error message. */
class UnitOpException(msg: String) : RuntimeException(msg)

data class Usage(
    val cgroupEnabled: Boolean,
    val cgroupPath: String,
    val memoryCurrent: Long,
    val memoryMax: String,
    val memoryPeak: Long,
    val cpuUsageUsec: Long,
    val pidsCurrent: Int,
)

/**
 * Typed Kotlin wrapper over IAohpContainer. All calls are suspend and run on
 * Dispatchers.IO; JSON field names mirror the stock AOHPAgentDriver parsers
 * (AohpContainerClient / AohpServiceInfo / CgroupUsage) and containerd.
 */
class ContainerService {
    companion object {
        const val TAG = "AohpDriver"
        const val SERVICE_NAME = "aohp_container"
        const val TEMPLATE_DIR = "/system/etc/aohp/rootfs-templates"
    }

    @Volatile private var svc: IAohpContainer? = null

    @Synchronized
    fun service(): IAohpContainer? {
        svc?.let { if (it.asBinder().isBinderAlive) return it else svc = null }
        val b = ServiceManagerCompat.getService(SERVICE_NAME) ?: return null
        return IAohpContainer.Stub.asInterface(b).also { svc = it }
    }

    val available: Boolean get() = service() != null

    private suspend inline fun <T> io(crossinline block: (IAohpContainer) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val s = service() ?: return@withContext Result.failure(
                IllegalStateException(ServiceManagerCompat.lastError ?: "aohp_container not available"))
            runCatching { block(s) }.onFailure { Log.e(TAG, "binder call failed", it) }
        }

    suspend fun listContainers(): Result<List<String>> = io { it.listContainers()?.toList() ?: emptyList() }

    /** Empty string on success, else daemon error message. */
    suspend fun createContainer(name: String, template: String): Result<String> =
        io { it.createContainer(name, template) ?: "" }

    suspend fun destroyContainer(name: String): Result<Boolean> = io { it.destroyContainer(name) }
    suspend fun resetContainer(name: String): Result<Boolean> = io { it.resetContainer(name) }

    suspend fun execSync(name: String, cmd: String, timeoutMs: Int): Result<ExecResult> = io {
        val json = it.execSync(name, cmd, timeoutMs)
        val o = JSONObject(json ?: "{}")
        ExecResult(o.optInt("exitCode", -1), o.optString("stdout", ""), o.optString("stderr", ""))
    }

    suspend fun openShell(name: String): Result<ParcelFileDescriptor> =
        io { it.openShell(name) ?: throw IllegalStateException("openShell returned null") }

    suspend fun templateInfo(name: String): Result<String> = io { it.templateInfo(name) ?: "" }

    suspend fun startService(name: String, id: String, cmd: String): Result<Long> = io { it.startService(name, id, cmd) }
    suspend fun stopService(name: String, id: String): Result<Boolean> = io { it.stopService(name, id) }

    suspend fun listServices(name: String): Result<List<ServiceInfo>> = io {
        val arr = JSONArray(it.listServices(name) ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ServiceInfo(
                o.optString("serviceId", ""), o.optInt("pid", -1), o.optBoolean("alive", false),
                o.optLong("startTime", 0), o.optLong("uptimeSec", 0), o.optString("command", ""))
        }
    }

    suspend fun serviceLog(name: String, id: String, tailBytes: Int): Result<String> =
        io { it.serviceLog(name, id, tailBytes) ?: "" }

    suspend fun getUsage(name: String): Result<Usage> = io {
        val o = JSONObject(it.getUsage(name) ?: "{}")
        Usage(o.optBoolean("cgroupEnabled", false), o.optString("cgroupPath", ""),
            o.optLong("memoryCurrent", 0), o.optString("memoryMax", ""), o.optLong("memoryPeak", 0),
            o.optLong("cpuUsageUsec", 0), o.optInt("pidsCurrent", 0))
    }

    suspend fun diagnose(name: String): Result<Diagnose> = io {
        val raw = it.diagnose(name) ?: "{}"
        val o = JSONObject(raw)
        val cg = o.optJSONObject("cgroup")?.let { c ->
            CgroupDiag(c.optBoolean("cgroupV2Detected", false), c.optBoolean("cgroupEnabled", false),
                c.optString("cgroupPath", ""), c.optString("memoryMaxConfigured", ""))
        }
        Diagnose(o.optString("container", name), o.optString("template", ""), o.optBoolean("rootfsExists", false),
            o.optBoolean("npmCacheHostDir", false), o.optBoolean("openclawDevHostDir", false), cg, raw)
    }

        // ---- units (IAohpContainer.unitControl, added with containerd units; older images throw) ----

    /** Raw unit op; throws UnitOpException on a daemon error or when the image has no unitControl. */
    suspend fun unitControl(env: String, op: String, args: JSONObject = JSONObject()): Result<JSONObject> = io {
        val s = try {
            it.unitControl(env, op, args.toString())
        } catch (e: Throwable) {
            // android.os.RemoteException / UnsupportedOperationException on an image without the method
            throw UnitOpException("unitControl not available on this image (" + e.javaClass.simpleName + ")")
        }
        val o = JSONObject(s ?: "{}")
        if (o.optBoolean("error", false)) throw UnitOpException(o.optString("message", "unit op failed"))
        o
    }

    /** Whether the daemon/framework support units at all (cached after the first answer). */
    @Volatile var unitsSupported: Boolean? = null

    suspend fun listUnits(env: String): Result<List<UnitInfo>> = unitControl(env, "list").map { o ->
        unitsSupported = true
        parseUnits(o.optJSONArray("units"))
    }.onFailure { if (it is UnitOpException && it.message?.contains("not available") == true) unitsSupported = false }

    suspend fun unitOp(env: String, op: String, unit: String, now: Boolean = false): Result<UnitInfo> =
        unitControl(env, op, JSONObject().put("unit", unit).put("now", now)).map { UnitInfo.from(it) }

    suspend fun unitLog(env: String, unit: String, tailBytes: Int): Result<String> =
        unitControl(env, "log", JSONObject().put("unit", unit).put("tailBytes", tailBytes)).map { it.optString("log") }

    /** env-start / env-stop / daemon-reload: returns the full unit list plus started/failed arrays. */
    suspend fun unitEnvOp(env: String, op: String): Result<JSONObject> = unitControl(env, op)

    fun parseUnits(arr: JSONArray?): List<UnitInfo> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { UnitInfo.from(it) } }

    /** Template names = *.tar.gz in the (world-readable) system template dir. */
    fun listTemplates(): List<String> =
        File(TEMPLATE_DIR).list()?.filter { it.endsWith(".tar.gz") }?.map { it.removeSuffix(".tar.gz") }?.sorted()
            ?: emptyList()
}
