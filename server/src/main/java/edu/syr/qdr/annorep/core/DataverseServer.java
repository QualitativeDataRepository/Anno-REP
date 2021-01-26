package edu.syr.qdr.annorep.core;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ContentBody;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

public class DataverseServer {

    private static final Logger logger = Logger.getLogger(DataverseServer.class.getCanonicalName());

    private String url = null;
    private CloseableHttpClient httpClient = null;
    private User user;

    private static Map<String, DataverseServer> dsm = new HashMap<String, DataverseServer>();

    public static DataverseServer getServerFor(String url, User u) {
        String lookupKey = url;
        if (u != null) {
            lookupKey = lookupKey + u.getApiKey();
        }
        if (!dsm.containsKey(lookupKey)) {
            DataverseServer dvs = new DataverseServer(url, u);
            dsm.put(lookupKey, dvs);
        }
        return dsm.get(lookupKey);
    }

    private DataverseServer(String serverUrl, User u) {
        url = serverUrl;
        user = u;
    }

    public CloseableHttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClients.createDefault();
        }
        return httpClient;

    }

    public JsonObject getAPIJsonResponse(String apiPath) {

        HttpGet httpGet = new HttpGet(url + apiPath);
        if (user != null) {
            httpGet.addHeader("X-Dataverse-key", user.getApiKey());
        }
        try {
            CloseableHttpResponse response = getHttpClient().execute(httpGet);
            String data = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new RuntimeException("Response code: " + response.getStatusLine().getStatusCode() + ", " + data);
            }
            JsonReader r = Json.createReader(new StringReader(data));
            return r.readObject();
        } catch (IOException ioe) {
            logger.log(Level.SEVERE, "IOException getting url", ioe);
            throw new RuntimeException("IOException getting url", ioe);
        }

    }

    public JsonObject addFile(String datasetDoi, String filename, String jsonData) {
        HttpPost httpPost = new HttpPost(url + "/api/datasets/:persistentId/add?persistentId=" + datasetDoi);
        if (user != null) {
            httpPost.addHeader("X-Dataverse-key", user.getApiKey());
        }
        ContentType cType = ContentType.DEFAULT_BINARY;
        Path p = Paths.get(filename);
        try {
            String mType = Files.probeContentType(p);

            if (mType != null) {
                cType = ContentType.create(mType);
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        try {
            ContentBody bin = new InputStreamBody(Files.newInputStream(p, StandardOpenOption.READ), cType, p.getFileName().toString());

            MultipartEntityBuilder meb = MultipartEntityBuilder.create();
            meb.addPart("file", bin);
            meb.addTextBody("jsonData", jsonData);

            HttpEntity reqEntity = meb.build();
            httpPost.setEntity(reqEntity);

            CloseableHttpResponse response;

            response = getHttpClient().execute(httpPost);

            int status = response.getStatusLine().getStatusCode();
            String res = null;
            HttpEntity resEntity = response.getEntity();
            if (resEntity != null) {
                res = EntityUtils.toString(resEntity);
            }
            if (status == 200) {
                JsonReader r = Json.createReader(new StringReader(res));
                return r.readObject();
            } else {
                // An error and unlikely that we can recover, so report and move on.
                logger.warning("Error response when processing " + filename + " : "
                        + response.getStatusLine().getReasonPhrase());
                if (res != null) {
                    logger.warning(res);
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }

    public JsonObject addAuxilliaryFile(String datasetDoi, int fileId, Path auxFile, String origin, boolean isPublic, String formatTag, String formatVersion) {
        HttpPost httpPost = new HttpPost(url + "/api/access/datafile/" + fileId + "/metadata/" + formatTag + "/" + formatVersion);
        if (user != null) {
            httpPost.addHeader("X-Dataverse-key", user.getApiKey());
        }
        ContentType cType = ContentType.DEFAULT_BINARY;
        try {
            String mType = Files.probeContentType(auxFile);

            if (mType != null) {
                cType = ContentType.create(mType);
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        try {
            ContentBody bin = new InputStreamBody(Files.newInputStream(auxFile, StandardOpenOption.READ), cType, auxFile.getFileName().toString());

            MultipartEntityBuilder meb = MultipartEntityBuilder.create();
            meb.addPart("file", bin);
            meb.addTextBody("origin", origin);
            meb.addTextBody("isPublic", isPublic ? "true":"false");

            HttpEntity reqEntity = meb.build();
            httpPost.setEntity(reqEntity);

            CloseableHttpResponse response;

            response = getHttpClient().execute(httpPost);

            int status = response.getStatusLine().getStatusCode();
            String res = null;
            HttpEntity resEntity = response.getEntity();
            if (resEntity != null) {
                res = EntityUtils.toString(resEntity);
            }
            if (status == 200) {
                JsonReader r = Json.createReader(new StringReader(res));
                return r.readObject();
            } else {
                // An error and unlikely that we can recover, so report and move on.
                logger.warning("Error response when processing " + auxFile.getFileName().toString() + " : "
                        + response.getStatusLine().getReasonPhrase());
                if (res != null) {
                    logger.warning(res);
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }

    
    public String getPath() {
        return url.replaceAll("[:/]", "_");
    }

    private boolean isLocked(String datasetPID) {
        String urlString = url + "/api/datasets/:persistentId/locks";
        urlString = urlString + "?persistentId=" + datasetPID;
        HttpGet httpGet = new HttpGet(urlString);
        if (user != null) {
            httpGet.addHeader("X-Dataverse-key", user.getApiKey());
        }

        CloseableHttpResponse response;
        try {
            response = getHttpClient().execute(httpGet);

            try {
                if (response.getStatusLine().getStatusCode() == 200) {
                    HttpEntity resEntity = response.getEntity();
                    if (resEntity != null) {
                        String res = EntityUtils.toString(resEntity);
                        JsonReader r = Json.createReader(new StringReader(res));
                        boolean locked = r.readObject().getJsonArray("data").size() > 0;
                        return locked;
                    }
                }
            } finally {
                EntityUtils.consumeQuietly(response.getEntity());
            }
        } catch (ClientProtocolException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        return false;
    }
}
