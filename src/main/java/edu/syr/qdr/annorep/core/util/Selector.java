package edu.syr.qdr.annorep.core.util;

import javax.json.JsonObject;

public abstract class Selector {

    protected boolean inComment = false;
    protected boolean commentStarted = false;
    
    public Selector() {
    }

    abstract public void addText(String text);
    
    abstract public JsonObject getJson();

    public void setInComment(boolean inComment) {
        this.inComment = inComment;
    }

    public void setCommentStarted(boolean commentStarted) {
        this.commentStarted = commentStarted;
    }

}
