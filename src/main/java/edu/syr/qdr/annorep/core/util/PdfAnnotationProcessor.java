package edu.syr.qdr.annorep.core.util;

import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import edu.syr.qdr.annorep.core.controller.DocumentsController;
import lombok.extern.slf4j.Slf4j;

/**
 * Since PDF documents are position-oriented and don't have a separate logical
 * element sequence, the only way (I know) to determine where anchors are in a
 * document is to stream through the text in display order and find them. This
 * class does that and then uses the existing Annotation/Selector classes
 * developed for working with docx files to generate Hypothesis-compatible
 * annotations.
 */
@Slf4j
public class PdfAnnotationProcessor {

    Map<Integer, Annotation> annotationMap = null;
    Map<Integer, String> anchorMap = null;
    StringFragment fragment = null;
    int maxAnchorLength = 0;

    /**
     * In addition to storing hte annotation and anchor maps, the constructor finds
     * the longest anchor and creates a streaming StringFragment used in the
     * processDocument method to allow looking ahead at the incoming text
     * 
     * @param annMap
     *            - the map of draft Annotation entries
     * @param anchorMap
     *            - a map with the corresponding anchor text strings for those
     *            annotations
     */
    public PdfAnnotationProcessor(Map<Integer, Annotation> annMap, Map<Integer, String> anchorMap) {
        super();
        this.annotationMap = annMap;
        this.anchorMap = anchorMap;
        for (String anchor : anchorMap.values()) {
            maxAnchorLength = Math.max(maxAnchorLength, anchor.length());
        }
        fragment = new StringFragment(maxAnchorLength * 2, true);
    }

    /**
     * This method works through the pdfdocument text stream. It includes logic to
     * look ahead and find anchors in the text and to then generate corresponding
     * annotation start/end events that allow use of the existing Annotation class
     * to populate TextQuote and TextPosition selectors
     * 
     * Once it completes (with no exception), the Annotations in the map should be
     * populated and ready to generate json representations
     * 
     * @param document
     * @throws IOException
     */
    public void processDocument(PDDocument document) throws IOException {
        // Create a text stripper which can provide stream the document's text to a
        // Writer
        PDFTextStripper textStripper = new PDFTextStripper();
        // This adds \r\n between paragraphs which is ~ how they are represented in the
        // docx processing
        textStripper.setAddMoreFormatting(true);
        PipedWriter pWriter = new PipedWriter();
        try {
            log.debug("Processing PDF document");
            // Setup a Reader that will make the text streaming into the Writer available
            // for analysis
            try (PipedReader pReader = new PipedReader(pWriter)) {
                // Start a thread to run the text extraction from the PDF document
                new Thread(new Runnable() {
                    public void run() {

                        try {
                            textStripper.writeText(document, pWriter);
                            pWriter.close();
                        } catch (IOException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                }).start();
                // Wait until the thread has created some initial content
                while (!pReader.ready()) {
                    Thread.sleep(10);
                }
                // Read document, maxAchorLength chars at a time
                char[] buf = new char[maxAnchorLength];
                int charsRead = -1;
                //endpt(id) -->
                //  null - anchor not yet found
                //  >0 - number of chars remaining in anchor (to be processed after the next read)
                //  -1 - anchor found/already processed
                Map<Integer, Integer> endpts = new HashMap<Integer, Integer>();
                StringBuilder titleB = new StringBuilder();
                String title = null;
                String latestFrag = null;
                int testLen = 0;
                boolean initialized = false;
                
                while ((charsRead = pReader.read(buf)) > -1) {
                    log.trace("CharsRead: " + charsRead);
                    String text = String.valueOf(buf, 0, charsRead);
                    // Title extraction - look for the first text - after any initial \r\n, up to a
                    // following \r
                    if (titleB.length() != 0) {
                        if (text.contains("\r")) {
                            int pos = text.indexOf('\r');
                            // Strip initial \r\n if the exist
                            if (pos == 0) {
                                do {
                                    text = text.substring(1);
                                } while (text.indexOf('\r') == 0 || text.indexOf('\n') == 0);
                                pos = text.indexOf('\r');
                            }
                            if (pos > 0) {
                                // We have the whole title, so read it and set the title
                                titleB.append(text.substring(0, pos));
                                title = titleB.toString();
                                log.debug("Title found: " + title);
                                // And set the title in every annotation
                                for (Annotation ann : annotationMap.values()) {
                                    ann.setDocTitle(title);
                                }
                            } else {
                                // We have nothing or part of the title, so add it to the buffer.
                                titleB.append(text);
                            }
                        } else {
                            // Whole string is text, add it to the buffer
                            titleB.append(text);
                        }
                    }
                    log.trace("Partial title: " + titleB.length() + " : " + titleB.toString());
                    // Annotation anchor discovery. Start by adding the newly read string to the
                    // fragment we're using. (Note - we can't use the text variable from above as it
                    // has \r\n chars removed and we want those.
                    fragment.addString(String.valueOf(buf, 0, charsRead));

                    // General algorithm: Look in current fragment for each anchor. Using
                    // 2*maxAnchorLegnth chars as a buffer size and only removing maxAnhorLength
                    // chars each time assures that we will always find each anchor contained in the
                    // fragment in some iteration, i.e. there will never be an anchor that can't be
                    // found because it is too long to ever fit in the fragment.
                    latestFrag = fragment.getString();
                    if (!initialized && latestFrag.length() < (2 * maxAnchorLength)) {
                        // Just read characters until or fragment is full
                        break;
                    }
                    initialized = true;
                    if (charsRead < maxAnchorLength) {
                        // Since read blocks, this should only occur at the end of the doc.
                        latestFrag = latestFrag.substring(maxAnchorLength - charsRead);
                    }
                    log.debug("Frag is " + latestFrag);
                    for (Entry<Integer, String> entry : anchorMap.entrySet()) {

                        String anchor = entry.getValue();
                        int id = entry.getKey();
                        log.trace("Annotation: " + id);
                        Annotation ann = annotationMap.get(id);
                        Integer endpt = process(anchor, ann, latestFrag, endpts.get(id), maxAnchorLength, testLen);
                        if (endpt == null) {
                            // The anchor isn't found/doesn't start in the first half of the buffer, so just
                            // add the pre-anchor-text to the annotation
                            ann.addText(latestFrag.substring(0, maxAnchorLength));
                        } else {
                            //We did find the anchor - mark whether it's complete or partly processed
                            endpts.put(id, endpt);
                        }

                    }
                    // Increment the number of chars read (used only in logging)
                    testLen += charsRead;
                }
                // Done reading from doc, so process the remaining chars in the buffer
                String lastFrag = latestFrag.substring(maxAnchorLength);
                log.debug("Final fragment is " + lastFrag);
                for (Entry<Integer, String> entry : anchorMap.entrySet()) {
                    String anchor = entry.getValue();
                    int id = entry.getKey();
                    log.trace("Annotation: " + id);
                    Annotation ann = annotationMap.get(id);
                    Integer endpt = process(anchor, ann, latestFrag, endpts.get(id), latestFrag.length(), testLen);
                    if (endpt == null) {
                        log.warn("Warning: Didn't find anchor: " + anchor);
                    }
                }

            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Integer process(String anchor, Annotation ann, String latestFrag, Integer endpt, int maxProcessingLength, int testLen) {
        int start = -1;
        // Check to see if we've already found this anchor
        if (endpt == null) {
            // Not yet found, so we look for the anchor to see if it starts in the first
            // half of the current fragment
            if ((start = latestFrag.indexOf(anchor)) > -1 && start < maxProcessingLength) {
                // We've found the anchor (or another place in the doc with the exact same
                // text!)
                log.debug("Anchor found at relative position " + start);
                if (start > 0) {
                    // Add any text before the anchor start to the annotation
                    ann.addText(latestFrag.substring(0, start));
                }
                // Then send the anchor start event
                log.debug("Anchor found at relative position  " + (testLen + start));
                ann.startAnchor();
                // And either add the whole anchor, or if it goes beyond the mid-point in the
                // buffer, just the first part
                if ((start + anchor.length()) <= maxProcessingLength) {
                    log.debug("Adding anchor: " + latestFrag.substring(start, start + anchor.length()));
                    ann.addText(latestFrag.substring(start, start + anchor.length()));
                    // If we have the whole anchor, send the end event and add any remaining text in
                    // the first half of the buffer to the annotation
                    ann.endAnchor();
                    if ((start + anchor.length()) < maxProcessingLength) {
                        ann.addText(latestFrag.substring(start + anchor.length(), maxProcessingLength));
                    }
                    // Record that the whole anchor has been found/processed (so we don't keep
                    // looking for it)
                    return -1;
                } else {
                    log.debug("Adding partial anchor: " + latestFrag.substring(start, maxProcessingLength));
                    // Add the initial part and mark the end point (which we'll process the next
                    // time through the read loop)
                    ann.addText(latestFrag.substring(start, maxProcessingLength));
                    return start + anchor.length() - maxProcessingLength;
                }
            } else {
                return null;
            }
        } else {
            // Either we've got a partial anchor or have found the whole thing
            if (endpt > 0) {
                log.debug("Finishing anchor: " + latestFrag.substring(0, endpt));
                // Add the remaining chars to the anchor and the signal it's end
                ann.addText(latestFrag.substring(0, endpt));
                ann.endAnchor();
                // And then add any remaining text from the first half of the buffer
                ann.addText(latestFrag.substring(endpt, maxProcessingLength));
            } else {
                // The anchor is already processed, just add the post-anchor text
                ann.addText(latestFrag.substring(0, maxProcessingLength));
            }
            return -1;
        }
    }

}
