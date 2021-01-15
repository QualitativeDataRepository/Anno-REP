package edu.syr.qdr.annorep.core.cli;

import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonWriter;
import javax.json.JsonWriterFactory;
import javax.json.stream.JsonGenerator;

import edu.syr.qdr.annorep.core.DataverseServer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "Dataset", version = "Dataset 1.0", mixinStandardHelpOptions = true)
public class DatasetCommand implements Runnable {

    public DatasetCommand() {
    }

    @Parameters(index = "0", description = "The Dataverse Server URL")
    protected String url;
    
    @Parameters(index = "1", description = "The DOI of the Dataset.")
    protected String doi;
    
    @Option(names = {"-r", "--refresh"}, description = "Refresh cached information from server")
    private boolean refresh = false;
    
    public void run() {
        
        DataverseServer dvs = DataverseServer.getServerFor(url);
        Dataset d = Dataset.getDataset(dvs, doi, refresh);
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
            @Parameters(paramLabel = "<folder>",defaultValue =  "\\",
            description = "top folder to display") String folder) {
        DataverseServer dvs = DataverseServer.getServerFor(url);
        Dataset d = Dataset.getDataset(dvs, doi, refresh);

        for(String s: d.getFileListing()) {
            if(s.startsWith(folder)) {
                System.out.println(s);
            }
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DatasetCommand()).execute(args);
        System.exit(exitCode);
    }

}
