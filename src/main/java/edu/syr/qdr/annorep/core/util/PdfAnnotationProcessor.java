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

public class PdfAnnotationProcessor {

    Map<Integer, Annotation> annotationMap = null;
    Map<Integer, String> anchorMap = null;
    StringFragment fragment = null;
    int maxCommentLength = 0;

    public PdfAnnotationProcessor(Map<Integer, Annotation> annMap, Map<Integer, String> anchorMap) {
        super();
        this.annotationMap = annMap;
        this.anchorMap = anchorMap;
        for (String anchor : anchorMap.values()) {
            maxCommentLength = Math.max(maxCommentLength, anchor.length());
        }
        fragment = new StringFragment(maxCommentLength * 2, true);
    }

    public void processDocument(PDDocument document) throws IOException {
        PDFTextStripper textStripper = new PDFTextStripper();
        textStripper.setAddMoreFormatting(true);
        PipedWriter pWriter = new PipedWriter();
        try {
            System.out.println("Examining pdf");
            try (PipedReader pReader = new PipedReader(pWriter)) {
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
                while (!pReader.ready()) {
                    Thread.sleep(10);
                }
                char[] buf = new char[maxCommentLength];
                int charsRead = -1;
                Map<Integer, Integer> endpts = new HashMap<Integer, Integer>();
                StringBuilder titleB = new StringBuilder();
                String title = null;
                String latestFrag = null;
                int testLen = 0;
                while ((charsRead = pReader.read(buf)) > -1) {
                    System.out.println("CharsRead: " + charsRead);
                    String text = String.valueOf(buf, 0, charsRead);
                    if (title == null) {
                        if (text.contains("\r")) {
                            int pos = text.indexOf('\r');
                            if (pos == 0) {
                                do {
                                    text = text.substring(1);
                                } while (text.indexOf('\r') == 0 || text.indexOf('\n') == 0);
                                pos = text.indexOf('\r');
                            }
                            if (pos > 0) {
                                titleB.append(text.substring(0, pos));

                                title = titleB.toString();

                                for (Annotation ann : annotationMap.values()) {
                                    ann.setDocTitle(title);
                                }

                            } else {
                                titleB.append(text);
                            }
                        } else {
                            titleB.append(text);
                        }
                    }
                    System.out.println("TitleB: " + titleB.length() + " : " + titleB.toString());
                    fragment.addString(String.valueOf(buf, 0, charsRead));

                    latestFrag = fragment.getString();
                    if (charsRead < maxCommentLength) {
                        latestFrag = latestFrag.substring(maxCommentLength - charsRead);
                    }
                    System.out.println("Frag is " + latestFrag);
                    for (Entry<Integer, String> entry : anchorMap.entrySet()) {
                        int start = -1;
                        String anchor = entry.getValue();
                        int id = entry.getKey();
                        Annotation ann = annotationMap.get(id);
                        if (!endpts.containsKey(id)) {
                            if ((start = latestFrag.indexOf(anchor)) > -1 && start < maxCommentLength) {
                                System.out.println("Start is " + start);
                                if (start > 0) {
                                    ann.addText(latestFrag.substring(0, start));
                                }
                                System.out.println("Start is at pos: " + (testLen + start));
                                ann.startAnchor();
                                if ((start + anchor.length()) <= maxCommentLength) {
                                    System.out.println("Adding to anchor: " + latestFrag.substring(start, start + anchor.length()));
                                    ann.addText(latestFrag.substring(start, start + anchor.length()));
                                    ann.endAnchor();
                                    if ((start + anchor.length()) < maxCommentLength) {
                                        ann.addText(latestFrag.substring(start + anchor.length(), maxCommentLength));
                                    }
                                } else {
                                    System.out.println("Partial anchor: " + latestFrag.substring(start, maxCommentLength));

                                    ann.addText(latestFrag.substring(start, maxCommentLength));
                                    endpts.put(id, start + anchor.length() - maxCommentLength);
                                }
                            } else {
                                ann.addText(latestFrag.substring(0, maxCommentLength));
                            }
                        } else {
                            int endPt = endpts.get(id);
                            if (endPt > 0) {
                                System.out.println("Finishing anchor: " + latestFrag.substring(0, endPt));
                                ann.addText(latestFrag.substring(0, endPt));
                                ann.endAnchor();
                                ann.addText(latestFrag.substring(endPt, maxCommentLength));
                                endpts.put(id, -1);
                            }
                        }
                    }
                    testLen += charsRead;
                    // System.out.println(" 15: " + ((int) buf[15]) + " 16: " + ((int) buf[16]));
                    // System.out.println("Strlength: " + text.length());
                    // System.out.println("Adding :x" + text + "x");
                }
                String lastFrag = latestFrag.substring(maxCommentLength);
                System.out.println("Frag is " + lastFrag);
                for (Entry<Integer, String> entry : anchorMap.entrySet()) {
                    int start = -1;
                    String anchor = entry.getValue();
                    int id = entry.getKey();
                    Annotation ann = annotationMap.get(id);
                    if (!endpts.containsKey(id)) {
                        ann.addText(lastFrag);
                    } else {
                        int endPt = endpts.get(id);
                        if (endPt > 0) {
                            System.out.println("Finishing anchor: " + lastFrag.substring(0, endPt));
                            ann.addText(lastFrag.substring(0, endPt));
                            ann.endAnchor();
                            ann.addText(lastFrag.substring(endPt, maxCommentLength));
                        }
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

}
