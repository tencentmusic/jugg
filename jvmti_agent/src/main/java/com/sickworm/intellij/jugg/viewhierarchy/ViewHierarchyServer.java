package com.sickworm.intellij.jugg.viewhierarchy;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;

import com.sickworm.intellij.jugg.hotfix.LogUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ViewHierarchyServer serves layout/element/tap actions inside app process via LocalSocket.
 */
public class ViewHierarchyServer {

    private static final String TAG = "Jugg#ViewHierarchyServer";
    private static final String SOCKET_PREFIX = "jugg_vh_";
    private static final String PROTOCOL_VERSION = "1.1";
    private static final long MAIN_THREAD_TIMEOUT_MS = 5000L;

    private static volatile ViewHierarchyServer sInstance;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ViewTreeDumper viewTreeDumper = new ViewTreeDumper();
    private final ElementFinder elementFinder = new ElementFinder(viewTreeDumper);
    private final ViewTapper viewTapper = new ViewTapper();
    private final LayoutVerifier layoutVerifier = new LayoutVerifier(
        elementFinder,
        android.content.res.Resources.getSystem().getDisplayMetrics()
    );

    private volatile boolean running;

    private ViewHierarchyServer(Context appContext) {
        this.appContext = appContext;
    }

    /**
     * Start singleton server in background thread.
     */
    public static synchronized boolean start(Context context) {
        if (sInstance != null) {
            return true;
        }
        Context applicationContext = context.getApplicationContext();
        Context safeContext = applicationContext != null ? applicationContext : context;
        ViewHierarchyServer server = new ViewHierarchyServer(safeContext);
        try {
            server.startInternal();
            sInstance = server;
            return true;
        } catch (Throwable t) {
            LogUtils.e(TAG, "start failed", t);
            sInstance = null;
            return false;
        }
    }

    private void startInternal() {
        if (running) {
            return;
        }
        running = true;
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runServerLoop();
            }
        }, "jugg-view-hierarchy-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void runServerLoop() {
        String socketName = SOCKET_PREFIX + Process.myPid();
        LogUtils.i(TAG, "Starting ViewHierarchyServer on socket: " + socketName + " pkg=" + appContext.getPackageName());

        try (LocalServerSocket serverSocket = new LocalServerSocket(socketName)) {
            while (running) {
                LocalSocket client = null;
                try {
                    client = serverSocket.accept();
                    handleClient(client);
                } catch (Throwable t) {
                    LogUtils.e(TAG, "accept/handle client failed", t);
                } finally {
                    closeQuietly(client);
                }
            }
        } catch (Throwable t) {
            LogUtils.e(TAG, "ViewHierarchyServer loop crashed", t);
        }
    }

    private void handleClient(LocalSocket client) {
        if (client == null) {
            return;
        }
        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
        ) {
            String requestLine = reader.readLine();
            JSONObject response = dispatchRequest(requestLine);
            writer.write(response.toString());
            writer.write('\n');
            writer.flush();
        } catch (Throwable t) {
            LogUtils.e(TAG, "handleClient failed", t);
        }
    }

    private JSONObject dispatchRequest(String requestLine) {
        if (TextUtils.isEmpty(requestLine)) {
            return error("Empty request.", null);
        }

        try {
            JSONObject request = new JSONObject(requestLine);
            String action = request.optString("action", "");
            JSONObject params = request.optJSONObject("params");
            if (params == null) {
                params = new JSONObject();
            }

            switch (action) {
                case "layout_dump":
                    JSONObject finalParamsDump = params;
                    return runOnMainThread(new Callable<JSONObject>() {
                        @Override
                        public JSONObject call() {
                            return doLayoutDump(finalParamsDump);
                        }
                    });
                case "find_elements":
                    JSONObject finalParamsFind = params;
                    return runOnMainThread(new Callable<JSONObject>() {
                        @Override
                        public JSONObject call() {
                            return doFindElements(finalParamsFind);
                        }
                    });
                case "find_and_tap":
                    JSONObject finalParamsFindAndTap = params;
                    return runOnMainThread(new Callable<JSONObject>() {
                        @Override
                        public JSONObject call() {
                            return doFindAndTap(finalParamsFindAndTap);
                        }
                    });
                case "find_and_long_press":
                    JSONObject finalParamsFindAndLongPress = params;
                    return runOnMainThread(new Callable<JSONObject>() {
                        @Override
                        public JSONObject call() {
                            return doFindAndLongPress(finalParamsFindAndLongPress);
                        }
                    });
                case "tap_coordinate":
                    JSONObject finalParamsTap = params;
                    return runOnMainThread(new Callable<JSONObject>() {
                        @Override
                        public JSONObject call() {
                            return doTapCoordinate(finalParamsTap);
                        }
                    });
                case "verify":
                    JSONObject finalParamsVerify = params;
                    return runOnMainThread(new Callable<JSONObject>() {
                        @Override
                        public JSONObject call() {
                            return doVerify(finalParamsVerify);
                        }
                    });
                case "eval_view":
                    JSONObject finalParamsEval = params;
                    return runOnMainThread(new Callable<JSONObject>() {
                        @Override
                        public JSONObject call() {
                            return doEvalView(finalParamsEval);
                        }
                    });
                default:
                    return error("Unsupported action: " + action, null);
            }
        } catch (Throwable t) {
            LogUtils.e(TAG, "dispatchRequest parse failed", t);
            return error("Malformed request: " + t.getMessage(), null);
        }
    }

    private JSONObject runOnMainThread(Callable<JSONObject> task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return task.call();
            } catch (Throwable t) {
                LogUtils.e(TAG, "runOnMainThread direct call failed", t);
                return error("Main thread action failed: " + t.getMessage(), null);
            }
        }

        AtomicReference<JSONObject> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    resultRef.set(task.call());
                } catch (Throwable t) {
                    errorRef.set(t);
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            boolean finished = latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                return error("Main thread operation timeout.", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("Main thread operation interrupted.", null);
        }

        Throwable throwable = errorRef.get();
        if (throwable != null) {
            LogUtils.e(TAG, "runOnMainThread task failed", throwable);
            return error("Main thread action failed: " + throwable.getMessage(), null);
        }

        JSONObject result = resultRef.get();
        return result != null ? result : error("Empty main thread result.", null);
    }

    private JSONObject doLayoutDump(JSONObject params) {
        try {
            String rootLayout = optString(params, "rootLayout");
            boolean excludeGone = optBoolean(params, "excludeGone", false);
            boolean topWindowOnly = optBoolean(params, "topWindowOnly", true);
            return ok(viewTreeDumper.dumpWindowsJson(rootLayout, excludeGone, topWindowOnly));
        } catch (Throwable t) {
            LogUtils.e(TAG, "doLayoutDump failed", t);
            return error("layout_dump failed: " + t.getMessage(), null);
        }
    }

    private JSONObject doFindElements(JSONObject params) {
        String text = optString(params, "text");
        String resourceId = optString(params, "resourceId");
        String contentDesc = optString(params, "contentDesc");
        String className = optString(params, "className");
        boolean topWindowOnly = optBoolean(params, "topWindowOnly", true);

        try {
            List<MatchedElement> matches = elementFinder.find(text, resourceId, contentDesc, className, topWindowOnly);
            return ok(buildElementsData(matches));
        } catch (Throwable t) {
            LogUtils.e(TAG, "doFindElements failed", t);
            return error("find_elements failed: " + t.getMessage(), null);
        }
    }

    private JSONObject doFindAndTap(JSONObject params) {
        return doFindAndPress(params, false);
    }

    private JSONObject doFindAndLongPress(JSONObject params) {
        return doFindAndPress(params, true);
    }

    private JSONObject doFindAndPress(JSONObject params, boolean isLongPress) {
        String text = optString(params, "text");
        String resourceId = optString(params, "resourceId");
        String contentDesc = optString(params, "contentDesc");
        String className = optString(params, "className");
        Integer durationValue = optInt(params, "duration");
        int duration = durationValue != null ? Math.max(50, durationValue) : 500;
        boolean topWindowOnly = optBoolean(params, "topWindowOnly", true);
        String actionName = isLongPress ? "find_and_long_press" : "find_and_tap";

        try {
            List<MatchedElement> matches = elementFinder.find(text, resourceId, contentDesc, className, topWindowOnly);
            if (matches.isEmpty()) {
                List<MatchedElement> candidates = elementFinder.findClickableCandidates(5, topWindowOnly);
                JSONObject data = buildElementsData(candidates);
                data.put("matchCount", 0);
                return error("No matching UI element found.", data);
            }
            if (matches.size() > 1) {
                JSONObject data = buildElementsData(matches);
                return error("Multiple elements matched (" + matches.size() + "). Use coordinate mode to tap.", data);
            }

            MatchedElement target = matches.get(0);
            boolean pressed = isLongPress ? viewTapper.longPress(target, duration) : viewTapper.tap(target);
            JSONObject data = new JSONObject();
            data.put(isLongPress ? "longPressed" : "tapped", pressed);
            data.put("matchCount", 1);
            data.put("x", target.centerX);
            data.put("y", target.centerY);
            data.put("matchedElement", target.toMatchedElementJson());
            if (isLongPress) {
                data.put("duration", duration);
            }

            if (!pressed) {
                String failedAction = isLongPress ? "long press" : "tap";
                return error(actionName + " matched but " + failedAction + " dispatch failed.", data);
            }
            return ok(data);
        } catch (Throwable t) {
            LogUtils.e(TAG, "doFindAndPress failed", t);
            return error(actionName + " failed: " + t.getMessage(), null);
        }
    }

    private JSONObject doTapCoordinate(JSONObject params) {
        Integer x = optInt(params, "x");
        Integer y = optInt(params, "y");
        if (x == null || y == null) {
            return error("tap_coordinate requires x and y.", null);
        }

        try {
            boolean tapped = viewTapper.tapCoordinate(viewTreeDumper.getAllWindows(), x, y);
            JSONObject data = new JSONObject();
            data.put("x", x);
            data.put("y", y);
            data.put("tapped", tapped);
            if (!tapped) {
                return error("tap_coordinate dispatch failed.", data);
            }
            return ok(data);
        } catch (Throwable t) {
            LogUtils.e(TAG, "doTapCoordinate failed", t);
            return error("tap_coordinate failed: " + t.getMessage(), null);
        }
    }

    private JSONObject doVerify(JSONObject params) {
        try {
            return layoutVerifier.verify(params);
        } catch (Throwable t) {
            LogUtils.e(TAG, "doVerify failed", t);
            return error("verify failed: " + t.getMessage(), null);
        }
    }

    private JSONObject doEvalView(JSONObject params) {
        try {
            JSONObject targetObj = params.optJSONObject("target");
            if (targetObj == null) {
                return error("eval_view requires 'target' selector.", null);
            }

            String text = optString(targetObj, "text");
            String resourceId = optString(targetObj, "resourceId");
            String contentDesc = optString(targetObj, "contentDesc");
            String className = optString(targetObj, "className");

            JSONArray expressions = params.optJSONArray("expressions");
            if (expressions == null || expressions.length() == 0) {
                return error("eval_view requires non-empty 'expressions' array.", null);
            }

            List<MatchedElement> matches = elementFinder.findInspectable(
                text, resourceId, contentDesc, className, true);

            if (matches.isEmpty()) {
                List<MatchedElement> candidates = elementFinder.findClickableCandidates(5, true);
                JSONObject data = new JSONObject();
                JSONArray candidatesArray = new JSONArray();
                for (MatchedElement c : candidates) {
                    candidatesArray.put(c.toMatchedElementJson());
                }
                data.put("candidates", candidatesArray);
                return error("No matching element found for selector {"
                    + describeSelector(targetObj) + "}.", data);
            }
            if (matches.size() > 1) {
                JSONObject data = new JSONObject();
                JSONArray matchesArray = new JSONArray();
                for (MatchedElement m : matches) {
                    matchesArray.put(m.toMatchedElementJson());
                }
                data.put("matchCount", matches.size());
                data.put("matches", matchesArray);
                return error("Multiple elements matched (" + matches.size()
                    + "). Narrow the selector or add className.", data);
            }

            MatchedElement target = matches.get(0);
            android.view.View view = target.view;

            JSONArray values = new JSONArray();
            for (int i = 0; i < expressions.length(); i++) {
                String expr = expressions.getString(i);
                JSONObject entry = new JSONObject();
                entry.put("expression", expr);
                try {
                    ViewExpressionEvaluator.Result result =
                        ViewExpressionEvaluator.evaluate(view, expr);
                    entry.put("value", result.jsonValue == null
                        ? JSONObject.NULL : result.jsonValue);
                    entry.put("type", result.typeName);
                } catch (Exception e) {
                    entry.put("value", JSONObject.NULL);
                    entry.put("type", "error");
                    entry.put("error", e.getClass().getSimpleName()
                        + ": " + e.getMessage());
                }
                values.put(entry);
            }

            JSONObject data = new JSONObject();
            data.put("className", view.getClass().getName());
            data.put("resourceId", ViewNode.shortenId(target.resourceId));
            data.put("density", android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
            data.put("values", values);
            return ok(data);

        } catch (Throwable t) {
            LogUtils.e(TAG, "doEvalView failed", t);
            return error("eval_view failed: " + t.getMessage(), null);
        }
    }

    private String describeSelector(JSONObject selector) {
        StringBuilder sb = new StringBuilder();
        String rid = optString(selector, "resourceId");
        String txt = optString(selector, "text");
        String cd = optString(selector, "contentDesc");
        String cn = optString(selector, "className");
        if (rid != null) sb.append("resourceId='").append(rid).append("'");
        if (txt != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("text='").append(txt).append("'");
        }
        if (cd != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("contentDesc='").append(cd).append("'");
        }
        if (cn != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("className='").append(cn).append("'");
        }
        return sb.toString();
    }

    private JSONObject buildElementsData(List<MatchedElement> matches) throws Exception {        JSONObject data = new JSONObject();
        JSONArray elements = new JSONArray();
        for (MatchedElement match : matches) {
            elements.put(match.toMatchedElementJson());
        }
        data.put("matchCount", matches.size());
        data.put("elements", elements);
        return data;
    }

    private String optString(JSONObject params, String key) {
        Object value = params.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        String str = String.valueOf(value).trim();
        return str.isEmpty() ? null : str;
    }

    private boolean optBoolean(JSONObject params, String key, boolean defaultValue) {
        Object value = params.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Integer optInt(JSONObject params, String key) {
        Object value = params.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Throwable ignore) {
            return null;
        }
    }

    private JSONObject ok(JSONObject data) {
        JSONObject response = new JSONObject();
        try {
            response.put("status", "ok");
            response.put("version", PROTOCOL_VERSION);
            response.put("data", data != null ? data : new JSONObject());
        } catch (Throwable t) {
            LogUtils.e(TAG, "build ok response failed", t);
        }
        return response;
    }

    private JSONObject error(String message, JSONObject data) {
        JSONObject response = new JSONObject();
        try {
            response.put("status", "error");
            response.put("version", PROTOCOL_VERSION);
            response.put("message", message != null ? message : "Unknown error");
            if (data != null) {
                response.put("data", data);
            }
        } catch (Throwable t) {
            LogUtils.e(TAG, "build error response failed", t);
        }
        return response;
    }

    private void closeQuietly(LocalSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (Throwable ignore) {
        }
    }
}
