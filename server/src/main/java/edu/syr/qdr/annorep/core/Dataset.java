package edu.syr.qdr.annorep.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.json.JsonWriter;
import javax.json.JsonWriterFactory;
import javax.json.stream.JsonGenerator;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import edu.syr.qdr.annorep.core.cli.DatasetCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

public class Dataset {

    private static final Logger logger = Logger.getLogger(DatasetCommand.class.getCanonicalName());
    public static String pathSep = System.getProperty("file.separator");

    private JsonObject metadata = null;
    private JsonArray files = null;
    // Convenience - keep DOI - it's also in the metadata
    private String doi;
    private DataverseServer dvS;

    public Dataset(DataverseServer dvs, String doi, JsonObject md, JsonArray files) {
        this.dvS = dvs;
        this.doi = doi;
        this.files = files;
        this.metadata = md;
    }

    public static Dataset getDataset(DataverseServer dvs, String doi) {
        return getDataset(dvs, doi, false);
    }

    public static Dataset getDataset(DataverseServer dvs, String doi, boolean refreshCache) {
        Path mdPath = Paths.get("tmp/" + dvs.getPath() + "/" + doi.replaceAll("[:/]", "_") + ".json");
        Path filesPath = Paths.get("tmp/" + dvs.getPath() + "/" + doi.replaceAll("[:/]", "_") + ".files.json");
        Dataset d = null;
        if (refreshCache) {
            try {
                Files.deleteIfExists(mdPath);
                Files.deleteIfExists(filesPath);

            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        if (mdPath.toFile().exists()) {
            JsonReader r;
            try {
                r = Json.createReader(new StringReader(new String(Files.readAllBytes(mdPath), StandardCharsets.UTF_8)));
                JsonObject md = r.readObject();
                r = Json.createReader(new StringReader(new String(Files.readAllBytes(mdPath), StandardCharsets.UTF_8)));
                JsonArray files = r.readArray();
                d = new Dataset(dvs, doi, md, files);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else {
            // JsonObject oremap = (JsonObject)
            // dvs.getAPIJsonResponse("/api/datasets/export?exporter=OAI_ORE&persistentId="
            // + doi);
            JsonObject metadata = dvs.getAPIJsonResponse("/api/datasets/:persistentId/metadata?persistentId=" + doi).getJsonObject("data");
            JsonArray files = dvs.getAPIJsonResponse("/api/datasets/:persistentId/versions/:draft/files?persistentId=" + doi).asJsonObject().getJsonArray("data");
            // d = new Dataset(oremap.getJsonObject("ore:describes"));
            d = new Dataset(dvs, doi, metadata, files);
            try {
                Files.createDirectories(mdPath.getParent());

                BufferedWriter mdWriter = Files.newBufferedWriter(mdPath, StandardOpenOption.CREATE_NEW);
                Map<String, Object> map = new HashMap<>();
                map.put(JsonGenerator.PRETTY_PRINTING, true);
                JsonWriterFactory writerFactory = Json.createWriterFactory(map);
                JsonWriter jsonWriter = writerFactory.createWriter(mdWriter);
                jsonWriter.writeObject(d.metadata);
                jsonWriter.close();
                BufferedWriter filesWriter = Files.newBufferedWriter(filesPath, StandardOpenOption.CREATE_NEW);
                jsonWriter = writerFactory.createWriter(filesWriter);
                jsonWriter.writeArray(d.files);
                jsonWriter.close();

            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

        }

        return d;
    }

    public JsonObject getMetadata() {
        return metadata;
    }

    public JsonArray getFiles() {
        return files;
    }

    public ArrayList<String> getFileListing() {
        ArrayList<String> fileList = new ArrayList<String>();
        for (JsonValue f : files) {
            JsonObject fo = f.asJsonObject();
            String name = fo.getString("label"); // schema:name
            String dirPath = fo.getString("directoryLabel", ""); // dvcore:directoryLabel
            if (dirPath.length() == 0) {
                dirPath = pathSep;
            } else {
                dirPath = pathSep + dirPath + pathSep;
            }
            fileList.add(dirPath + name);
        }
        fileList.sort(new Comparator<String>() {
            public int compare(String s1, String s2) {
                Path p1 = Paths.get(s1);
                Path p2 = Paths.get(s2);
                int depth1 = p1.getNameCount();
                int depth2 = p2.getNameCount();
                if (depth1 != depth2) {
                    return (depth1 > depth2) ? 1 : -1;
                } else {
                    return p1.compareTo(p2);
                }
            }
        });
        return fileList;

    }

    public JsonObject addDocument(String filename, String folder, String description, String[] tags, boolean restrict) {
        ArrayList<String> tagArray = new ArrayList<String>();
        tagArray.add("Document");
        if (tags != null) {
            for (String tag : tags) {
                tagArray.add(tag);
            }
        }
        tags = tagArray.toArray(new String[tagArray.size()]);

        JsonObject file = addFile(filename, folder, description, tags, restrict);
System.out.println(file.toString());
        convertDocument(file);
        return file;
    }

    public void convertDocument(JsonObject file) {
        // Get file/stream, pass to convert tools, get two outputs: html/pdf doc and
        // annotations file
        // Here are samples
        Path convertedFile = Paths.get("tmp", "convertedFile.pdf");
        Path annotationFile = Paths.get("tmp", "annotationFile.json");
        addAuxilliaryFile(file.getJsonObject("dataFile").getInt("id"), convertedFile, "AnnoRep", true, "ingestPDF", "v1");
        addAuxilliaryFile(file.getJsonObject("dataFile").getInt("id"), annotationFile, "AnnoRep", true, "annotationJson", "v1");

    }
    /*
     * Upload aux export API_TOKEN=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx export
     * FILENAME='auxfile.txt' export FILE_ID='12345' export FORMAT_TAG='dpJson'
     * export FORMAT_VERSION='v1' export SERVER_URL=https://demo.dataverse.org
     * 
     * curl -H X-Dataverse-key:$API_TOKEN -X POST -F "file=@$FILENAME" -F
     * 'origin=myApp' -F 'isPublic=true'
     * "$SERVER_URL/api/access/datafile/$FILE_ID/metadata/$FORMAT_TAG/$FORMAT_VERSION"
     * 
     * 
     * Download aux
     * 
     * export API_TOKEN=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx export
     * SERVER_URL=https://demo.dataverse.org export FILE_ID='12345' export
     * FORMAT_TAG='dpJson' export FORMAT_VERSION='v1'
     * 
     * curl "$SERVER_URL/api/access/datafile/$FILE_ID/$FORMAT_TAG/$FORMAT_VERSION"
     */

    private void addAuxilliaryFile(int fileId, Path annotationFile, String origin, boolean isPublic, String formatTag, String formatVersion) {
        dvS.addAuxilliaryFile(doi, fileId, annotationFile, origin, isPublic, formatTag, formatVersion);
    }

    public JsonObject addFile(String filename, String folder, String description, String[] tags, boolean restrict) {
        JsonObjectBuilder job = Json.createObjectBuilder();
        if (description != null) {
            job.add("description", description);
        }
        if (folder != null) {
            job.add("directoryLabel", folder);
        }
        if (tags != null && tags.length != 0) {
            JsonArrayBuilder jab = Json.createArrayBuilder();
            for (String tag : tags) {
                jab.add(tag);
            }
            job.add("categories", jab);
        }
        job.add("restrict", restrict);

        JsonObject response = dvS.addFile(doi, filename, job.build().toString());
        JsonObject file = response.getJsonObject("data").getJsonArray("files").getJsonObject(0);
        JsonArrayBuilder jab = Json.createArrayBuilder();

        for (JsonValue f : files) {
            jab.add(f);
        }
        jab.add(file);
        files = jab.build();
        Path filesPath = Paths.get("tmp/" + dvS.getPath() + "/" + doi.replaceAll("[:/]", "_") + ".files.json");
        try {
            Files.deleteIfExists(filesPath);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return file;
    }

}