package com.rxtube2;


import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;

import com.evgenii.jsevaluator.JsEvaluator;
import com.evgenii.jsevaluator.interfaces.JsCallback;
import com.rxtube2.internal.Utils;
import com.rxtube2.model.YtResponse;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Observable;

import static com.rxtube2.Constants.*;


class RxTubeImpl implements RxTube {

    private final static boolean CACHING = true;

    private static boolean LOGGING = false;

    private final static String LOG_TAG = "YouTubeExtractor";
    private final static String CACHE_FILE_NAME = "decipher_js_funct";
    private final static int DASH_PARSE_RETRIES = 5;

    private final Context context;
    private String videoID;
    private VideoMeta videoMeta;
    private boolean includeWebM = true;
    private boolean useHttp = false;
    private boolean parseDashManifest = false;

    private volatile String decipheredSignature;

    private static String decipherJsFileName;
    private static String decipherFunctions;
    private static String decipherFunctionName;

    private final Lock lock = new ReentrantLock();
    private final Condition jsExecuting = lock.newCondition();

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/40.0.2214.115 Safari/537.36";
    private static final String STREAM_MAP_STRING = "url_encoded_fmt_stream_map";

    private static final Pattern patYouTubePageLink = Pattern.compile("(http|https)://(www\\.|m.|)youtube\\.com/watch\\?v=(.+?)( |\\z|&)");
    private static final Pattern patYouTubeShortLink = Pattern.compile("(http|https)://(www\\.|)youtu.be/(.+?)( |\\z|&)");

    private static final Pattern patDashManifest1 = Pattern.compile("dashmpd=(.+?)(&|\\z)");
    private static final Pattern patDashManifest2 = Pattern.compile("\"dashmpd\":\"(.+?)\"");
    private static final Pattern patDashManifestEncSig = Pattern.compile("/s/([0-9A-F|.]{10,}?)(/|\\z)");

    private static final Pattern patTitle = Pattern.compile("title=(.*?)(&|\\z)");
    private static final Pattern patAuthor = Pattern.compile("author=(.+?)(&|\\z)");
    private static final Pattern patChannelId = Pattern.compile("ucid=(.+?)(&|\\z)");
    private static final Pattern patLength = Pattern.compile("length_seconds=(\\d+?)(&|\\z)");
    private static final Pattern patViewCount = Pattern.compile("view_count=(\\d+?)(&|\\z)");

    private static final Pattern patHlsvp = Pattern.compile("hlsvp=(.+?)(&|\\z)");
    private static final Pattern patHlsItag = Pattern.compile("/itag/(\\d+?)/");

    private static final Pattern patItag = Pattern.compile("itag=([0-9]+?)([&,])");
    private static final Pattern patEncSig = Pattern.compile("s=([0-9A-F|.]{10,}?)([&,\"])");
    private static final Pattern patIsSigEnc = Pattern.compile("s%3D([0-9A-F|.]{10,}?)%26");
    private static final Pattern patUrl = Pattern.compile("url=(.+?)([&,])");

    private static final Pattern patVariableFunction = Pattern.compile("([{; =])([a-zA-Z$][a-zA-Z0-9$]{0,2})\\.([a-zA-Z$][a-zA-Z0-9$]{0,2})\\(");
    private static final Pattern patFunction = Pattern.compile("([{; =])([a-zA-Z$_][a-zA-Z0-9$]{0,2})\\(");
    private static final Pattern patDecryptionJsFile = Pattern.compile("jsbin\\\\/(player-(.+?).js)");
    private static final Pattern patSignatureDecFunction = Pattern.compile("\"signature\",(.{1,3}?)\\(.{1,10}?\\)");

    RxTubeImpl(Context context) {
        this.context = Utils.checkNotNull(context);
    }

    @Override
    public Observable<YtResponse> extract(String youTubeLink) {
        return extract(youTubeLink, false, true);
    }

    @Override
    public Observable<YtResponse> extract(final String youtubeLink, boolean parseDashManifest, boolean includeWebM) {
        Utils.checkNotNull(youtubeLink);
        this.parseDashManifest = parseDashManifest;
        this.includeWebM = includeWebM;
        return Observable.fromCallable(new Callable<YtResponse>() {
            @Override
            public YtResponse call() throws Exception {
                videoID = null;
                Matcher mat = patYouTubePageLink.matcher(youtubeLink);
                if (mat.find()) {
                    videoID = mat.group(3);
                } else {
                    mat = patYouTubeShortLink.matcher(youtubeLink);
                    if (mat.find()) {
                        videoID = mat.group(3);
                    } else if (youtubeLink.matches("\\p{Graph}+?")) {
                        videoID = youtubeLink;
                    }
                }
                if (videoID != null) {
                    return new YtResponse(getStreamUrls(), videoMeta);
                }
                throw new RuntimeException("Wrong YouTube link format");
            }
        });
    }

    private SparseArray<YtFile> getStreamUrls() throws IOException, InterruptedException {

        String ytInfoUrl = (useHttp) ? "http://" : "https://";
        ytInfoUrl += "www.youtube.com/get_video_info?video_id=" + videoID + "&eurl="
                + URLEncoder.encode("https://youtube.googleapis.com/v/" + videoID, "UTF-8");

        String dashMpdUrl = null;
        String streamMap;
        BufferedReader reader = null;
        URL getUrl = new URL(ytInfoUrl);
        if (LOGGING)
            Log.d(LOG_TAG, "infoUrl: " + ytInfoUrl);
        HttpURLConnection urlConnection = (HttpURLConnection) getUrl.openConnection();
        urlConnection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            streamMap = reader.readLine();

        } finally {
            if (reader != null)
                reader.close();
            urlConnection.disconnect();
        }
        Matcher mat;
        String curJsFileName = null;
        String[] streams;
        SparseArray<String> encSignatures = null;

        parseVideoMeta(streamMap);

        if (videoMeta.isLiveStream()) {
            mat = patHlsvp.matcher(streamMap);
            if (mat.find()) {
                String hlsvp = URLDecoder.decode(mat.group(1), "UTF-8");
                SparseArray<YtFile> ytFiles = new SparseArray<>();

                getUrl = new URL(hlsvp);
                urlConnection = (HttpURLConnection) getUrl.openConnection();
                urlConnection.setRequestProperty("User-Agent", USER_AGENT);
                try {
                    reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("https://") || line.startsWith("http://")) {
                            mat = patHlsItag.matcher(line);
                            if (mat.find()) {
                                int itag = Integer.parseInt(mat.group(1));
                                YtFile newFile = new YtFile(FORMAT_MAP.get(itag), line);
                                ytFiles.put(itag, newFile);
                            }
                        }
                    }
                } finally {
                    if (reader != null)
                        reader.close();
                    urlConnection.disconnect();
                }

                if (ytFiles.size() == 0) {
                    if (LOGGING)
                        Log.d(LOG_TAG, streamMap);
                    return null;
                }
                return ytFiles;
            }
            return null;
        }

        // "use_cipher_signature" disappeared, we check whether at least one ciphered signature
        // exists int the stream_map.
        boolean sigEnc = true;
        if (streamMap != null && streamMap.contains(STREAM_MAP_STRING)) {
            String streamMapSub = streamMap.substring(streamMap.indexOf(STREAM_MAP_STRING));
            mat = patIsSigEnc.matcher(streamMapSub);
            if (!mat.find())
                sigEnc = false;
        }

        // Some videos are using a ciphered signature we need to get the
        // deciphering js-file from the youtubepage.
        if (sigEnc) {
            // Get the video directly from the youtubepage
            if (CACHING
                    && (decipherJsFileName == null || decipherFunctions == null || decipherFunctionName == null)) {
                readDecipherFunctFromCache();
            }
            getUrl = new URL("https://youtube.com/watch?v=" + videoID);
            urlConnection = (HttpURLConnection) getUrl.openConnection();
            urlConnection.setRequestProperty("User-Agent", USER_AGENT);
            try {
                reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    // Log.d("line", line);
                    if (line.contains(STREAM_MAP_STRING)) {
                        streamMap = line.replace("\\u0026", "&");
                        break;
                    }
                }
            } finally {
                if (reader != null)
                    reader.close();
                urlConnection.disconnect();
            }
            encSignatures = new SparseArray<>();

            mat = patDecryptionJsFile.matcher(streamMap);
            if (mat.find()) {
                curJsFileName = mat.group(1).replace("\\/", "/");
                if (decipherJsFileName == null || !decipherJsFileName.equals(curJsFileName)) {
                    decipherFunctions = null;
                    decipherFunctionName = null;
                }
                decipherJsFileName = curJsFileName;
            }

            if (parseDashManifest) {
                mat = patDashManifest2.matcher(streamMap);
                if (mat.find()) {
                    dashMpdUrl = mat.group(1).replace("\\/", "/");
                    mat = patDashManifestEncSig.matcher(dashMpdUrl);
                    if (mat.find()) {
                        encSignatures.append(0, mat.group(1));
                    } else {
                        dashMpdUrl = null;
                    }
                }
            }
        } else {
            if (parseDashManifest) {
                mat = patDashManifest1.matcher(streamMap);
                if (mat.find()) {
                    dashMpdUrl = URLDecoder.decode(mat.group(1), "UTF-8");
                }
            }
            streamMap = URLDecoder.decode(streamMap, "UTF-8");
        }

        streams = streamMap.split(",|" + STREAM_MAP_STRING + "|&adaptive_fmts=");
        SparseArray<YtFile> ytFiles = new SparseArray<>();
        for (String encStream : streams) {
            encStream = encStream + ",";
            if (!encStream.contains("itag%3D")) {
                continue;
            }
            String stream;
            stream = URLDecoder.decode(encStream, "UTF-8");

            mat = patItag.matcher(stream);
            int itag;
            if (mat.find()) {
                itag = Integer.parseInt(mat.group(1));
                if (LOGGING)
                    Log.d(LOG_TAG, "Itag found:" + itag);
                if (FORMAT_MAP.get(itag) == null) {
                    if (LOGGING)
                        Log.d(LOG_TAG, "Itag not in list:" + itag);
                    continue;
                } else if (!includeWebM && FORMAT_MAP.get(itag).getExt().equals("webm")) {
                    continue;
                }
            } else {
                continue;
            }

            if (curJsFileName != null) {
                mat = patEncSig.matcher(stream);
                if (mat.find()) {
                    encSignatures.append(itag, mat.group(1));
                }
            }
            mat = patUrl.matcher(encStream);
            String url = null;
            if (mat.find()) {
                url = mat.group(1);
            }

            if (url != null) {
                Format format = FORMAT_MAP.get(itag);
                String finalUrl = URLDecoder.decode(url, "UTF-8");
                YtFile newVideo = new YtFile(format, finalUrl);
                ytFiles.put(itag, newVideo);
            }
        }

        if (encSignatures != null) {
            if (LOGGING)
                Log.d(LOG_TAG, "Decipher signatures");
            String signature;
            decipheredSignature = null;
            if (decipherSignature(encSignatures)) {
                lock.lock();
                try {
                    jsExecuting.await(7, TimeUnit.SECONDS);
                } finally {
                    lock.unlock();
                }
            }
            signature = decipheredSignature;
            if (signature == null) {
                return null;
            } else {
                String[] sigs = signature.split("\n");
                for (int i = 0; i < encSignatures.size() && i < sigs.length; i++) {
                    int key = encSignatures.keyAt(i);
                    if (key == 0) {
                        dashMpdUrl = dashMpdUrl.replace("/s/" + encSignatures.get(key), "/signature/" + sigs[i]);
                    } else {
                        String url = ytFiles.get(key).getUrl();
                        url += "&signature=" + sigs[i];
                        YtFile newFile = new YtFile(FORMAT_MAP.get(key), url);
                        ytFiles.put(key, newFile);
                    }
                }
            }
        }

        if (parseDashManifest && dashMpdUrl != null) {
            for (int i = 0; i < DASH_PARSE_RETRIES; i++) {
                try {
                    // It sometimes fails to connect for no apparent reason. We just retry.
                    parseDashManifest(dashMpdUrl, ytFiles);
                    break;
                } catch (IOException io) {
                    Thread.sleep(5);
                    if (LOGGING)
                        Log.d(LOG_TAG, "Failed to parse dash manifest " + (i + 1));
                }
            }
        }

        if (ytFiles.size() == 0) {
            if (LOGGING)
                Log.d(LOG_TAG, streamMap);
            return null;
        }
        return ytFiles;
    }

    private boolean decipherSignature(final SparseArray<String> encSignatures) throws IOException {
        // Assume the functions don't change that much
        if (decipherFunctionName == null || decipherFunctions == null) {
            String decipherFunctUrl = "https://s.ytimg.com/yts/jsbin/" + decipherJsFileName;

            BufferedReader reader = null;
            String javascriptFile;
            URL url = new URL(decipherFunctUrl);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("User-Agent", USER_AGENT);
            try {
                reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                StringBuilder sb = new StringBuilder("");
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                    sb.append(" ");
                }
                javascriptFile = sb.toString();
            } finally {
                if (reader != null)
                    reader.close();
                urlConnection.disconnect();
            }

            if (LOGGING)
                Log.d(LOG_TAG, "Decipher FunctURL: " + decipherFunctUrl);
            Matcher mat = patSignatureDecFunction.matcher(javascriptFile);
            if (mat.find()) {
                decipherFunctionName = mat.group(1);
                if (LOGGING)
                    Log.d(LOG_TAG, "Decipher Functname: " + decipherFunctionName);

                Pattern patMainVariable = Pattern.compile("(var |\\s|,|;)" + decipherFunctionName.replace("$", "\\$") +
                        "(=function\\((.{1,3})\\)\\{)");

                String mainDecipherFunct;

                mat = patMainVariable.matcher(javascriptFile);
                if (mat.find()) {
                    mainDecipherFunct = "var " + decipherFunctionName + mat.group(2);
                } else {
                    Pattern patMainFunction = Pattern.compile("function " + decipherFunctionName.replace("$", "\\$") +
                            "(\\((.{1,3})\\)\\{)");
                    mat = patMainFunction.matcher(javascriptFile);
                    if (!mat.find())
                        return false;
                    mainDecipherFunct = "function " + decipherFunctionName + mat.group(2);
                }

                int startIndex = mat.end();

                for (int braces = 1, i = startIndex; i < javascriptFile.length(); i++) {
                    if (braces == 0 && startIndex + 5 < i) {
                        mainDecipherFunct += javascriptFile.substring(startIndex, i) + ";";
                        break;
                    }
                    if (javascriptFile.charAt(i) == '{')
                        braces++;
                    else if (javascriptFile.charAt(i) == '}')
                        braces--;
                }
                decipherFunctions = mainDecipherFunct;
                // Search the main function for extra functions and variables
                // needed for deciphering
                // Search for variables
                mat = patVariableFunction.matcher(mainDecipherFunct);
                while (mat.find()) {
                    String variableDef = "var " + mat.group(2) + "={";
                    if (decipherFunctions.contains(variableDef)) {
                        continue;
                    }
                    startIndex = javascriptFile.indexOf(variableDef) + variableDef.length();
                    for (int braces = 1, i = startIndex; i < javascriptFile.length(); i++) {
                        if (braces == 0) {
                            decipherFunctions += variableDef + javascriptFile.substring(startIndex, i) + ";";
                            break;
                        }
                        if (javascriptFile.charAt(i) == '{')
                            braces++;
                        else if (javascriptFile.charAt(i) == '}')
                            braces--;
                    }
                }
                // Search for functions
                mat = patFunction.matcher(mainDecipherFunct);
                while (mat.find()) {
                    String functionDef = "function " + mat.group(2) + "(";
                    if (decipherFunctions.contains(functionDef)) {
                        continue;
                    }
                    startIndex = javascriptFile.indexOf(functionDef) + functionDef.length();
                    for (int braces = 0, i = startIndex; i < javascriptFile.length(); i++) {
                        if (braces == 0 && startIndex + 5 < i) {
                            decipherFunctions += functionDef + javascriptFile.substring(startIndex, i) + ";";
                            break;
                        }
                        if (javascriptFile.charAt(i) == '{')
                            braces++;
                        else if (javascriptFile.charAt(i) == '}')
                            braces--;
                    }
                }

                if (LOGGING)
                    Log.d(LOG_TAG, "Decipher Function: " + decipherFunctions);
                decipherViaWebView(encSignatures);
                if (CACHING) {
                    writeDeciperFunctToChache();
                }
            } else {
                return false;
            }
        } else {
            decipherViaWebView(encSignatures);
        }
        return true;
    }

    private void parseDashManifest(String dashMpdUrl, SparseArray<YtFile> ytFiles) throws IOException {
        Pattern patBaseUrl = Pattern.compile("<BaseURL yt:contentLength=\"[0-9]+?\">(.+?)</BaseURL>");
        String dashManifest;
        BufferedReader reader = null;
        URL getUrl = new URL(dashMpdUrl);
        HttpURLConnection urlConnection = (HttpURLConnection) getUrl.openConnection();
        urlConnection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            reader.readLine();
            dashManifest = reader.readLine();

        } finally {
            if (reader != null)
                reader.close();
            urlConnection.disconnect();
        }
        if (dashManifest == null)
            return;
        Matcher mat = patBaseUrl.matcher(dashManifest);
        while (mat.find()) {
            int itag;
            String url = mat.group(1);
            Matcher mat2 = patItag.matcher(url);
            if (mat2.find()) {
                itag = Integer.parseInt(mat2.group(1));
                if (FORMAT_MAP.get(itag) == null)
                    continue;
                if (!includeWebM && FORMAT_MAP.get(itag).getExt().equals("webm"))
                    continue;
            } else {
                continue;
            }
            url = url.replace("&amp;", "&").replace(",", "%2C").
                    replace("mime=audio/", "mime=audio%2F").
                    replace("mime=video/", "mime=video%2F");
            YtFile yf = new YtFile(FORMAT_MAP.get(itag), url);
            ytFiles.append(itag, yf);
        }

    }

    private void parseVideoMeta(String getVideoInfo) throws UnsupportedEncodingException {
        boolean isLiveStream = false;
        String title = null, author = null, channelId = null;
        long viewCount = 0, length = 0;
        Matcher mat = patTitle.matcher(getVideoInfo);
        if (mat.find()) {
            title = URLDecoder.decode(mat.group(1), "UTF-8");
        }

        mat = patHlsvp.matcher(getVideoInfo);
        if (mat.find())
            isLiveStream = true;

        mat = patAuthor.matcher(getVideoInfo);
        if (mat.find()) {
            author = URLDecoder.decode(mat.group(1), "UTF-8");
        }
        mat = patChannelId.matcher(getVideoInfo);
        if (mat.find()) {
            channelId = mat.group(1);
        }
        mat = patLength.matcher(getVideoInfo);
        if (mat.find()) {
            length = Long.parseLong(mat.group(1));
        }
        mat = patViewCount.matcher(getVideoInfo);
        if (mat.find()) {
            viewCount = Long.parseLong(mat.group(1));
        }
        videoMeta = new VideoMeta(videoID, title, author, channelId, length, viewCount, isLiveStream);

    }

    private void readDecipherFunctFromCache() {
        File cacheFile = new File(context.getCacheDir().getAbsolutePath() + "/" + CACHE_FILE_NAME);
        // The cached functions are valid for 2 weeks
        if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified()) < 1209600000) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(cacheFile), "UTF-8"));
                decipherJsFileName = reader.readLine();
                decipherFunctionName = reader.readLine();
                decipherFunctions = reader.readLine();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * Set default protocol of the returned urls to HTTP instead of HTTPS.
     * HTTP may be blocked in some regions so HTTPS is the default value.
     * <p/>
     * Note: Enciphered videos require HTTPS so they are not affected by
     * this.
     */
    public void setDefaultHttpProtocol(boolean useHttp) {
        this.useHttp = useHttp;
    }

    private void writeDeciperFunctToChache() {
        File cacheFile = new File(context.getCacheDir().getAbsolutePath() + "/" + CACHE_FILE_NAME);
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(cacheFile), "UTF-8"));
            writer.write(decipherJsFileName + "\n");
            writer.write(decipherFunctionName + "\n");
            writer.write(decipherFunctions);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void decipherViaWebView(final SparseArray<String> encSignatures) {
        final StringBuilder stb = new StringBuilder(decipherFunctions + " function decipher(");
        stb.append("){return ");
        for (int i = 0; i < encSignatures.size(); i++) {
            int key = encSignatures.keyAt(i);
            if (i < encSignatures.size() - 1)
                stb.append(decipherFunctionName).append("('").append(encSignatures.get(key)).
                        append("')+\"\\n\"+");
            else
                stb.append(decipherFunctionName).append("('").append(encSignatures.get(key)).
                        append("')");
        }
        stb.append("};decipher();");

        new Handler(Looper.getMainLooper()).post(new Runnable() {

            @Override
            public void run() {
                new JsEvaluator(context).evaluate(stb.toString(), new JsCallback() {
                    @Override
                    public void onResult(String result) {
                        lock.lock();
                        try {
                            decipheredSignature = result;
                            jsExecuting.signal();
                        } finally {
                            lock.unlock();
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        lock.lock();
                        try {
                            if (LOGGING)
                                Log.e(LOG_TAG, errorMessage);
                            jsExecuting.signal();
                        } finally {
                            lock.unlock();
                        }
                    }
                });
            }
        });
    }
}
