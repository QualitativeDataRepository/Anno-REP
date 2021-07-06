package edu.syr.qdr.annorep.core.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;

import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ContentBody;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DataverseService {

    private static String url = "https://dv.dev-aws.qdr.org";
    private CloseableHttpClient httpClient = null;

    private DataverseService() {

    }
    
    public boolean ping() {
        return true;
        
    }

    public String getPdfUrl(long id) {
        return url + getPdfPath(id);
    }

    public String getPdfPath(long id) {
        return "/api/access/datafile/" + id + "/auxiliary/ingestPDF/v1.0";
    }

    public String getAnnUrl(long id) {
        return url + getAnnPath(id);
    }

    public String getAnnPath(long id) {
        return "/api/access/datafile/" + id + "/auxiliary/annotationJson/v1.0";
    }

    public CloseableHttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = HttpClients.createDefault();
        }
        return httpClient;

    }

    public JsonObject getAPIJsonResponse(String apiPath, String apikey) throws Exception {
        log.info("Getting: " + apiPath);
        HttpGet httpGet = new HttpGet(url + apiPath);
        if (apikey != null) {
            httpGet.addHeader("X-Dataverse-key", apikey);
        }
        try {
            CloseableHttpResponse response = getHttpClient().execute(httpGet);
            String data = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            int status = response.getStatusLine().getStatusCode(); 
            if ( status == 404) {
                return Json.createObjectBuilder().build();
            } else if (status != 200) {
                throw new Exception("Response code: " + response.getStatusLine().getStatusCode() + ", " + data);
            }
            JsonReader r = Json.createReader(new StringReader(data));
            return r.readObject();
        } catch (IOException ioe) {
            log.error("IOException getting url", ioe);
            throw new Exception("IOException getting url", ioe);
        }

    }

    public int deleteAPI(String apiPath, String apikey) {
        log.info("DELETEing: " + apiPath);
        HttpDelete httpDelete = new HttpDelete(url + apiPath);
        if (apikey != null) {
            httpDelete.addHeader("X-Dataverse-key", apikey);
        }
        try {
            CloseableHttpResponse response = getHttpClient().execute(httpDelete);
            String data = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            int status = response.getStatusLine().getStatusCode();
            if (response.getStatusLine().getStatusCode() != 200) {
                log.info("Received error status from Dataverse: " + status);
                return status;
            }
        } catch (IOException ioe) {
            log.error("IOException getting url", ioe);
            return 500;
        }
        return 200;
    }

    public InputStream getFile(long id, String apikey) {
        HttpGet httpGet = new HttpGet(url + "/api/access/datafile/" + id);
        if (apikey != null) {
            httpGet.addHeader("X-Dataverse-key", apikey);
        }
        CloseableHttpResponse response;
        try {
            response = getHttpClient().execute(httpGet);

            int status = response.getStatusLine().getStatusCode();
            String res = null;
            HttpEntity resEntity = response.getEntity();

            if (status == 200) {
                return resEntity.getContent();
            }
            log.error("Status: " + status);
            log.error("Msg: " + resEntity.toString());

        } catch (UnsupportedOperationException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }

    public JsonObject addFile(String datasetDoi, String filename, String jsonData, String apikey) {
        HttpPost httpPost = new HttpPost(url + "/api/datasets/:persistentId/add?persistentId=" + datasetDoi);
        if (apikey != null) {
            httpPost.addHeader("X-Dataverse-key", apikey);
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
                log.error("Error response when processing " + filename + " : "
                        + response.getStatusLine().getReasonPhrase());
                if (res != null) {
                    log.error(res);
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }

    public JsonObject addAuxiliaryFile(Long id, Path auxFile, String origin, boolean isPublic, String formatTag, String formatVersion, String type, String apikey) {
        try (InputStream is = Files.newInputStream(auxFile, StandardOpenOption.READ)) {
            ContentType cType = ContentType.DEFAULT_BINARY;

            String mType = Files.probeContentType(auxFile);

            if (mType != null) {
                cType = ContentType.create(mType);
            }
            return addAuxiliaryFile(id, is, cType, origin, isPublic, formatTag, formatVersion, type, apikey);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return null;

    }

    public String getPath() {
        return url.replaceAll("[:/]", "_");
    }

    private boolean isLocked(String datasetPID, String apikey) {
        String urlString = url + "/api/datasets/:persistentId/locks";
        urlString = urlString + "?persistentId=" + datasetPID;
        HttpGet httpGet = new HttpGet(urlString);
        if (apikey != null) {
            httpGet.addHeader("X-Dataverse-key", apikey);
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

    public JsonObject addAuxiliaryFile(Long id, InputStream is, ContentType cType, String origin, boolean isPublic, String formatTag, String formatVersion, String type, String apikey) {
        HttpPost httpPost = new HttpPost(url + "/api/access/datafile/" + id + "/auxiliary/" + formatTag + "/" + formatVersion);
        if (apikey != null) {
            httpPost.addHeader("X-Dataverse-key", apikey);
        }

        try {

            ContentBody bin = new InputStreamBody(is, cType);

            MultipartEntityBuilder meb = MultipartEntityBuilder.create();
            meb.addPart("file", bin);
            meb.addTextBody("origin", origin);
            meb.addTextBody("type", type);
            meb.addTextBody("isPublic", isPublic ? "true" : "false");

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
                log.error("Error response when processing aux file: " + formatTag + "/" + formatVersion + " : "
                        + response.getStatusLine().getReasonPhrase());
                if (res != null) {
                    log.error(res);
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }
}
