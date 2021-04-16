package edu.syr.qdr.annorep.core.util;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

public class TextPositionSelector extends Selector {

    // Number of characters Hypothesis expects in the pre/post comment anchors for a
    // comment
    protected static final int ANCHOR_SIZE = 32;

    private long start;
    private long end;

    public TextPositionSelector() {
    }

    public JsonObject getJson() {
        JsonObjectBuilder job = Json.createObjectBuilder();
        job.add("type", "TextPositionSelector")
                .add("start", start)
                .add("end", end);
        return job.build();
    }

    public long getStart() {
        return start;
    }

    public void setStart(long start) {
        this.start = start;
    }

    public long getEnd() {
        return end;
    }

    public void setEnd(long end) {
        this.end = end;
    }

    @Override
    public void addText(String text) {
        if (!commentStarted) {
            start += text.length();
            end += text.length();
        } else {
            if (inComment) {
                end += text.length();
            } else {
                // Done
            }
        }
    }
}
