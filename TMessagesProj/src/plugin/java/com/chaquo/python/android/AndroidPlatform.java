package com.chaquo.python.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.internal.Common;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Fork divergence: asset-extraction failures are logged and skipped (returning {@code null}
 * from {@link #getPath()}) rather than thrown, and the constructor logs before failing.
 */
public class AndroidPlatform extends Python.Platform {
    public static String ABI;
    private AssetManager am;
    private JSONObject buildJson;
    public Application mContext;
    private SharedPreferences sp;
    private static final String[] OBSOLETE_FILES = {"app.zip", "requirements.zip", "chaquopy.mp3", "stdlib.mp3", "chaquopy.zip", "lib-dynload", "stdlib.zip", "bootstrap.zip", "stdlib-common.zip", "ticket.txt"};
    private static final String[] OBSOLETE_CACHE = {"AssetFinder"};

    public AndroidPlatform(Context context) {
        mContext = (Application) context.getApplicationContext();
        sp = mContext.getSharedPreferences(Common.ASSET_DIR, 0);
        am = mContext.getAssets();
        try {
            buildJson = new JSONObject(streamToString(am.open("chaquopy/build.json")));
            loadNativeLibs();
            for (String abi : Build.SUPPORTED_ABIS) {
                try {
                    am.open("chaquopy/" + Common.assetZip(Common.ASSET_STDLIB, abi));
                    ABI = abi;
                    break;
                } catch (IOException ignored) {
                }
            }
            if (ABI != null) {
                return;
            }
            throw new RuntimeException("None of this device's ABIs " + Arrays.toString(Build.SUPPORTED_ABIS) + " are supported by this app.");
        } catch (IOException | JSONException e) {
            Log.e("chaquopy", "Failed to initialize Python runtime", e);
            throw new RuntimeException(e);
        }
    }

    private void cleanExtractedDir(String dir, JSONObject assetsJson) {
        File file = new File(mContext.getFilesDir(), "chaquopy/" + dir);
        for (String name : file.list()) {
            File child = new File(file, name);
            if (child.isDirectory()) {
                cleanExtractedDir(dir + "/" + name, assetsJson);
            } else {
                if (!assetsJson.has(dir + "/" + name)) {
                    child.delete();
                }
            }
        }
    }

    private void deleteObsolete(File baseDir, String[] filenames) {
        for (String filename : filenames) {
            deleteRecursive(new File(baseDir, "chaquopy/" + filename.replace("<abi>", ABI)));
        }
    }

    private void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    private void extractAsset(JSONObject assetsJson, SharedPreferences.Editor spe, String path) throws JSONException, IOException {
        String fullPath = "chaquopy/" + path;
        File outFile = new File(mContext.getFilesDir(), fullPath);
        String spKey = "asset." + path;
        String newHash = assetsJson.getString(path);
        if (outFile.exists() && sp.getString(spKey, "").equals(newHash)) {
            return;
        }
        outFile.delete();
        File outDir = outFile.getParentFile();
        if (!outDir.exists()) {
            outDir.mkdirs();
            if (!outDir.isDirectory()) {
                Log.e("chaquopy", "Failed to create " + outDir);
                return;
            }
        }
        InputStream inputStream = am.open(fullPath);
        File tmpFile = new File(outDir, outFile.getName() + ".tmp");
        tmpFile.delete();
        FileOutputStream outputStream = new FileOutputStream(tmpFile);
        try {
            transferStream(inputStream, outputStream);
            outputStream.close();
            if (tmpFile.renameTo(outFile)) {
                spe.putString(spKey, newHash);
            } else {
                Log.e("chaquopy", "Failed to create " + outFile);
            }
        } catch (Throwable th) {
            outputStream.close();
            throw th;
        }
    }

    private void extractAssets(List<String> assets) throws JSONException, IOException {
        JSONObject assetsJson = buildJson.getJSONObject("assets");
        Set<String> unextracted = new HashSet<>(assets);
        Set<String> directories = new HashSet<>();
        SharedPreferences.Editor spe = sp.edit();
        for (Iterator<String> i = assetsJson.keys(); i.hasNext(); ) {
            String path = i.next();
            for (String asset : assets) {
                if (path.equals(asset) || path.startsWith(asset + "/")) {
                    extractAsset(assetsJson, spe, path);
                    unextracted.remove(asset);
                    if (path.startsWith(asset + "/")) {
                        directories.add(asset);
                    }
                    break;
                }
            }
        }
        if (!unextracted.isEmpty()) {
            Log.e("chaquopy", "Failed to find assets: " + unextracted);
            return;
        }
        for (String dir : directories) {
            cleanExtractedDir(dir, assetsJson);
        }
        spe.apply();
    }

    private void loadNativeLibs() throws JSONException {
        for (String suffix : Arrays.asList(Common.ASSET_DIR, "python")) {
            System.loadLibrary("crypto_" + suffix);
            System.loadLibrary("ssl_" + suffix);
            System.loadLibrary("sqlite3_" + suffix);
        }
        System.loadLibrary("python" + buildJson.getString("python_version"));
        System.loadLibrary("chaquopy_java");
    }

    private String streamToString(InputStream inputStream) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder sb = new StringBuilder();
        while (true) {
            String line = bufferedReader.readLine();
            if (line == null) {
                return sb.toString();
            }
            sb.append(line);
            sb.append("\n");
        }
    }

    private void transferStream(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[1048576];
        int read = inputStream.read(buffer);
        while (true) {
            if (read == -1) {
                return;
            }
            outputStream.write(buffer, 0, read);
            read = inputStream.read(buffer);
        }
    }

    public Application getApplication() {
        return mContext;
    }

    @Override
    public String getPath() {
        String baseDir = mContext.getFilesDir() + "/chaquopy";
        List<String> pathElements = new ArrayList<>(Arrays.asList(
                Common.assetZip(Common.ASSET_STDLIB, Common.ABI_COMMON),
                Common.assetZip(Common.ASSET_BOOTSTRAP),
                "bootstrap-native/" + ABI));
        String path = "";
        for (int i = 0; i < pathElements.size(); i++) {
            String element = path + baseDir + "/" + pathElements.get(i);
            path = element;
            if (i < pathElements.size() - 1) {
                path = element.concat(":");
            }
        }
        Collections.addAll(pathElements, Common.ASSET_CACERT);
        try {
            deleteObsolete(mContext.getFilesDir(), OBSOLETE_FILES);
            deleteObsolete(mContext.getCacheDir(), OBSOLETE_CACHE);
            extractAssets(pathElements);
            return path;
        } catch (IOException | JSONException e) {
            Log.e("chaquopy", "Failed to extract Python assets", e);
            return null;
        }
    }

    @Override
    public void onStart(Python python) {
        python.getModule("java.android").callAttr("initialize", mContext, buildJson, new String[]{Common.ASSET_APP, Common.ASSET_REQUIREMENTS, "stdlib-" + ABI});
    }

    public native void redirectStdioToLogcat();
}
