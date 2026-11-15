// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.bridge;

import android.app.appfunctions.AppFunctionException;
import android.app.appfunctions.AppFunctionManager;
import android.app.appfunctions.ExecuteAppFunctionRequest;
import android.app.appfunctions.ExecuteAppFunctionResponse;
import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GlobalSearchSession;
import android.app.appsearch.JoinSpec;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Bridge methods {@code appfn.list} / {@code appfn.execute}: the Driver as a privileged
 * AppFunctions caller (android.permission.EXECUTE_APP_FUNCTIONS, internal|privileged on
 * Android 16 while app_function_access_api_enabled is off).
 *
 * <p>{@code appfn.list} queries the AppSearch index the platform apps-indexer fills from each
 * publisher's app_functions.xml (database apps-db, namespace app_functions, owner package
 * "android"), joined to the runtime metadata document for the enabled flag — the same query
 * {@code cmd app_function list-app-functions} runs. {@code appfn.execute} is
 * {@link AppFunctionManager#executeAppFunction} with JSON<->GenericDocument conversion ported
 * from the service's AppSearchDataJsonConverter.
 */
public final class AppFunctionsClient {
    private static final String TAG = "AohpAppFn";

    // AppFunctionStaticMetadataHelper / AppFunctionRuntimeMetadata (frameworks/base, @hide)
    private static final String INDEXER_PACKAGE = "android";
    private static final String STATIC_NAMESPACE = "app_functions";
    private static final String STATIC_SCHEMA_TYPE = "AppFunctionStaticMetadata";
    private static final String RUNTIME_SCHEMA_TYPE = "AppFunctionRuntimeMetadata";
    private static final String PROP_STATIC_QUALIFIED_ID = "appFunctionStaticMetadataQualifiedId";
    private static final String PROP_FUNCTION_ID = "functionId";
    private static final String PROP_PACKAGE_NAME = "packageName";
    private static final String PROP_SCHEMA_NAME = "schemaName";
    private static final String PROP_SCHEMA_CATEGORY = "schemaCategory";
    private static final String PROP_SCHEMA_VERSION = "schemaVersion";
    private static final String PROP_ENABLED_BY_DEFAULT = "enabledByDefault";
    private static final String PROP_RESTRICT_CALLERS = "restrictCallersWithExecuteAppFunctions";
    private static final String PROP_ENABLED = "enabled";

    private static final long DEFAULT_TIMEOUT_SEC = 30;
    private static final long MAX_TIMEOUT_SEC = 600;

    private final Context mContext;
    private final Executor mDirect = Runnable::run;

    public AppFunctionsClient(Context context) {
        mContext = context.getApplicationContext();
    }

    /** params: {category?: string, package?: string, verbose?: bool} */
    public CompletableFuture<JSONObject> list(JSONObject p) {
        final String category = p.optString("category", "");
        final String pkg = p.optString("package", "");
        final boolean verbose = p.optBoolean("verbose", false);
        AppSearchManager asm = mContext.getSystemService(AppSearchManager.class);
        if (asm == null) {
            return CompletableFuture.completedFuture(err("appfn", "AppSearchManager unavailable"));
        }
        CompletableFuture<JSONObject> out = new CompletableFuture<>();
        asm.createGlobalSearchSession(mDirect, sessionResult -> {
            if (!sessionResult.isSuccess()) {
                out.complete(err("appfn", "createGlobalSearchSession: "
                        + sessionResult.getErrorMessage()));
                return;
            }
            GlobalSearchSession session = sessionResult.getResultValue();
            try {
                SearchResults results = session.search("", buildSpec());
                JSONArray functions = new JSONArray();
                drainPages(results, functions, category, pkg, verbose, () -> {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("functions", functions);
                        o.put("count", functions.length());
                        out.complete(o);
                    } catch (JSONException e) {
                        out.complete(err("json", e.getMessage()));
                    } finally {
                        closeQuietly(results);
                        closeQuietly(session);
                    }
                }, msg -> {
                    out.complete(err("appfn", msg));
                    closeQuietly(results);
                    closeQuietly(session);
                });
            } catch (RuntimeException e) {
                closeQuietly(session);
                out.complete(err("appfn", "search: " + e));
            }
        });
        return out;
    }

    private interface Done { void run(); }
    private interface Fail { void run(String msg); }

    private void drainPages(SearchResults results, JSONArray sink, String category, String pkg,
                            boolean verbose, Done done, Fail fail) {
        results.getNextPage(mDirect, pageResult -> {
            if (!pageResult.isSuccess()) {
                fail.run("getNextPage: " + pageResult.getErrorMessage());
                return;
            }
            List<SearchResult> page = pageResult.getResultValue();
            if (page == null || page.isEmpty()) {
                done.run();
                return;
            }
            for (SearchResult r : page) {
                try {
                    JSONObject fn = toFunctionJson(r, verbose);
                    if (fn == null) continue;
                    if (!category.isEmpty() && !category.equals(fn.optString("schemaCategory"))) continue;
                    if (!pkg.isEmpty() && !pkg.equals(fn.optString("package"))) continue;
                    sink.put(fn);
                } catch (JSONException e) {
                    Log.w(TAG, "skip result: " + e);
                }
            }
            drainPages(results, sink, category, pkg, verbose, done, fail);
        });
    }

    private static SearchSpec buildSpec() {
        SearchSpec runtimeSpec = new SearchSpec.Builder()
                .addFilterPackageNames(INDEXER_PACKAGE)
                .addFilterSchemas(RUNTIME_SCHEMA_TYPE)
                .build();
        JoinSpec join = new JoinSpec.Builder(PROP_STATIC_QUALIFIED_ID)
                .setNestedSearch("", runtimeSpec)
                .build();
        return new SearchSpec.Builder()
                .addFilterPackageNames(INDEXER_PACKAGE)
                .addFilterNamespaces(STATIC_NAMESPACE)
                .addFilterSchemas(STATIC_SCHEMA_TYPE)
                .setJoinSpec(join)
                .setResultCountPerPage(100)
                .build();
    }

    private static JSONObject toFunctionJson(SearchResult r, boolean verbose) throws JSONException {
        GenericDocument d = r.getGenericDocument();
        if (!d.getSchemaType().startsWith(STATIC_SCHEMA_TYPE)) return null;
        JSONObject o = new JSONObject();
        o.put("package", d.getPropertyString(PROP_PACKAGE_NAME));
        o.put("functionId", d.getPropertyString(PROP_FUNCTION_ID));
        putIfPresent(o, "schemaName", d, PROP_SCHEMA_NAME);
        putIfPresent(o, "schemaCategory", d, PROP_SCHEMA_CATEGORY);
        putIfPresent(o, "schemaVersion", d, PROP_SCHEMA_VERSION);
        putIfPresent(o, "enabledByDefault", d, PROP_ENABLED_BY_DEFAULT);
        putIfPresent(o, "restrictCallers", d, PROP_RESTRICT_CALLERS);
        // Effective enabled state: runtime doc overrides, else enabledByDefault.
        Boolean enabled = null;
        for (SearchResult joined : r.getJoinedResults()) {
            GenericDocument rt = joined.getGenericDocument();
            if (rt.getSchemaType().startsWith(RUNTIME_SCHEMA_TYPE)) {
                Boolean b = readBool(rt, PROP_ENABLED);
                if (b != null) enabled = b;
            }
        }
        if (enabled == null) enabled = readBool(d, PROP_ENABLED_BY_DEFAULT);
        if (enabled != null) o.put("enabled", enabled);
        if (verbose) o.put("static", docToJson(d));
        return o;
    }

    /**
     * Boolean property that may be stored as boolean[] (static metadata's enabledByDefault) or
     * long[] 0/1 (the runtime metadata's "enabled", written by AppFunctionManagerService's
     * set-enabled path). getPropertyBoolean() on the long form throws ClassCastException inside
     * AppSearch and yields false — seen on 23.2 after `cmd app_function set-enabled`.
     */
    private static Boolean readBool(GenericDocument d, String prop) {
        Object v = d.getProperty(prop);
        if (v == null) return null;
        if (v.getClass().isArray()) {
            if (Array.getLength(v) == 0) return null;
            v = Array.get(v, 0);
        }
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).longValue() != 0;
        if (v instanceof String) return Boolean.parseBoolean((String) v) || "1".equals(v);
        return null;
    }

    private static void putIfPresent(JSONObject o, String key, GenericDocument d, String prop)
            throws JSONException {
        Object v = d.getProperty(prop);
        if (v == null) return;
        if (v.getClass().isArray() && Array.getLength(v) == 1) {
            o.put(key, Array.get(v, 0));
        } else {
            o.put(key, v);
        }
    }

    /** params: {package, function, params?: object, timeoutSec?: number} */
    public CompletableFuture<JSONObject> execute(JSONObject p) {
        final String pkg = p.optString("package", "");
        final String fn = p.optString("function", "");
        if (pkg.isEmpty() || fn.isEmpty()) {
            return CompletableFuture.completedFuture(err("params", "package and function required"));
        }
        long timeoutSec = p.optLong("timeoutSec", DEFAULT_TIMEOUT_SEC);
        timeoutSec = Math.max(1, Math.min(MAX_TIMEOUT_SEC, timeoutSec));
        AppFunctionManager mgr = mContext.getSystemService(AppFunctionManager.class);
        if (mgr == null) {
            return CompletableFuture.completedFuture(err("appfn", "AppFunctionManager unavailable"));
        }
        GenericDocument params;
        try {
            JSONObject pj = p.optJSONObject("params");
            params = jsonToDoc(pj == null ? new JSONObject() : pj);
        } catch (JSONException e) {
            return CompletableFuture.completedFuture(err("params", "params: " + e.getMessage()));
        }
        ExecuteAppFunctionRequest req = new ExecuteAppFunctionRequest.Builder(pkg, fn)
                .setParameters(params)
                .build();
        CompletableFuture<JSONObject> out = new CompletableFuture<>();
        CancellationSignal cancel = new CancellationSignal();
        final long t0 = System.currentTimeMillis();
        try {
            mgr.executeAppFunction(req, mDirect, cancel,
                    new OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException>() {
                        @Override
                        public void onResult(ExecuteAppFunctionResponse resp) {
                            try {
                                JSONObject o = new JSONObject();
                                o.put("package", pkg);
                                o.put("function", fn);
                                o.put("result", docToJson(resp.getResultDocument()));
                                JSONArray grants = uriGrants(resp);
                                if (grants != null) o.put("uriGrants", grants);
                                if (!resp.getExtras().isEmpty()) {
                                    o.put("extraKeys", new JSONArray(resp.getExtras().keySet()));
                                }
                                o.put("elapsedMs", System.currentTimeMillis() - t0);
                                out.complete(o);
                            } catch (JSONException e) {
                                out.complete(err("json", e.getMessage()));
                            }
                        }

                        @Override
                        public void onError(AppFunctionException e) {
                            JSONObject o = err("appfn_" + e.getErrorCode(),
                                    Objects.toString(e.getErrorMessage(), "app function error"));
                            try {
                                o.put("errorCode", e.getErrorCode());
                                o.put("errorCategory", e.getErrorCategory());
                                o.put("package", pkg);
                                o.put("function", fn);
                            } catch (JSONException ignored) {}
                            out.complete(o);
                        }
                    });
        } catch (RuntimeException e) {
            // SecurityException when the caller lacks EXECUTE_APP_FUNCTIONS / is not allowlisted.
            return CompletableFuture.completedFuture(err("appfn", e.toString()));
        }
        return out.completeOnTimeout(err("timeout", "no response in " + timeoutSec + "s"),
                        timeoutSec, TimeUnit.SECONDS)
                .whenComplete((v, t) -> { if (v != null && "timeout".equals(v.optString("code"))) cancel.cancel(); });
    }

    /** getUriGrants() is @FlaggedApi(app_function_access_api_enabled); reflect so a build
     *  with the flag off (Android 16 bp2a) degrades to "no grants" instead of failing. */
    private static JSONArray uriGrants(ExecuteAppFunctionResponse resp) {
        try {
            Object list = ExecuteAppFunctionResponse.class.getMethod("getUriGrants").invoke(resp);
            if (!(list instanceof List)) return null;
            JSONArray arr = new JSONArray();
            for (Object g : (List<?>) list) {
                JSONObject o = new JSONObject();
                o.put("uri", String.valueOf(g.getClass().getMethod("getUri").invoke(g)));
                o.put("modeFlags", g.getClass().getMethod("getModeFlags").invoke(g));
                arr.put(o);
            }
            return arr.length() == 0 ? null : arr;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- JSON <-> GenericDocument (ported from com.android.server.appfunctions.AppSearchDataJsonConverter)

    static GenericDocument jsonToDoc(JSONObject json) throws JSONException {
        String id = json.optString("id", "");
        String namespace = json.optString("namespace", "");
        String schemaType = json.optString("schemaType", "");
        GenericDocument.Builder<?> b = new GenericDocument.Builder<>(namespace, id, schemaType);
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object v = json.get(key);
            if (v instanceof String) {
                b.setPropertyString(key, (String) v);
            } else if (v instanceof Integer || v instanceof Long) {
                b.setPropertyLong(key, ((Number) v).longValue());
            } else if (v instanceof Double || v instanceof Float) {
                b.setPropertyDouble(key, ((Number) v).doubleValue());
            } else if (v instanceof Boolean) {
                b.setPropertyBoolean(key, (Boolean) v);
            } else if (v instanceof JSONObject) {
                b.setPropertyDocument(key, jsonToDoc((JSONObject) v));
            } else if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                if (a.length() == 0) continue;
                Object first = a.get(0);
                if (first instanceof String) {
                    String[] arr = new String[a.length()];
                    for (int i = 0; i < a.length(); i++) arr[i] = a.optString(i, null);
                    b.setPropertyString(key, arr);
                } else if (first instanceof Integer || first instanceof Long) {
                    long[] arr = new long[a.length()];
                    for (int i = 0; i < a.length(); i++) arr[i] = a.getLong(i);
                    b.setPropertyLong(key, arr);
                } else if (first instanceof Double || first instanceof Float) {
                    double[] arr = new double[a.length()];
                    for (int i = 0; i < a.length(); i++) arr[i] = a.getDouble(i);
                    b.setPropertyDouble(key, arr);
                } else if (first instanceof Boolean) {
                    boolean[] arr = new boolean[a.length()];
                    for (int i = 0; i < a.length(); i++) arr[i] = a.getBoolean(i);
                    b.setPropertyBoolean(key, arr);
                } else if (first instanceof JSONObject) {
                    GenericDocument[] arr = new GenericDocument[a.length()];
                    for (int i = 0; i < a.length(); i++) arr[i] = jsonToDoc(a.getJSONObject(i));
                    b.setPropertyDocument(key, arr);
                }
            }
        }
        return b.build();
    }

    /** Every property becomes a JSON array (GenericDocument cannot tell singular from repeated). */
    static JSONObject docToJson(GenericDocument d) throws JSONException {
        JSONObject o = new JSONObject();
        for (String name : d.getPropertyNames()) {
            Object v = d.getProperty(name);
            if (v == null) { o.put(name, JSONObject.NULL); continue; }
            JSONArray arr = new JSONArray();
            if (v instanceof GenericDocument[]) {
                for (GenericDocument n : (GenericDocument[]) v) arr.put(docToJson(n));
            } else if (v.getClass().isArray()) {
                int n = Array.getLength(v);
                for (int i = 0; i < n; i++) arr.put(Array.get(v, i));
            } else {
                arr.put(v);
            }
            o.put(name, arr);
        }
        return o;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }

    private static JSONObject err(String code, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", true);
            o.put("code", code);
            o.put("message", msg);
        } catch (JSONException ignored) {}
        return o;
    }
}
