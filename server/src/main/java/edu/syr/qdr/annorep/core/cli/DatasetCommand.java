package edu.syr.qdr.annorep.core.cli;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonWriter;
import javax.json.JsonWriterFactory;
import javax.json.stream.JsonGenerator;

import edu.syr.qdr.annorep.core.Dataset;
import edu.syr.qdr.annorep.core.DataverseServer;
import edu.syr.qdr.annorep.core.User;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "Dataset", version = "Dataset 1.0", mixinStandardHelpOptions = true)
public class DatasetCommand implements Runnable {

    public DatasetCommand() {
    }

    private DataverseServer dvs;
    private Dataset d;

    @Parameters(index = "0", description = "The Dataverse Server URL")
    protected String url;

    @Parameters(index = "1", description = "The DOI of the Dataset.")
    protected String doi;

    @Option(names = { "-k", "--key" }, description = "API Key")
    String apiKey = null;

    @Option(names = { "-r", "--refresh" }, description = "Refresh cached information from server")
    private boolean refresh = false;

    public void run() {
        commonSetup(url, doi, apiKey, refresh);
        System.out.println(d.getMetadata().getString("Title"));
        StringWriter sw = new StringWriter();
        Map<String, Object> map = new HashMap<>();
        map.put(JsonGenerator.PRETTY_PRINTING, true);
        JsonWriterFactory writerFactory = Json.createWriterFactory(map);
        JsonWriter jsonWriter = writerFactory.createWriter(sw);
        jsonWriter.writeObject(d.getMetadata());
        jsonWriter = writerFactory.createWriter(sw);
        System.out.println(sw.toString());
    }

    @Command(name = "listFiles", description = "List files in the dataset")
    void listFiles(
            @Parameters(paramLabel = "<folder>", defaultValue = "\\", description = "top folder to display") String folder) {
        commonSetup(url, doi, apiKey, refresh);
        for (String s : d.getFileListing()) {
            if (s.startsWith(folder)) {
                System.out.println(s);
            }
        }
    }

    @Command(name = "uploadFile", description = "Upload a file to the dataset")
    void uploadFile(
            @Parameters(paramLabel = "<file>", description = "local path for the file to upload") String filename,
            @Parameters(paramLabel = "<description>", description = "a description for the file") String description,

            @Parameters(paramLabel = "<folder>", defaultValue = "\\", description = "folder for file in Dataverse") String folder,
            @Parameters(arity = "1..*", paramLabel = "<tags>", description = "tags to add") String[] tags) {
        commonSetup(url, doi, apiKey, refresh);
        JsonObject file = d.addFile(filename, folder.replace("\\", "/"), description, tags, false);
        System.out.println(file.toString());
    }
    
    @Command(name = "uploadDocument", description = "Upload a document to the dataset and created a converted pdf and annotations file")
    void uploadDocument(
            @Parameters(paramLabel = "<file>", description = "local path for the file to upload") String filename,
            @Parameters(paramLabel = "<description>", description = "a description for the file") String description,

            @Parameters(paramLabel = "<folder>", defaultValue = "\\", description = "folder for file in Dataverse") String folder,
            @Parameters(arity = "0..*", paramLabel = "<tags>", description = "tags to add") String[] tags) {
        commonSetup(url, doi, apiKey, refresh);
        JsonObject file = d.addDocument(filename, folder.replace("\\", "/"), description, tags, false);
        System.out.println(file.toString());
    }

    private void commonSetup(String url, String doi, String key, boolean refresh) {
        User u = null;
        if (apiKey != null) {
            u = new User(apiKey);
        }
        dvs = DataverseServer.getServerFor(url, u);
        d = Dataset.getDataset(dvs, doi, refresh);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DatasetCommand()).execute(args);
        System.exit(exitCode);
    }

}
