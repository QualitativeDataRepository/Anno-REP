package edu.syr.qdr.annorep.core.service;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aspose.words.*;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import javax.xml.bind.JAXBElement;

import org.apache.http.entity.ContentType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.openpackaging.parts.relationships.RelationshipsPart;
import org.docx4j.relationships.Relationship;
import org.docx4j.relationships.Relationships;
import org.docx4j.wml.BooleanDefaultTrue;
import org.docx4j.wml.Br;
import org.docx4j.wml.CommentRangeEnd;
import org.docx4j.wml.CommentRangeStart;
import org.docx4j.wml.Comments.Comment;
import org.docx4j.wml.P;
import org.docx4j.wml.P.Hyperlink;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase.PStyle;
import org.docx4j.wml.R;
import org.docx4j.wml.RPr;
import org.docx4j.wml.Text;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import edu.syr.qdr.annorep.core.entity.Documents;
import edu.syr.qdr.annorep.core.repository.DocumentsRepository;
import edu.syr.qdr.annorep.core.util.Annotation;
import edu.syr.qdr.annorep.core.util.PdfAnnotationProcessor;
import edu.syr.qdr.annorep.core.util.StringFragment;
import fr.opensagres.poi.xwpf.converter.pdf.PdfConverter;
import fr.opensagres.poi.xwpf.converter.pdf.PdfOptions;
import lombok.extern.slf4j.Slf4j;

import org.apache.pdfbox.Loader;

@Slf4j
@Component
public class DocumentsService {

    // Number of characters Hypothesis expects in the pre/post comment anchors for a
    // comment
    protected static final int ANCHOR_SIZE = 32;
    final static String PLACEHOLDER_TEXT = "PLACEHOLDER TEXT";

    @Autowired
    DataverseService dataverseService;

    private DocumentsRepository documentsRepository;

    public DocumentsService(DocumentsRepository documentsRepository) {
        this.documentsRepository = documentsRepository;
    }

    public List<Documents> getDocuments() {
        return documentsRepository.findAll();
    }

    public Documents getDocument(long id) {
        Documents d = null;
        if (documentsRepository.existsById(id)) {
            d = documentsRepository.getById(id);
        }
        return d;
    }

    public Documents saveDocument(Documents documents) {
        return documentsRepository.save(documents);
    }

    public Documents convertDoc(Long id, String apikey) {
        log.info("Converting: " + id);
        Documents d = getDocument(id);
        log.debug(id + " exists? :" + (d != null));
        // log.debug(id + " exists? :" + documentsRepository.existsById(id));

        /*
         * try { JsonObject response = dataverseService.getAPIJsonResponse("/api/files/"
         * + id + "/metadata/draft", apikey); log.debug(String.join(", ",
         * response.keySet())); } catch (Exception e) { return null; }
         */
        if (d == null) {

            try {
                d = retrieveDocFor(id, apikey);
            } catch (Exception e) {
                log.warn("Error searching for " + id + ": " + e.getLocalizedMessage());
                return null;
            }

            try {
                JsonArray response = dataverseService.getAPIJsonResponse("/api/access/datafile/" + id + "/auxiliary/AnnoRep", apikey).asJsonObject().getJsonArray("data");
                JsonValue auxObj = response.stream().filter((item -> item.asJsonObject().getString("formatVersion").equals("v1.0"))).findFirst().orElse(null);
                if (auxObj != null) {
                    d.setConverted(true);
                }
            } catch (Exception e) {
                // No cached object and can't get find the datafile with this id
                System.out.println(e.getMessage());
            }
            saveDocument(d);
            d = parseAnnotations(id, apikey, false);
            saveDocument(d);
        }
        if (d == null) {
            return null;
        }
        if (d.isConverted()) {
            return d;
        }

        // Valid datafile and valid version to convert
        // Any response means the datafile exists, so create the required aux files
        // Path convertedFile = Paths.get("tmp", "convertedFile.pdf");

        // Path annotationFile = Paths.get("tmp", "annotationFile.json");
        switch (d.getMimetype()) {
        case "application/pdf":

            try (InputStream pdfIn = dataverseService.getFile(id, apikey);) {
                createAnnotationsAndPdfFromPdf(id, pdfIn, apikey);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            break;
        case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":

            try (InputStream docIn = dataverseService.getFile(id, apikey);) {
                dataverseService.addAuxiliaryFile(id, createPdfFromDocxAspose(docIn), ContentType.create("application/pdf", StandardCharsets.UTF_8), "AnnoRep", false, "ingestPDF", "v1.0", "AR", apikey);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            try (InputStream docIn = dataverseService.getFile(id, apikey);) {
                dataverseService.addAuxiliaryFile(id, createAnnotations(id, docIn), ContentType.create("application/json", StandardCharsets.UTF_8), "AnnoRep", false, "annotationJson", "v1.0", "AR", apikey);
                d = parseAnnotations(id, apikey, true);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            break;
        }
        d.setConverted(true);
        d = saveDocument(d);
        // Report the state prior to successful conversion
        d.setConverted(false);
        return d;

    }

    private Documents retrieveDocFor(Long id, String apikey) throws Exception {
        String mimetype = dataverseService.getAPIJsonResponse("/api/search?q=identifier:" + id, apikey).asJsonObject().getJsonObject("data").getJsonArray("items").getJsonObject(0).getString("file_content_type");
        Documents d = new Documents();
        d.setId(id);
        d.setConverted(false);
        d.setMimetype(mimetype);
        return d;
    }

    private void createAnnotationsAndPdfFromPdf(Long id, InputStream pdfInputStream, String apikey) {

        Map<Integer, Annotation> annMap = new HashMap<Integer, Annotation>();
        Map<Integer, String> anchorMap = new HashMap<Integer, String>();

        try {
            ZipSecureFile.setMinInflateRatio(0.001);
            PDDocument document;
            document = Loader.loadPDF(pdfInputStream);

            log.debug("Pages: " + document.getNumberOfPages());
            log.debug("Version: " + document.getVersion());
            if (log.isDebugEnabled()) {

                PDFTextStripper textStripper = new PDFTextStripper();
                textStripper.setAddMoreFormatting(true);

                log.trace("Full Text: " + textStripper.getText(document));
            }

            final int titleId = -1;
            annMap.put(titleId, new Annotation());
            annMap.get(titleId).appendCommentText(PLACEHOLDER_TEXT);

            int numHighlights = 0;

            // Find 'Highlight' comments
            for (PDPage pdfPage : document.getPages()) {
                log.debug("Next Page");
                List<PDAnnotation> newAnnotations = new ArrayList<PDAnnotation>();
                List<PDAnnotation> annotations = pdfPage.getAnnotations();
                // Create a text stripper to find the anchor text in all of the rectangles
                // covered by the highlight comments
                PDFTextStripperByArea stripper = new PDFTextStripperByArea();

                for (int j = 0; j < annotations.size(); j++) {
                    PDAnnotation annot = annotations.get(j);
                    log.trace("Annotation " + j);
                    log.trace("Contents: " + annot.getContents());
                    if (annot.getContents() != null) {
                        log.trace("Contents len: " + annot.getContents().length());
                    }
                    log.trace("Subtype: " + annot.getSubtype());
                    if (annot.getSubtype().equals("Highlight")) {
                        Annotation ann = new Annotation();
                        numHighlights++;
                        log.debug("Found Highlight " + numHighlights + " : " + annot.getContents());

                        // Create an annotation for this highlight comment
                        annMap.put(numHighlights, ann);
                        // Add the comment text
                        ann.appendCommentText(annot.getContents());
                        // Get the rectangle that is highlighted so we can find the anchor text
                        PDRectangle rect = annot.getRectangle();
                        log.debug("Highlighted Rectangle " + numHighlights + " : " + rect.getLowerLeftX() + "," + rect.getLowerLeftY() + "," + rect.getUpperRightX() + "," + rect.getUpperRightY());

                        float x = rect.getLowerLeftX();
                        float y = rect.getUpperRightY();
                        float width = rect.getWidth();
                        float height = rect.getHeight();
                        int rotation = pdfPage.getRotation();
                        // Adjust based on rotation
                        if (rotation == 0) {
                            PDRectangle pageSize = pdfPage.getMediaBox();
                            log.debug("Media Box " + numHighlights + " : " + pageSize.getLowerLeftX() + "," + pageSize.getLowerLeftY() + "," + pageSize.getUpperRightX() + "," + pageSize.getUpperRightY() + ", " + pageSize.getHeight());
                            y = pageSize.getHeight() - y;
                        } else if (rotation == 90) {
                            // Do nothing
                        }
                        log.debug("Added Rectangle " + numHighlights + " : " + x + "," + y + "," + width + "," + height);

                        Rectangle2D.Float awtRect = new Rectangle2D.Float(x, y, width, height);
                        // Queue the rectangles
                        stripper.addRegion("" + j, awtRect);

                    } else {
                        newAnnotations.add(annot);
                    }
                }
                // Now get anchor text for all annotations on the page at once
                stripper.extractRegions(pdfPage);
                // reset to size at start of page
                numHighlights = anchorMap.size() - 1;
                for (int j = 0; j < annotations.size(); j++) {
                    PDAnnotation annot = annotations.get(j);
                    if (annot.getSubtype().equals("Highlight")) {
                        numHighlights++;
                        String anchorText = stripper.getTextForRegion("" + j);
                        log.debug("Len: " + anchorText.length());
                        while (anchorText.endsWith("\n") || anchorText.endsWith("\r")) {
                            log.debug("Removing " + anchorText.codePointAt(anchorText.length() - 1));
                            anchorText = anchorText.substring(0, anchorText.length() - 1);
                        }
                        // Put the anchor text in the map with the same id as the annotations
                        anchorMap.put(numHighlights, anchorText);
                        log.debug("Anchor text " + numHighlights + ": " + anchorText);

                    }
                }
                pdfPage.setAnnotations(newAnnotations);
            }
            try (PipedInputStream strippedPdfInputStream = new PipedInputStream()) {
                new Thread(new Runnable() {
                    public void run() {
                        try (PipedOutputStream pdfOutputStream = new PipedOutputStream(strippedPdfInputStream)) {
                            // process for creating pdf started
                            document.save(pdfOutputStream);
                        } catch (Exception e) {
                            log.error("Error creating pdf: " + e.getMessage());
                            e.printStackTrace();
                            throw new RuntimeException("Error creating pdf: " + e.getMessage());
                        }
                    }
                }).start();
                // Wait for bytes
                int i = 0;
                while (strippedPdfInputStream.available() <= 0 && i < 100) {
                    Thread.sleep(10);
                    i++;
                }
                dataverseService.addAuxiliaryFile(id, strippedPdfInputStream, ContentType.create("application/pdf", StandardCharsets.UTF_8), "AnnoRep", false, "ingestPDF", "v1.0", "AR", apikey);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            // Now that we have the comment text and anchor text, we need to stream through
            // the overall doc text to be able to populate TextQuote and TextPosition
            // Selectors
            // This class reads the pdf doc text and makes it look like a stream with anchor
            // start/anchor stop events in it so it can be processed the same way as with
            // docs files.
            PdfAnnotationProcessor pdfProc = new PdfAnnotationProcessor(annMap, anchorMap);
            pdfProc.processDocument(document);
            // The result of processDocument should be that all of the annotations in the
            // map are now complete and can be used to generate json for the annotations doc
            JsonArrayBuilder jab = Json.createArrayBuilder();
            annMap.entrySet().forEach(entry -> {
                entry.getValue().setDocUri(dataverseService.getPdfUrl(id));
                if (!entry.getKey().equals(titleId)) {
                    log.debug("Adding ann " + entry.getKey());
                    jab.add(entry.getValue().getJson());
                }
            });

            Documents d = getDocument(id);
            JsonArray anns = jab.build();
            if (d != null) {
                d.setTitleAnnotation(annMap.get(titleId).getJson().toString());
                d.setAnnotations(anns.toString());
            }
            saveDocument(d);

            log.debug("Have " + jab.build().size() + " annotations");
            jab.add(0, annMap.get(titleId).getJson());
            anns.forEach(annObj -> {
                jab.add(annObj);
            });
            anns = jab.build();
            log.debug("Now have " + anns.size() + " annotations");
            String annStr = anns.toString();
            log.debug("String is  " + annStr);

            dataverseService.addAuxiliaryFile(id, new ByteArrayInputStream(annStr.getBytes(StandardCharsets.UTF_8)), ContentType.create("application/json", StandardCharsets.UTF_8), "AnnoRep", false, "annotationJson", "v1.0", "AR", apikey);
            // Return a stream that can be used to create the annotations aux file.
            // return new ByteArrayInputStream(annStr.getBytes(StandardCharsets.UTF_8));

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private InputStream createPdfFromDocxAspose(InputStream docInputStream) throws Exception{
    Document wordDoc = new com.aspose.words.Document(docInputStream);
  //convert docx to pdf
  wordDoc.save("d:/git/Anno-Rep/target/Output.pdf");
  log.info("Done with Aspose");
  return new FileInputStream(new File("d:/git/Anno-Rep/target/Output.pdf"));
    }
  
    private InputStream createPdfFromDocx(InputStream docInputStream) {
        PipedInputStream pdfInputStream = new PipedInputStream();
        try {
            ZipSecureFile.setMinInflateRatio(0.001);
            new Thread(new Runnable() {
                public void run() {
                    try (PipedOutputStream pdfOutputStream = new PipedOutputStream(pdfInputStream)) {
                        // process for creating pdf started
                        XWPFDocument document = new XWPFDocument(docInputStream);
                        PdfOptions options = PdfOptions.getDefault();
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
                    Map<BigInteger, Annotation> annotationMap = new HashMap<BigInteger, Annotation>();
                    // ANCHOR_SIZE chars before a comment
                    Map<BigInteger, StringFragment> preCommentText = new HashMap<BigInteger, StringFragment>();
                    // ANCHOR_SIZE chars after a comment
                    Map<BigInteger, StringFragment> postCommentText = new HashMap<BigInteger, StringFragment>();
                    // The document text that is the anchor text the comment is about
                    Map<BigInteger, StringBuilder> commentedText = new HashMap<BigInteger, StringBuilder>();
                    // The comment itself.
                    Map<BigInteger, StringBuilder> commentText = new HashMap<BigInteger, StringBuilder>();
                    String title = null;
                    Map<String, String> commentRelationshipMap = new HashMap<String, String>();

                    StringBuilder convertedText = new StringBuilder();

                    try (PipedOutputStream annOutputStream = new PipedOutputStream(annInputStream)) {
                        // process for creating ann started
                        WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage
                                .load(docInputStream);
                        MainDocumentPart mainDocumentPart = wordMLPackage
                                .getMainDocumentPart();

                        //Todo - handle null cPart
                        CommentsPart cPart = mainDocumentPart.getCommentsPart();
                        if (cPart != null) {
                            RelationshipsPart rPart = cPart.getRelationshipsPart(false);
                            if (rPart != null) {
                                Relationships rels = rPart.getRelationships();
                                if (rels != null) {
                                    List<Relationship> relList = rels.getRelationship();
                                    relList.forEach((Relationship r) -> {
                                        commentRelationshipMap.put(r.getId(), r.getTarget());
                                        System.out.println("RTarg: " + r.getTarget());
                                        System.out.println("RId: " + r.getId());
                                    });
                                }
                            }
                        }
                        // Create faux Title Comment:
                        BigInteger titleId = BigInteger.valueOf(-1);
                        annotationMap.put(titleId, new Annotation());
                        preCommentText.put(titleId, new StringFragment(ANCHOR_SIZE, true));
                        postCommentText.put(titleId, new StringFragment(ANCHOR_SIZE, false));
                        commentedText.put(titleId, new StringBuilder());
                        commentText.put(titleId, new StringBuilder(PLACEHOLDER_TEXT));
                        List<Comment> comments = new ArrayList<Comment>();
                        if(cPart!=null) {
                            comments = cPart.getContents().getComment();
                        }
                        
                        for (Comment c : comments) {
                            // System.out.println(c.getAuthor());
                            // System.out.println(c.getDate().toString());
                            // System.out.println(c.getId());
                            BigInteger id = c.getId();
                            // Preconfigure buffers for each comment
                            annotationMap.put(id, new Annotation());
                            preCommentText.put(id, new StringFragment(ANCHOR_SIZE, true));
                            postCommentText.put(id, new StringFragment(ANCHOR_SIZE, false));
                            commentedText.put(id, new StringBuilder());
                            commentText.put(id, new StringBuilder());
                            for (Object o : c.getContent()) {
                                // System.out.println("Found o = " + o.getClass().getCanonicalName());
                                if (o instanceof P) {
                                    P para = (P) o;

                                    for (Object po : para.getContent()) {
                                        System.out.println("Found po = " + po.getClass().getCanonicalName());
                                        if (po instanceof JAXBElement) {
                                            JAXBElement<?> jb = (JAXBElement<?>) po;

                                            System.out.println(jb.getName());
                                            System.out.println(jb.getDeclaredType());
                                            System.out.println(jb.getValue().getClass().getCanonicalName());
                                            if (jb.getValue() instanceof Hyperlink) {

                                                System.out.println("Found hyperlink");
                                                Hyperlink link = (Hyperlink) jb.getValue();
                                                System.out.println("Id: " + link.getId());
                                                System.out.println("TgtFr: " + link.getTgtFrame());
                                                System.out.println("TTip: " + link.getTooltip());
                                                System.out.println("Str: " + link.toString());
                                                System.out.println("Anchor: " + link.getAnchor());
                                                System.out.println("Loc: " + link.getDocLocation());
                                                for (Object lo : link.getContent()) {
                                                    if (lo instanceof R) {

                                                        System.out.println("HL text: " + getStyedTextFromRun((R) lo));

                                                        String htmlLink = "<a href=\"" + commentRelationshipMap.get(link.getId()) + "\">" + getStyedTextFromRun((R) lo) + "</a>";
                                                        annotationMap.get(id).appendCommentText(htmlLink);
                                                        commentText.get(id).append(htmlLink);
                                                    }
                                                }
                                            }
                                        }

                                        if (po instanceof R) {
                                            String styledText = getStyedTextFromRun((R) po);
                                            annotationMap.get(id).appendCommentText(styledText);
                                            commentText.get(id).append(styledText);

                                        }
                                    }
                                    // System.out.println("Comment # " + id + ": " +
                                    // commentText.get(id).toString());
                                }
                            }
                        }
                        Map<BigInteger, Boolean> inComment = new HashMap<BigInteger, Boolean>();
                        Map<BigInteger, Boolean> commentStarted = new HashMap<BigInteger, Boolean>();

                        commentText.keySet().forEach(id -> {
                            inComment.put(id, false);
                            commentStarted.put(id, false);
                        });
                        boolean firstPara = true;
                        for (Object o : mainDocumentPart.getContent()) {

                            if (o instanceof P) {
                                P para = (P) o;

                                final String paraSep = "\n\n";
                                if (!firstPara) {
                                    annotationMap.values().forEach(ann -> {
                                        ann.addText(paraSep);
                                    });
                                    convertedText.append(paraSep);

                                    inComment.forEach((id, inside) -> {

                                        boolean started = commentStarted.get(id);
                                        if (!started) {
                                            preCommentText.get(id).addString(paraSep);
                                            // System.out.println("Adding '" + paraSep + "' to preText for" + id);
                                        } else {
                                            if (inside) {
                                                commentedText.get(id).append(paraSep);
                                                // System.out.println("Adding '" + paraSep + "' to commentedText for" + id);
                                            } else {
                                                postCommentText.get(id).addString(paraSep);
                                                // System.out.println("Adding '" + paraSep + "' to postText for" + id);
                                            }
                                        }
                                    });

                                } else {
                                    firstPara = false;
                                }
                                for (Object po : para.getContent()) {
                                    // System.out.println("PO: " + po.getClass().getCanonicalName());
                                    if (po instanceof CommentRangeStart) {
                                        // ToDo = get id and handle overlapping comments
                                        BigInteger id = ((CommentRangeStart) po).getId();
                                        annotationMap.get(id).startAnchor();
                                        commentStarted.put(id, true);
                                        inComment.put(id, true);
                                        // System.out.println("Comment Start: " + id);
                                        // System.out.println("PlainText: " + String.join("", nonCommentTexts));
                                        // nonCommentTexts.clear();
                                    }
                                    if (po instanceof CommentRangeEnd) {
                                        BigInteger id = ((CommentRangeEnd) po).getId();
                                        annotationMap.get(id).endAnchor();
                                        inComment.put(id, false);
                                        // System.out.println("Comment End: " + id);
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

                                                    PPr ppr = para.getPPr();
                                                    if (ppr != null) {
                                                        PStyle style = ppr.getPStyle();
                                                        // System.out.println("ParaStyle: " + style.getVal());
                                                        if (style != null && style.getVal().equals("Heading1") && title == null) {
                                                            title = text;
                                                            annotationMap.values().forEach(ann -> ann.setDocTitle(text));
                                                            annotationMap.get(titleId).startAnchor();
                                                            commentStarted.put(titleId, true);
                                                            inComment.put(titleId, true);
                                                        }
                                                    }

                                                    // When text is found iterate through
                                                    annotationMap.values().forEach(ann -> {
                                                        ann.addText(text);
                                                    });
                                                    convertedText.append(text);

                                                    inComment.forEach((id, inside) -> {
                                                        boolean started = commentStarted.get(id);
                                                        if (!started) {
                                                            preCommentText.get(id).addString(text);
                                                            // System.out.println("Adding '" + text + "' to preText for" + id);
                                                        } else {
                                                            if (inside) {
                                                                commentedText.get(id).append(text);
                                                                // System.out.println("Adding '" + text + "' to commentedText for" + id);
                                                            } else {
                                                                postCommentText.get(id).addString(text);
                                                                // System.out.println("Adding '" + text + "' to postText for" + id);
                                                            }
                                                        }
                                                    });
                                                    if (title != null) {
                                                        annotationMap.get(titleId).endAnchor();
                                                        inComment.put(titleId, false);
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

                        // ToDo - could write the annotations/title to the Document now, but we're in a
                        // thread with no context

                        annotationMap.entrySet().forEach(entry -> {
                            entry.getValue().setDocUri(dataverseService.getPdfUrl(docId));
                            jab.add(entry.getValue().getJson());
                        });
                        String annStr = jab.build().toString();
                        // System.out.println("Annotations: " + annStr);
                        annOutputStream.write(annStr.getBytes(StandardCharsets.UTF_8));
                        JsonArrayBuilder oldjab = Json.createArrayBuilder();
                        inComment.forEach((id, inside) -> {
                            // System.out.println("Comment ID: " + id);
                            if (inside && commentStarted.get(id)) {
                                // System.out.println("Never found comment end!");
                            }
                            if (!commentStarted.get(id)) {
                                // System.out.println("Never found comment!");
                            }
                            // System.out.println("PreText: " + preCommentText.get(id).getString());
                            // System.out.println("DocumentTextBeingCommented: " +
                            // commentedText.get(id).toString());
                            // System.out.println("CommentText: " + commentText.get(id).toString());

                            // System.out.println("PostText: " + postCommentText.get(id).getString());

                            oldjab.add(createAnnotation(docId, preCommentText.get(id).getString(), commentedText.get(id).toString(), postCommentText.get(id).getString(), commentText.get(id).toString(), theTitle));
                        });
                        annStr = oldjab.build().toString();
                        // System.out.println("\n\nOld Annotations: " + annStr);

                        // This is getting all text
                        String textNodesXPath = "//w:t";
                        List<Object> textNodes = mainDocumentPart
                                .getJAXBNodesViaXPath(textNodesXPath, true);
                        for (Object obj : textNodes) {
                            Text text = (Text) ((JAXBElement<?>) obj).getValue();
                            String textValue = text.getValue();
                            // System.out.println("Whole Text: " + textValue);
                        }
                        // System.out.println("Converted text: |" + convertedText.toString() + "|");

                        // PdfConverter.getInstance().convert(document, pdfOutputStream, options);

                    } catch (Exception e) {
                        log.error("Error creating ann: " + e.getMessage());
                        e.printStackTrace();
                        throw new RuntimeException("Error creating ann: " + e.getMessage());
                    }
                }

                private String getStyedTextFromRun(R po) {
                    String val = "";
                    R run = (R) po;
                    RPr style = run.getRPr();
                    boolean bold = false;
                    boolean italic = false;
                    if (style != null) {
                        bold = style.getB() instanceof BooleanDefaultTrue;
                        italic = style.getI() instanceof BooleanDefaultTrue;
                        System.out.println("Bold?: " + (style.getB() instanceof BooleanDefaultTrue));
                        System.out.println("Italic?: " + (style.getI() instanceof BooleanDefaultTrue));
                    }
                    for (Object ro : run.getContent()) {
                        System.out.println("Found ro = " + ro.getClass().getCanonicalName());
                        if (ro instanceof Br) {
                            val = val + "\n";
                        }
                        if (ro instanceof JAXBElement) {
                            JAXBElement<?> jb = (JAXBElement<?>) ro;

                            System.out.println(jb.getName());
                            // System.out.println(jb.getDeclaredType());
                            // System.out.println(jb.getValue().getClass().getCanonicalName());
                            // System.out.println(jb.getValue());
                            if (jb.getValue() instanceof Text) {
                                Text t = (Text) jb.getValue();

                                System.out.println(t.getSpace());
                                System.out.println(t.getValue());
                                // System.out.println((long)t.getValue().charAt(1));
                                val = t.getValue();
                                if (bold) {
                                    val = "<b>" + val + "</b>";
                                }
                                if (italic) {
                                    val = "<i>" + val + "</i>";
                                }

                            }

                        }

                    }
                    return val;
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
                System.out.println("Waiting: " + i);
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

    public int deleteAuxDocs(Long id, String apikey) {
        log.info("Deleting pdf and annotations for: " + id);
        Documents d = getDocument(id);
        int status1 = dataverseService.deleteAPI(dataverseService.getAnnPath(id), apikey);
        int status2 = dataverseService.deleteAPI(dataverseService.getPdfPath(id), apikey);
        if ((status1 == 200 || status1 == 404) && (status2 == 200 || status2 == 404) && d != null) {
            // Clear our flag if the deletes succeeded or the docs don't exist (e.g. some
            // error prevented the creation of one or both docs, one was already deleted,
            // etc.)
            d.setConverted(false);
            d.setTitleAnnotation(null);
            d.setAnnotations(null);
            saveDocument(d);
        }
        if ((status1 == status2)) {
            return status1;
        } else {
            return 500;
        }

    }

    public Documents parseAnnotations(Long id, String apikey, boolean force) {
        Documents d = getDocument(id);
        if(d==null) {
            try {
            d=retrieveDocFor(id, apikey);
            } catch (Exception e) {
                log.debug("Couldn't retrieve file id: " + id + ": " + e.getMessage());
            }
        }
        if (d != null && (d.isConverted() == true || force)) {
            try {
                JsonArray response = dataverseService.getAPIJsonResponse(dataverseService.getAnnPath(id), apikey).asJsonArray();
                log.debug(response.toString());
                d.setTitleAnnotation(response.get(0).toString());
                JsonArrayBuilder jab = Json.createArrayBuilder(response);

                jab.remove(0);
                JsonArray ja = jab.build();
                d.setAnnotations(ja.toString());
                d = saveDocument(d);
            } catch (Exception e) {
                // Class cast - 404 returns a jsonObject rather than Array
                log.debug(e.getMessage());
                d = null;
            }
        }
        return d;
    }

}
