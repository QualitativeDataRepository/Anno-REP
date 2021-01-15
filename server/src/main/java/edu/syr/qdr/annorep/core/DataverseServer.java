package edu.syr.qdr.annorep.core;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonReader;
import javax.json.JsonValue;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

public class DataverseServer {

    private static final Logger logger = Logger.getLogger(DataverseServer.class.getCanonicalName());

    private String url = null;
    private CloseableHttpClient httpClient = null;
    private HttpClientContext context;

    private static Map<String, DataverseServer> dsm = new HashMap<String, DataverseServer>();

    public static DataverseServer getServerFor(String url) {
        if (!dsm.containsKey(url)) {
            DataverseServer dvs = new DataverseServer(url);
            dsm.put(url, dvs);
        }
        return dsm.get(url);
    }

    private DataverseServer(String serverUrl) {
        url = serverUrl;
    }

    public CloseableHttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClients.createDefault();
        }
        return httpClient;

    }

    public JsonValue getAPIJsonResponse(String apiPath) {

        HttpGet httpGet = new HttpGet(url + apiPath);
        try {
            HttpResponse response = getHttpClient().execute(httpGet);
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
    
    public String getPath() {
        return url.replaceAll("[:/]","_");
    }
}
