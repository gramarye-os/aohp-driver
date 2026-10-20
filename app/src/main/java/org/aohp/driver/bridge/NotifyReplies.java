package org.aohp.driver.bridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Ring buffer of notification replies/dismissals produced by {@link NotifyReplyReceiver}, drained
 * by the bridge op {@code sys.notify_replies {after, waitMs}}. Drain-with-cursor (monotonic
 * {@code seq}) so a client that was down can catch up; bounded so a client that never drains
 * cannot grow the heap. Doubles as the replay buffer once the ws server pushes events.
 */
public final class NotifyReplies {
    private static final int MAX = 500;
    private static final ArrayDeque<JSONObject> BUF = new ArrayDeque<>();
    private static long seq = 0;

    private NotifyReplies() {}

    /** kind: "reply" | "dismiss". text may be null for dismiss. */
    public static synchronized void add(String kind, String tag, int id, String text,
                                        String correlationId, String env, String source) {
        try {
            JSONObject o = new JSONObject();
            o.put("seq", ++seq).put("kind", kind).put("tag", tag).put("id", id)
             .put("ts", System.currentTimeMillis());
            if (text != null) o.put("text", text);
            if (correlationId != null && !correlationId.isEmpty()) o.put("correlationId", correlationId);
            if (env != null && !env.isEmpty()) o.put("env", env);
            if (source != null) o.put("source", source);
            BUF.addLast(o);
            while (BUF.size() > MAX) BUF.removeFirst();
            NotifyReplies.class.notifyAll();
        } catch (JSONException ignored) { }
    }

    public static synchronized long lastSeq() { return seq; }

    /** Entries with seq > after; blocks up to waitMs for at least one if none are pending. */
    public static synchronized JSONObject drain(long after, long waitMs) throws JSONException {
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        while (seq <= after && waitMs > 0) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try { NotifyReplies.class.wait(left); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : BUF) if (o.optLong("seq") > after) out.add(o);
        JSONObject r = new JSONObject();
        r.put("ok", true).put("after", after).put("lastSeq", seq)
         .put("dropped", out.isEmpty() ? false : out.get(0).optLong("seq") > after + 1 && after > 0)
         .put("events", new JSONArray(out));
        return r;
    }
}
