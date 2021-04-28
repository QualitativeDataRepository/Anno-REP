package edu.syr.qdr.annorep.core.util;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;

public class Annotation {

    private TextQuoteSelector tqs = new TextQuoteSelector();
    private TextPositionSelector tps = new TextPositionSelector();
    private StringBuilder commentText = new StringBuilder();
    private boolean inComment = false;
    private boolean commentStarted = false;
    private String docUri;
    private String docTitle;

    public Annotation() {
    }

    public TextQuoteSelector getTextQuoteSelector() {
        return tqs;
    }

    public void setTextQuoteSelector(TextQuoteSelector tqs) {
        this.tqs = tqs;
    }

    public TextPositionSelector getTextPositionSelector() {
        return tps;
    }

    public void setTextPositionSelector(TextPositionSelector tps) {
        this.tps = tps;
    }

    public String getCommentText() {
        return commentText.toString();
    }

    public void addText(String newText) {
        tqs.addText(newText);
        tps.addText(newText);
    }

    public void appendCommentText(String commentText) {
        this.commentText.append(commentText);
    }

    public boolean isInComment() {
        return inComment;
    }

    public void endAnchor() {
        this.inComment = false;
        tqs.setInComment(inComment);
        tps.setInComment(inComment);
    }

    public boolean isCommentStarted() {
        return commentStarted;
    }

    public void startAnchor() {
        
        this.commentStarted = true;
        this.inComment = true;
        tqs.setInComment(inComment);
        tps.setInComment(inComment);
        tqs.setCommentStarted(commentStarted);
        tps.setCommentStarted(commentStarted);
    }

    public JsonObject getJson() {
        JsonObjectBuilder annBuilder = Json.createObjectBuilder();
        JsonArrayBuilder selectors = Json.createArrayBuilder();
        selectors.add(tqs.getJson());
        selectors.add(tps.getJson());
        
        annBuilder.add("target", Json.createArrayBuilder().add(Json.createObjectBuilder().add("source", getDocUri()).add("selector",  selectors)));
        annBuilder.add("text", getCommentText());
        annBuilder.add("uri", getDocUri());
        JsonObjectBuilder doc = Json.createObjectBuilder();
        if(getDocTitle()!=null) {
            doc.add("title", Json.createArrayBuilder().add(getDocTitle()));
        }
        annBuilder.add("document", doc);
        return annBuilder.build();
    }

    public String getDocUri() {
        return docUri;
    }

    public void setDocUri(String docUri) {
        this.docUri = docUri;
    }

    public String getDocTitle() {
        return docTitle;
    }

    public void setDocTitle(String docTitle) {
        this.docTitle = docTitle;
    }

}
