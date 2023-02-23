package edu.syr.qdr.annorep.core.service;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import javax.xml.bind.JAXBElement;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.http.entity.ContentType;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
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
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;
import org.jodconverter.core.document.DefaultDocumentFormatRegistry;
import org.jodconverter.core.office.OfficeUtils;
import org.jodconverter.local.JodConverter;
import org.jodconverter.local.office.LocalOfficeManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

import edu.syr.qdr.annorep.core.entity.Documents;
import edu.syr.qdr.annorep.core.repository.DocumentsRepository;
import edu.syr.qdr.annorep.core.util.Annotation;
import edu.syr.qdr.annorep.core.util.PdfAnnotationProcessor;
import edu.syr.qdr.annorep.core.util.StringFragment;
import lombok.extern.slf4j.Slf4j;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;

@Slf4j
@Component
public class DocumentsService {

    // Number of characters Hypothesis expects in the pre/post comment anchors for a
    // comment
    protected static final int ANCHOR_SIZE = 32;
    final static String PLACEHOLDER_TEXT = "PLACEHOLDER TEXT";

    boolean success = false;

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
            d = documentsRepository.getReferenceById(id);
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
                dataverseService.addAuxiliaryFile(id, createPdfFromDocxJODC(docIn), ContentType.create("application/pdf", StandardCharsets.UTF_8), "AnnoRep", false, "ingestPDF", "v1.0", "AR", apikey);
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
        if (d != null) {
            d.setConverted(true);
            d = saveDocument(d);
            // Report the state prior to successful conversion
            d.setConverted(false);
        }
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
                        // To get the rich/html text version of an annotation, one has to dig
                        boolean foundRichText = false;
                        for (Entry<COSName, COSBase> entry : annot.getCOSObject().entrySet()) {
                            // Not sure what RC stands for but this is the xhtml version of the text with
                            // spans with font sizes, weights, etc.
                            if (entry.getKey().getName().equals("RC")) {
                                String val = ((COSString) entry.getValue()).getString();
                                // The string has an xml header and a body element, we just want the child nodes
                                // in the body
                                // So convert to an XML representation and then write the children back to a
                                // string
                                DocumentBuilder builder;
                                try {
                                    builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                                    org.w3c.dom.Document doc = builder.parse(new InputSource(new StringReader(val)));
                                    TransformerFactory tf = TransformerFactory.newInstance();
                                    Transformer transformer;
                                    try {
                                        transformer = tf.newTransformer();
                                        // remove XML declaration
                                        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                                        StringWriter writer = new StringWriter();
                                        // Get child nodes
                                        org.w3c.dom.NodeList nl = doc.getElementsByTagName("body").item(0).getChildNodes();
                                        // Write each to the output string
                                        for (int n = 0; n < nl.getLength(); n++) {
                                            transformer.transform(new DOMSource(nl.item(n)), new StreamResult(writer));
                                        }
                                        // transformer.transform(new
                                        // DOMSource(doc.getElementsByTagName("body").item(0)), new
                                        // StreamResult(writer));
                                        ann.appendCommentText(writer.getBuffer().toString());
                                        foundRichText = true;
                                        // log.info("Transformed output: " +output);
                                    } catch (TransformerException e) {
                                        e.printStackTrace();
                                    }
                                } catch (Exception e) {
                                    log.warn("Exception: Using plain text for annotation");
                                    e.printStackTrace();
                                    ann.appendCommentText(annot.getContents());
                                }
                                break;
                            }
                        }
                        if (!foundRichText) {
                            log.warn("Didn't find rich Text for " + annot.getContents());
                            ann.appendCommentText(annot.getContents());
                        }
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

    private InputStream createPdfFromDocxJODC(InputStream docInputStream) throws Exception {

        // Create an office manager using the default configuration.
        // The default port is 2002. Note that when an office manager
        // is installed, it will be the one used by default when
        // a converter is created.
        final LocalOfficeManager officeManager = LocalOfficeManager.install();

        try {

            // Start an office process and connect to the started instance (on port 2002).
            officeManager.start();
            File outputFile = File.createTempFile("JODCtemp", ".pdf");

            JodConverter
                    .convert(docInputStream)
                    .as(DefaultDocumentFormatRegistry.DOCX)
                    .to(outputFile)
                    .as(DefaultDocumentFormatRegistry.PDF)
                    .execute();

            return new FileInputStream(outputFile);
        } finally {
            log.debug("Done with JODC");
            // Stop the office process
            OfficeUtils.stopQuietly(officeManager);
        }
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
                    StringBuilder title = new StringBuilder();
                    Map<String, String> commentRelationshipMap = new HashMap<String, String>();

                    //Not used?
                    StringBuilder convertedText = new StringBuilder();

                    try (PipedOutputStream annOutputStream = new PipedOutputStream(annInputStream)) {
                        // process for creating ann started
                        WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage
                                .load(docInputStream);
                        MainDocumentPart mainDocumentPart = wordMLPackage
                                .getMainDocumentPart();

                        // Todo - handle null cPart
                        CommentsPart cPart = mainDocumentPart.getCommentsPart();
                        if (cPart != null) {
                            RelationshipsPart rPart = cPart.getRelationshipsPart(false);
                            if (rPart != null) {
                                Relationships rels = rPart.getRelationships();
                                if (rels != null) {
                                    List<Relationship> relList = rels.getRelationship();
                                    relList.forEach((Relationship r) -> {
                                        commentRelationshipMap.put(r.getId(), r.getTarget());
                                        log.debug("RTarg: " + r.getTarget());
                                        log.debug("RId: " + r.getId());
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
                        if (cPart != null) {
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
                            boolean firstPara=true;
                            for (Object o : c.getContent()) {
                                // System.out.println("Found o = " + o.getClass().getCanonicalName());
                                if (o instanceof P) {
                                    P para = (P) o;
                                    boolean firstRun=true;
                                    for (Object po : para.getContent()) {
                                        log.debug("Found po = " + po.getClass().getCanonicalName());
                                        if (po instanceof JAXBElement) {
                                            JAXBElement<?> jb = (JAXBElement<?>) po;

                                            log.debug(jb.getName().toString());
                                            log.debug(jb.getDeclaredType().getSimpleName());
                                            log.debug(jb.getValue().getClass().getCanonicalName());
                                            if (jb.getValue() instanceof Hyperlink) {

                                                log.debug("Found hyperlink");
                                                Hyperlink link = (Hyperlink) jb.getValue();
                                                log.debug("Id: " + link.getId());
                                                log.debug("TgtFr: " + link.getTgtFrame());
                                                log.debug("TTip: " + link.getTooltip());
                                                log.debug("Str: " + link.toString());
                                                log.debug("Anchor: " + link.getAnchor());
                                                log.debug("Loc: " + link.getDocLocation());
                                                for (Object lo : link.getContent()) {
                                                    if (lo instanceof R) {
                                                        log.debug("HL text: " + getStyedTextFromRun((R) lo));
                                                        if(!firstPara && firstRun) {
                                                            log.debug("adding a break");
                                                            commentText.get(id).append("<br>");
                                                            annotationMap.get(id).appendCommentText("<br>");
                                                            firstRun=false;
                                                        }
                                                        String htmlLink = "<a href=\"" + commentRelationshipMap.get(link.getId()) + "\">" + getStyedTextFromRun((R) lo) + "</a>";
                                                        annotationMap.get(id).appendCommentText(htmlLink);
                                                        commentText.get(id).append(htmlLink);
                                                    }
                                                }
                                            }
                                        }

                                        if (po instanceof R) {
                                            if(!firstPara && firstRun) {
                                                log.debug("adding a break");
                                                commentText.get(id).append("<br>");
                                                annotationMap.get(id).appendCommentText("<br>");
                                                firstRun=false;
                                            }
                                            String styledText = getStyedTextFromRun((R) po);
                                            log.debug("Adding " + styledText);
                                            annotationMap.get(id).appendCommentText(styledText);
                                            commentText.get(id).append(styledText);

                                        }
                                    }
                                    firstPara=false;

                                    //System.out.println("Comment # " + id + ": " +
                                    //commentText.get(id).toString());
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
                        int paraCount = 1;
                        for (Object o : mainDocumentPart.getContent()) {
                            if (o instanceof JAXBElement && ((JAXBElement<?>) o).getDeclaredType().equals(Tbl.class)) {
                                /* Find Tr rows, and Tc which then contains P and same substructure */
                                Object q = ((JAXBElement<?>) o).getValue();
                                for (Object to : ((Tbl) q).getContent()) {
                                    if (to instanceof Tr) {
                                        for (Object tro : ((Tr) to).getContent()) {
                                            if (tro instanceof JAXBElement && ((JAXBElement<?>) tro).getDeclaredType().equals(Tc.class)) {
                                                for (Object tco : ((Tc) ((JAXBElement<?>) tro).getValue()).getContent()) {
                                                    if (tco instanceof P) {
                                                        //Won't look for title in tables
                                                        processParagraph(((P) tco), firstPara, false, annotationMap, commentStarted, convertedText, inComment, preCommentText, postCommentText, commentedText, title, titleId);
                                                        firstPara = false;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (o instanceof P) {
                                P para = (P) o;
                                //Look for para in first five paragraphs only 
                                processParagraph(para, firstPara, (paraCount < 5), annotationMap, commentStarted, convertedText, inComment, preCommentText, postCommentText, commentedText, title, titleId);
                                firstPara = false;
                                paraCount++;
                            }
                            
                        }
                        JsonArrayBuilder jab = Json.createArrayBuilder();
                        final String theTitle = title.toString();

                        // ToDo - could write the annotations/title to the Document now, but we're in a
                        // thread with no context
                        if(annotationMap.get(titleId).getDocTitle()== null) {
                            //We didn't find a title, so don't add a placeholder ann
                            annotationMap.remove(titleId);
                        }
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

                        /*
                         * This is getting all text String textNodesXPath = "//w:t"; List<Object>
                         * textNodes = mainDocumentPart .getJAXBNodesViaXPath(textNodesXPath, true); for
                         * (Object obj : textNodes) { Text text = (Text) ((JAXBElement<?>)
                         * obj).getValue(); String textValue = text.getValue(); //
                         * System.out.println("Whole Text: " + textValue); }
                         */

                    } catch (Exception e) {
                        log.error("Error creating ann: " + e.getMessage());
                        e.printStackTrace();
                        throw new RuntimeException("Error creating ann: " + e.getMessage());
                    }
                }

                private void processParagraph(P para, boolean firstPara, boolean lookForTitle, Map<BigInteger, Annotation> annotationMap, Map<BigInteger, Boolean> commentStarted, StringBuilder convertedText, Map<BigInteger, Boolean> inComment, Map<BigInteger, StringFragment> preCommentText, Map<BigInteger, StringFragment> postCommentText, Map<BigInteger, StringBuilder> commentedText, StringBuilder title, BigInteger titleId) {
                    //ToDo - this was disabled. Now, ann.addText removed \n so this has no effect there. It would be added to the pre/in/post entries but that would make them inconsistent with the overall char count.
                    //Commenting it out until there's evidence that adding \n\n is needed to be consistent with Hypothesis.
                    /*
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
                    }
                    */
                    boolean bold = false;
                    boolean largeFont=false;
                    if (para.getPPr() != null && para.getPPr().getRPr() != null) {
                        BooleanDefaultTrue bdt = para.getPPr().getRPr().getB();
                        bold = bdt == null ? false : bdt.isVal();
                        if(para.getPPr().getRPr().getSz()!=null) {
                            largeFont = para.getPPr().getRPr().getSz().getVal().intValue() >= 32;
                            log.debug("Para Hps: " + para.getPPr().getRPr().getSz().getVal());
                        }
                        log.debug("Looking?: " + lookForTitle);
                        log.debug("Para is bold?: " + para.getPPr().getRPr().getB());
                        
                    }
                    boolean multirun = false;
                    int rCounter = 0;
                    if (lookForTitle && title.length() == 0) {
                        for (Object po : para.getContent()) {

                            if (po instanceof R) {

                                rCounter++;
                            }
                            if (rCounter == 2) {
                                multirun = true;
                                break;
                            }
                        }
                    }
                    for (Object po : para.getContent()) {
                        // System.out.println("PO: " + po.getClass().getCanonicalName());
                        if (po instanceof CommentRangeStart) {
                            // ToDo = get id and handle overlapping comments
                            BigInteger id = ((CommentRangeStart) po).getId();
                            annotationMap.get(id).startAnchor();
                            commentStarted.put(id, true);
                            inComment.put(id, true);
                            log.debug("Comment Start: " + id);
                            // System.out.println("PlainText: " + String.join("", nonCommentTexts));
                            // nonCommentTexts.clear();
                        }
                        if (po instanceof CommentRangeEnd) {
                            BigInteger id = ((CommentRangeEnd) po).getId();
                            annotationMap.get(id).endAnchor();
                            inComment.put(id, false);
                            log.debug("Comment End: " + id);
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
                                        log.debug("Found text: " + text);
                                        PPr ppr = para.getPPr();
                                        if (ppr != null) {
                                            PStyle style = ppr.getPStyle();
                                            
                                            if (annotationMap.get(titleId).getDocTitle() == null) {
                                                if (log.isDebugEnabled() && ((R) po).getRPr() != null) {
                                                    if (((R) po).getRPr().getB() != null) {
                                                        log.debug("Bold?: " + ((R) po).getRPr().getB().isVal());
                                                    }
                                                    if (((R) po).getRPr().getSz() != null) {
                                                        log.debug("Size: " + ((R) po).getRPr().getSz().getVal());
                                                    }
                                                }
                                                if (style != null) {
                                                    log.debug("ParaStyle: " + style.getClass().getCanonicalName() + " : " + style.getVal());
                                                }
                                            }
                                            /*
                                             * Heuristics to find title:
                                             *  First paragraph
                                             *  * styled as Heading 1
                                             *  * is bold or in a font >= 16 pt and either the text in the run in >20 chars or there are multiple runs
                                             *  
                                             *  Assumes the title is always in one paragraph (not split)
                                             *  
                                             */
                                            if (lookForTitle && ((style != null && (style.getVal().equals("Heading1"))) || ((bold||largeFont) && ((text.length() > 20) || multirun))) && annotationMap.get(titleId).getDocTitle() == null) {
                                                title.append(text);
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
                                                log.debug("Adding '" + text + "' to preText for" + id);
                                            } else {
                                                if (inside) {
                                                    commentedText.get(id).append(text);
                                                    log.debug("Adding '" + text + "' to commentedText for" + id);
                                                } else {
                                                    postCommentText.get(id).addString(text);
                                                    log.debug("Adding '" + text + "' to postText for" + id);
                                                }
                                            }
                                        });

                                        /*
                                         * if (inComment) { commentTexts.add(((Text) jb.getValue()).getValue()); } else
                                         * { nonCommentTexts.add(((Text) jb.getValue()).getValue()); }
                                         */
                                    }
                                }
                            }
                        }
                    }
                    //At the end of a paragraph, if we don't yet have a title, see if there is one
                    if (annotationMap.get(titleId).getDocTitle() == null && title.length() != 0) {
                        //If so, finish the placeholder annotation for it and add the title to all the other annotations 
                        annotationMap.get(titleId).endAnchor();
                        inComment.put(titleId, false);
                        String dTitle = title.toString();
                        System.out.println("Adding title to anns: " + dTitle);
                        annotationMap.values().forEach(ann -> ann.setDocTitle(dTitle));
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
                        log.debug("Bold?: " + (style.getB() instanceof BooleanDefaultTrue));
                        log.debug("Italic?: " + (style.getI() instanceof BooleanDefaultTrue));
                    }
                    for (Object ro : run.getContent()) {
                        log.debug("Found ro = " + ro.getClass().getCanonicalName());
                        if (ro instanceof Br) {
                            val = val + "\n";
                        }
                        if (ro instanceof JAXBElement) {
                            JAXBElement<?> jb = (JAXBElement<?>) ro;

                            log.debug(jb.getName().toString());
                            // System.out.println(jb.getDeclaredType());
                            // System.out.println(jb.getValue().getClass().getCanonicalName());
                            // System.out.println(jb.getValue());
                            if (jb.getValue() instanceof Text) {
                                Text t = (Text) jb.getValue();

                                log.debug(t.getSpace());
                                log.debug(t.getValue());
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
            while (annInputStream.available() <= 0 && i < 25) {
                System.out.println("Waiting: " + i);
                Thread.sleep(2000);
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
        if (d == null) {
            try {
                d = retrieveDocFor(id, apikey);
            } catch (Exception e) {
                log.debug("Couldn't retrieve file id: " + id + ": " + e.getMessage());
            }
        }
        if (d != null && (d.isConverted() == true || force)) {
            try {
                JsonArray response = dataverseService.getAPIJsonResponse(dataverseService.getAnnPath(id), apikey).asJsonArray();
                log.debug(response.toString());
                JsonArrayBuilder jab = Json.createArrayBuilder(response);
                //If the doc title is set (in any/all annotations), we know the title was found and the first annotation is a placeholder that needs to be handled separately
                if (response.get(0).asJsonObject().get("document").asJsonObject().containsKey("title")) {
                    d.setTitleAnnotation(response.get(0).toString());
                    jab.remove(0);
                }
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
