// Copyright (C) 2026 Chris Anderson. Licensed under the Apache License, Version 2.0.

package org.aohp.driver.webapp

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebStorage
import org.aohp.driver.bridge.AohpContainerClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Bridge methods webapp.pin / list / remove / open: an env puts a web app it made on the home
 * screen. Nothing is installed; the shortcut opens [WebAppActivity] on the env's files or server.
 */
object WebApps {
    private const val TAG = "AohpWebApp"
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,31}\$")
    private val UNIT = Regex("^[A-Za-z0-9@._-]{1,64}\$")
    private val COLOR = Regex("^#[0-9a-fA-F]{6}\$")
    private const val ICON_MAX_BYTES = 1 shl 20

    private fun err(code: String, msg: String): JSONObject =
        JSONObject().put("error", true).put("code", code).put("message", msg)

    fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).trim('-')

    /** The origin a "dir" app is shown under. It never resolves: every request is answered by the Driver. */
    fun host(id: String): String = id + ".webapp.invalid"

    fun uri(id: String): Uri = Uri.parse("aohp-webapp://app/" + id)

    private fun shortcutId(id: String) = "webapp-" + id

    private fun iconFile(ctx: Context, id: String) = File(File(ctx.filesDir, "webapps").apply { mkdirs() }, id + ".png")

    private fun launchIntent(ctx: Context, id: String): Intent =
        Intent(Intent.ACTION_VIEW, uri(id)).setClass(ctx, WebAppActivity::class.java)

    @JvmStatic
    fun pin(ctx: Context, c: AohpContainerClient, p: JSONObject): JSONObject {
        val name = p.optString("name").trim()
        if (name.isEmpty() || name.length > 40) return err("name", "name: 1 to 40 characters")
        val id = p.optString("id").trim().ifEmpty { slug(name) }
        if (!ID.matches(id)) return err("id", "id: lower-case letters, digits and '-', at most 32 characters")
        val envs = c.listContainers().toList()
        val env = p.optString("env").trim().ifEmpty { if (envs.size == 1) envs[0] else "" }
        if (env.isEmpty() || env !in envs)
            return err("env", if (envs.isEmpty()) "there is no env on this phone" else "env: one of " + envs.joinToString())
        val dir = p.optString("dir").trim()
        val url = p.optString("url").trim()
        if (dir.isEmpty() == url.isEmpty())
            return err("mode", "give either dir (a folder of files, no network) or url (a server on 127.0.0.1)")
        val color = p.optString("color").trim()
        if (color.isNotEmpty() && !COLOR.matches(color)) return err("color", "color: #rrggbb")
        val immersive = p.optBoolean("immersive", false)
        val now = System.currentTimeMillis()

        val app0: WebApp
        if (dir.isNotEmpty()) {
            val root = dir.trimEnd('/')
            val entry = p.optString("entry").trim().trimStart('/').ifEmpty { "index.html" }
            if (!root.startsWith("/") || EnvFiles.unsafe(root) || EnvFiles.unsafe(entry))
                return err("dir", "dir: an absolute path to the app's folder in the env")
            EnvFiles.open(c, env, root, entry) ?: return err("dir", "no file " + entry + " in " + root + " of env " + env)
            app0 = WebApp(id, name, env, "dir", dir = root, entry = entry, color = color, immersive = immersive, createdMs = now)
        } else {
            val u = Uri.parse(url)
            val h = u.host ?: ""
            if (u.scheme != "http" || (h != "127.0.0.1" && h != "localhost") || u.port <= 0)
                return err("url", "url: http://127.0.0.1:<port>/ of a server in the env (bind the server to 127.0.0.1)")
            val unit = p.optString("unit").trim()
            if (unit.isNotEmpty() && !UNIT.matches(unit)) return err("unit", "unit: a unit name such as mygame.service")
            app0 = WebApp(id, name, env, "url", url = url, unit = unit, color = color, immersive = immersive, createdMs = now)
        }

        val iconPath = p.optString("icon").trim()
        val file = iconFile(ctx, id)
        if (iconPath.isNotEmpty()) {
            if (!iconPath.startsWith("/") || EnvFiles.unsafe(iconPath))
                return err("icon", "icon: an absolute path to a PNG, JPEG or WebP file in the env")
            val parent = iconPath.substringBeforeLast('/').ifEmpty { "/" }
            val bytes = EnvFiles.readAll(c, env, parent, iconPath.substringAfterLast('/'), ICON_MAX_BYTES)
                ?: return err("icon", "cannot read " + iconPath + " in env " + env + " (missing, or larger than 1 MB)")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return err("icon", iconPath + " is not an image")
            val scaled = Bitmap.createScaledBitmap(bmp, 192, 192, true)
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            file.delete()
        }

        // Remember which env this is, not only its name. If the id cannot be had now, the record takes it at first open.
        val app = app0.copy(envId = EnvId.ensure(c, env) ?: "")
        WebAppRegistry.get(ctx).put(app)
        WebAppActivity.closeIfDropped(id)
        Log.i(TAG, "pin id=" + id + " mode=" + app.mode + " env=" + env + (if (app.mode == "dir") " dir=" + app.dir else " url=" + app.url))

        val wantPin = p.optBoolean("pin", true)
        var asked = false
        var already = false
        if (wantPin) {
            val sm = ctx.getSystemService(ShortcutManager::class.java)
            if (sm != null) {
                val info = shortcut(ctx, app)
                already = sm.pinnedShortcuts.any { it.id == info.id }
                if (already) {
                    runCatching { sm.enableShortcuts(listOf(info.id)); sm.updateShortcuts(listOf(info)) }
                } else if (sm.isRequestPinShortcutSupported) {
                    asked = runCatching { sm.requestPinShortcut(info, null) }.getOrDefault(false)
                }
            }
        }
        val q = "\"" + name + "\""
        val msg = when {
            already -> "Updated. " + q + " is already on the home screen."
            asked -> "The phone is asking its user to add " + q + " to the home screen."
            wantPin -> "Registered, but this launcher cannot add a shortcut. Open it with webapp.open."
            else -> "Registered without a home-screen shortcut. Open it with webapp.open."
        }
        return app.toJson().put("ok", true).put("onHomeScreen", already).put("askedUser", asked).put("message", msg)
    }

    @JvmStatic
    fun list(ctx: Context): JSONObject {
        val pinned = runCatching {
            ctx.getSystemService(ShortcutManager::class.java)?.pinnedShortcuts?.filter { it.isEnabled }?.map { it.id }?.toSet()
        }.getOrNull() ?: emptySet()
        val arr = JSONArray()
        WebAppRegistry.get(ctx).all().forEach { arr.put(it.toJson().put("onHomeScreen", shortcutId(it.id) in pinned)) }
        return JSONObject().put("apps", arr)
    }

    @JvmStatic
    fun remove(ctx: Context, p: JSONObject): JSONObject {
        val id = p.optString("id").trim()
        val reg = WebAppRegistry.get(ctx)
        val app = reg.get(id) ?: return err("id", "no web app with id '" + id + "'")
        drop(ctx, app, "This app was removed", closeWindow = true)
        Log.i(TAG, "remove id=" + id)
        return JSONObject().put("ok", true).put("id", id)
            .put("message", "Removed \"" + app.name + "\". Its home-screen icon stays until the user drags it away.")
    }

    /** Take an app off the phone: its record, icon file and stored page data. The home-screen icon is disabled. */
    private fun drop(ctx: Context, app: WebApp, why: String, closeWindow: Boolean) {
        WebAppRegistry.get(ctx).remove(app.id)
        iconFile(ctx, app.id).delete()
        if (closeWindow) WebAppActivity.close(app.id)
        // A pinned shortcut cannot be taken off the home screen by its app; it is disabled and says why.
        runCatching { ctx.getSystemService(ShortcutManager::class.java)?.disableShortcuts(listOf(shortcutId(app.id)), why) }
        if (app.mode == "dir") Handler(Looper.getMainLooper()).post {
            runCatching { WebStorage.getInstance().deleteOrigin("https://" + host(app.id)) }
        }
    }

    const val ENV_GONE = "The env this app was made in no longer exists"

    /** The app's env turned out to be gone or replaced when the app was opened (see [EnvId.verify]). */
    fun dropStale(ctx: Context, app: WebApp) {
        drop(ctx, app, ENV_GONE, closeWindow = false)
        Log.i(TAG, "drop id=" + app.id + " env=" + app.env + " by=env-check")
    }

    /** An env was destroyed or reset: its files are gone, so the apps pinned from it go too. Returns their ids. */
    @JvmStatic
    fun forgetEnv(ctx: Context, env: String, why: String): List<String> {
        val gone = WebAppRegistry.get(ctx).all().filter { it.env == env }
        gone.forEach { drop(ctx, it, ENV_GONE, closeWindow = true); Log.i(TAG, "drop id=" + it.id + " env=" + env + " by=" + why) }
        return gone.map { it.id }
    }

    @JvmStatic
    fun open(ctx: Context, p: JSONObject): JSONObject {
        val id = p.optString("id").trim()
        val app = WebAppRegistry.get(ctx).get(id) ?: return err("id", "no web app with id '" + id + "'")
        return try {
            ctx.startActivity(launchIntent(ctx, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            JSONObject().put("ok", true).put("id", id).put("message", "Opened \"" + app.name + "\".")
        } catch (t: Throwable) {
            err("open", "could not open: " + t.message)
        }
    }

    private fun shortcut(ctx: Context, a: WebApp): ShortcutInfo {
        val f = iconFile(ctx, a.id)
        val custom = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        val icon = if (custom != null) Icon.createWithBitmap(custom) else Icon.createWithAdaptiveBitmap(letterIcon(a))
        return ShortcutInfo.Builder(ctx, shortcutId(a.id))
            .setShortLabel(a.name).setLongLabel(a.name).setIcon(icon)
            .setIntent(launchIntent(ctx, a.id)).build()
    }

    /** A plain coloured tile with the app's first letter, for apps that bring no icon. */
    private fun letterIcon(a: WebApp): Bitmap {
        val side = 432
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = runCatching { Color.parseColor(a.color) }.getOrElse {
            val hues = intArrayOf(0xFF1E6FD9.toInt(), 0xFF2E9E6B.toInt(), 0xFFB5651D.toInt(), 0xFF8E44AD.toInt(), 0xFFC0392B.toInt(), 0xFF16808A.toInt())
            hues[Math.floorMod(a.id.hashCode(), hues.size)]
        }
        canvas.drawColor(bg)
        val dark = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000 < 150
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.WHITE else Color.BLACK
            textSize = side * 0.36f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val letter = (a.name.firstOrNull { it.isLetterOrDigit() } ?: '?').uppercaseChar().toString()
        canvas.drawText(letter, side / 2f, side / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return bmp
    }
}
