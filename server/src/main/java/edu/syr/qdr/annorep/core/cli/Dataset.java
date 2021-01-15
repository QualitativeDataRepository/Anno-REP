package edu.syr.qdr.annorep.core.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonWriter;
import javax.json.JsonWriterFactory;
import javax.json.stream.JsonGenerator;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import edu.syr.qdr.annorep.core.DataverseServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

public class Dataset {

    private static final Logger logger = Logger.getLogger(DatasetCommand.class.getCanonicalName());

    private JsonObject metadata = null;
    private JsonArray files = null;

    public Dataset(JsonObject agg) {
        files = agg.getJsonArray("ore:aggregates");
        metadata = Json.createObjectBuilder(agg).remove("ore:aggregates").remove("schema:hasPart").build();
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
                d = new Dataset(r.readObject().getJsonObject("ore:describes"));
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else {
            JsonObject oremap = (JsonObject) dvs.getAPIJsonResponse("/api/datasets/export?exporter=OAI_ORE&persistentId=" + doi);
            d = new Dataset(oremap.getJsonObject("ore:describes"));
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

}