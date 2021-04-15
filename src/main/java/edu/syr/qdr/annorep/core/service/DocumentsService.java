package edu.syr.qdr.annorep.core.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import javax.xml.bind.JAXBElement;

import org.apache.http.entity.ContentType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.wml.CommentRangeEnd;
import org.docx4j.wml.CommentRangeStart;
import org.docx4j.wml.Comments.Comment;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase.PStyle;
import org.docx4j.wml.R;
import org.docx4j.wml.Text;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import edu.syr.qdr.annorep.core.controller.DocumentsController;
import edu.syr.qdr.annorep.core.entity.Documents;
import edu.syr.qdr.annorep.core.repository.DocumentsRepository;
import edu.syr.qdr.annorep.core.util.StringFragment;
import fr.opensagres.poi.xwpf.converter.pdf.PdfConverter;
import fr.opensagres.poi.xwpf.converter.pdf.PdfOptions;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class DocumentsService {

    // Number of characters Hypothesis expects in the pre/post comment anchors for a
    // comment
    protected static final int ANCHOR_SIZE = 32;

    @Autowired
    DataverseService dataverseService;

    private DocumentsRepository documentsRepository;

    public DocumentsService(DocumentsRepository documentsRepository) {
        this.documentsRepository = documentsRepository;
    }

    public List<Documents> getDocuments() {
        return documentsRepository.findAll();
    }

    public Documents saveDocument(Documents documents) {
        return documentsRepository.save(documents);
    }

    public Documents convertDoc(Long id, String apikey) {
        log.info("Convert: " + id);
        Documents d = null;
        if (documentsRepository.existsById(id)) {
          d = documentsRepository.getOne(id);
        }
        log.info(id + " exists? :" + (d != null));
        // log.info(id + " exists? :" + documentsRepository.existsById(id));

        /*
         * try { JsonObject response = dataverseService.getAPIJsonResponse("/api/files/"
         * + id + "/metadata/draft", apikey); log.info(String.join(", ",
         * response.keySet())); } catch (Exception e) { return null; }
         */
        if (d == null) {
            d = new Documents();
            d.setId(id);
            d.setConverted(false);
            try {
                JsonArray response = dataverseService.getAPIJsonResponse("/api/access/datafile/" + id + "/metadata/aux/AnnoRep", apikey).getJsonArray("data");
                JsonValue auxObj = response.stream().filter((item -> item.asJsonObject().getString("formatVersion").equals("v1.0"))).findFirst().orElse(null);
                if (auxObj != null) {
                    d.setConverted(true);
                }
            } catch (Exception e) {
                // No cached object and can't get find the datafile with this id
                System.out.println(e.getMessage());
            }
            d = documentsRepository.save(d);

        }
        if (d == null) {
            return null;
        }
        if (d.isConverted()) {
            return d;
        }

        // Valid datafile and valid version to convert
        // Any response means the datafile exists, so create the required aux files
//        Path convertedFile = Paths.get("tmp", "convertedFile.pdf");
//        Path annotationFile = Paths.get("tmp", "annotationFile.json");
        try (InputStream docIn = dataverseService.getFile(id, apikey);) {
            dataverseService.addAuxiliaryFile(id, createPDF(docIn), ContentType.create("application/pdf", StandardCharsets.UTF_8), "AnnoRep", false, "ingestPDF", "v1.0", apikey);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        try (InputStream docIn = dataverseService.getFile(id, apikey);) {
            dataverseService.addAuxiliaryFile(id, createAnnotations(id, docIn), ContentType.create("application/json", StandardCharsets.UTF_8), "AnnoRep", false, "annotationJson", "v1.0", apikey);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        d.setConverted(true);
        d = saveDocument(d);
        //Report the state prior to successful conversion
        d.setConverted(false);
        return d;

    }

    private InputStream createPDF(InputStream docInputStream) {
        PipedInputStream pdfInputStream = new PipedInputStream();
        try {

            new Thread(new Runnable() {
                public void run() {
                    try (PipedOutputStream pdfOutputStream = new PipedOutputStream(pdfInputStream)) {
                        // process for creating pdf started
                        XWPFDocument document = new XWPFDocument(docInputStream);
                        PdfOptions options = PdfOptions.create();
                        PdfConverter.getInstance().convert(document, pdfOutputStream, options);

                    } catch (Exception e) {
                        log.error("Error creating pdf: " + e.getMessage());
                        e.printStackTrace();
                        throw new RuntimeException("Error creating pdf: " + e.getMessage());
                    }
                }
            }).start();
            // Wait for bytes
            int i = 0;
            while (pdfInputStream.available() <= 0 && i < 100) {
                Thread.sleep(10);
                i++;
            }
            return pdfInputStream;
        } catch (IOException e) {
            // TODO Auto-generated catch block
            try {
                pdfInputStream.close();
            } catch (IOException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }
            e.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            try {
                pdfInputStream.close();
            } catch (IOException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }
            e.printStackTrace();
        }
        return null;
    }

    private InputStream createAnnotations(Long docId, InputStream docInputStream) {
        PipedInputStream annInputStream = new PipedInputStream();
        try {

            new Thread(new Runnable() {
                public void run() {
                    // ANCHOR_SIZE chars before a comment
                    Map<BigInteger, StringFragment> preCommentText = new HashMap<BigInteger, StringFragment>();
                    // ANCHOR_SIZE chars after a comment
                    Map<BigInteger, StringFragment> postCommentText = new HashMap<BigInteger, StringFragment>();
                    // The document text that is the anchor text the comment is about
                    Map<BigInteger, StringBuilder> commentedText = new HashMap<BigInteger, StringBuilder>();
                    // The comment itself.
                    Map<BigInteger, StringBuilder> commentText = new HashMap<BigInteger, StringBuilder>();
                    String title = null;

                    try (PipedOutputStream annOutputStream = new PipedOutputStream(annInputStream)) {
                        // process for creating ann started
                        WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage
                                .load(docInputStream);
                        MainDocumentPart mainDocumentPart = wordMLPackage
                                .getMainDocumentPart();
                        CommentsPart cPart = mainDocumentPart.getCommentsPart();
                        List<Comment> comments = cPart.getContents().getComment();
                        for (Comment c : comments) {
                            // System.out.println(c.getAuthor());
                            // System.out.println(c.getDate().toString());
                            // System.out.println(c.getId());
                            BigInteger id = c.getId();
                            // Preconfigure buffers for each comment
                            preCommentText.put(id, new StringFragment(ANCHOR_SIZE, true));
                            postCommentText.put(id, new StringFragment(ANCHOR_SIZE, false));
                            commentedText.put(id, new StringBuilder());
                            commentText.put(id, new StringBuilder());
                            for (Object o : c.getContent()) {
                                // System.out.println("Found o = " + o.getClass().getCanonicalName());
                                if (o instanceof P) {
                                    P para = (P) o;
                                    for (Object po : para.getContent()) {
                                        // System.out.println("Found po = " + po.getClass().getCanonicalName());
                                        if (po instanceof R) {
                                            R run = (R) po;
                                            for (Object ro : run.getContent()) {
                                                // System.out.println("Found ro = " + ro.getClass().getCanonicalName());
                                                if (ro instanceof JAXBElement) {
                                                    JAXBElement<?> jb = (JAXBElement<?>) ro;
                                                    // System.out.println(jb.getName());
                                                    // System.out.println(jb.getDeclaredType());
                                                    // System.out.println(jb.getValue().getClass().getCanonicalName());
                                                    // System.out.println(jb.getValue());
                                                    if (jb.getValue() instanceof Text) {
                                                        Text t = (Text) jb.getValue();
                                                        System.out.println(t.getSpace());
                                                        System.out.println(t.getValue());
                                                        // System.out.println((long)t.getValue().charAt(1));
                                                        commentText.get(id).append(t.getValue());
                                                    }

                                                }
                                            }
                                        }
                                    }
                                    System.out.println("Comment # " + id + ": " + commentText.get(id).toString());
                                }
                            }
                        }
                        Map<BigInteger, Boolean> inComment = new HashMap<BigInteger, Boolean>();
                        Map<BigInteger, Boolean> commentStarted = new HashMap<BigInteger, Boolean>();
                        commentText.keySet().forEach(id -> {
                            inComment.put(id, false);
                            commentStarted.put(id, false);
                        });

                        for (Object o : mainDocumentPart.getContent()) {
                            if (o instanceof P) {
                                P para = (P) o;

                                String paraSep = "\n\n";
                                inComment.forEach((id, inside) -> {
                                    boolean started = commentStarted.get(id);
                                    if (!started) {
                                        preCommentText.get(id).addString(paraSep);
                                        System.out.println("Adding '" + paraSep + "' to preText for" + id);
                                    } else {
                                        if (inside) {
                                            commentedText.get(id).append(paraSep);
                                            System.out.println("Adding '" + paraSep + "' to commentedText for" + id);
                                        } else {
                                            postCommentText.get(id).addString(paraSep);
                                            System.out.println("Adding '" + paraSep + "' to postText for" + id);
                                        }
                                    }
                                });
                                for (Object po : para.getContent()) {
                                    // System.out.println("PO: " + po.getClass().getCanonicalName());
                                    if (po instanceof CommentRangeStart) {
                                        // ToDo = get id and handle overlapping comments
                                        BigInteger id = ((CommentRangeStart) po).getId();
                                        commentStarted.put(id, true);
                                        inComment.put(id, true);
                                        System.out.println("Comment Start: " + id);
                                        // System.out.println("PlainText: " + String.join("", nonCommentTexts));
                                        // nonCommentTexts.clear();
                                    }
                                    if (po instanceof CommentRangeEnd) {
                                        BigInteger id = ((CommentRangeEnd) po).getId();
                                        inComment.put(id, false);
                                        System.out.println("Comment End: " + id);
                                        // System.out.println("Anchor: " + String.join("", commentTexts));
                                        // commentTexts.clear();
                                    }

                                    if (po instanceof R) {
                                        for (Object ro : ((R) po).getContent()) {
                                            // System.out.println("RO: " + ro.getClass().getCanonicalName());

                                            if (ro instanceof JAXBElement) {
                                                JAXBElement<?> jb = (JAXBElement<?>) ro;
                                                // System.out.println(jb.getName());
                                                // System.out.println(jb.getDeclaredType());
                                                // System.out.println(jb.getValue().getClass().getCanonicalName());
                                                if (jb.getValue() instanceof Text) {
                                                    String text = ((Text) jb.getValue()).getValue();
                                                    // When text is found iterate through
                                                    inComment.forEach((id, inside) -> {
                                                        boolean started = commentStarted.get(id);
                                                        if (!started) {
                                                            preCommentText.get(id).addString(text);
                                                            System.out.println("Adding '" + text + "' to preText for" + id);
                                                        } else {
                                                            if (inside) {
                                                                commentedText.get(id).append(text);
                                                                System.out.println("Adding '" + text + "' to commentedText for" + id);
                                                            } else {
                                                                postCommentText.get(id).addString(text);
                                                                System.out.println("Adding '" + text + "' to postText for" + id);
                                                            }
                                                        }
                                                    });
                                                    PPr ppr = para.getPPr();
                                                    if (ppr != null) {
                                                        PStyle style = ppr.getPStyle();
                                                        System.out.println("ParaStyle: " + style.getVal());
                                                        if (style.getVal().equals("Heading1") && title == null) {
                                                            title = text;
                                                        }
                                                    }
                                                    /*
                                                     * if (inComment) { commentTexts.add(((Text) jb.getValue()).getValue()); } else
                                                     * { nonCommentTexts.add(((Text) jb.getValue()).getValue()); }
                                                     */
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                        }
                        JsonArrayBuilder jab = Json.createArrayBuilder();
                        final String theTitle = title;
                        inComment.forEach((id, inside) -> {
                            System.out.println("Comment ID: " + id);
                            if (inside && commentStarted.get(id)) {
                                System.out.println("Never found comment end!");
                            }
                            if (!commentStarted.get(id)) {
                                System.out.println("Never found comment!");
                            }
                            System.out.println("PreText: " + preCommentText.get(id).getString());
                            System.out.println("DocumentTextBeingCommented: " + commentedText.get(id).toString());
                            System.out.println("CommentText: " + commentText.get(id).toString());

                            System.out.println("PostText: " + postCommentText.get(id).getString());

                            jab.add(createAnnotation(docId, preCommentText.get(id).getString(), commentedText.get(id).toString(), postCommentText.get(id).getString(), commentText.get(id).toString(), theTitle));
                        });
                        String annStr = jab.build().toString();
                        System.out.println("Annotations: " + annStr);
                        annOutputStream.write(annStr.getBytes(StandardCharsets.UTF_8));

                        // This is getting all text
                        String textNodesXPath = "//w:t";
                        List<Object> textNodes = mainDocumentPart
                                .getJAXBNodesViaXPath(textNodesXPath, true);
                        for (Object obj : textNodes) {
                            Text text = (Text) ((JAXBElement<?>) obj).getValue();
                            String textValue = text.getValue();
                            System.out.println("Whole Text: " + textValue);
                        }

                        // PdfConverter.getInstance().convert(document, pdfOutputStream, options);

                    } catch (Exception e) {
                        log.error("Error creating ann: " + e.getMessage());
                        e.printStackTrace();
                        throw new RuntimeException("Error creating ann: " + e.getMessage());
                    }
                }

                private JsonObjectBuilder createAnnotation(Long docId, String prefix, String exact, String suffix, String comment, String title) {
                    JsonObjectBuilder annOb = Json.createObjectBuilder();
                    JsonObjectBuilder selector = Json.createObjectBuilder();
                    selector.add("type", "TextQuoteSelector");
                    selector.add("exact", exact);
                    selector.add("prefix", prefix);
                    selector.add("suffix", suffix);
                    annOb.add("target", Json.createArrayBuilder().add(Json.createObjectBuilder().add("source", dataverseService.getPdfUrl(docId)).add("selector", Json.createArrayBuilder().add(selector))));
                    annOb.add("text", comment);
                    annOb.add("uri", dataverseService.getPdfUrl(docId));
                    annOb.add("document", Json.createObjectBuilder().add("title", Json.createArrayBuilder().add(title)));
                    return annOb;
                }
            }).start();
            // Wait for bytes
            int i = 0;
            while (annInputStream.available() <= 0 && i < 100) {
                Thread.sleep(100);
                i++;
            }
            // Path annotationFile = Paths.get("tmp", "annotationFile.json");
            // return new FileInputStream(annotationFile.toFile());
            return annInputStream;
        } catch (IOException e) {
            // TODO Auto-generated catch block
            try {
                annInputStream.close();
            } catch (IOException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }
            e.printStackTrace();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            try {
                annInputStream.close();
            } catch (IOException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }
            e.printStackTrace();
        }
        return null;
    }

    public int deleteDoc(Long id, String apikey) {
        log.info("Deleting for: " + id);
        if (documentsRepository.existsById(id)) {
            documentsRepository.deleteById(id);
        }
        int status1 = dataverseService.deleteAPI(dataverseService.getAnnPath(id), apikey);
        int status2 = dataverseService.deleteAPI(dataverseService.getPdfPath(id), apikey);
        if ((status1 == status2)) {
            return status1;
        } else {
            return 500;
        }

    }

}
