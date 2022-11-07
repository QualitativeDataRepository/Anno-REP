package edu.syr.qdr.annorep.core.util;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

public class TextQuoteSelector extends Selector {

    // Number of characters Hypothesis expects in the pre/post comment anchors for a
    // comment
    protected static final int ANCHOR_SIZE = 32;

    private StringFragment prefix = new StringFragment(ANCHOR_SIZE, true);
    private StringFragment suffix = new StringFragment(ANCHOR_SIZE, false);
    private StringBuilder exact = new StringBuilder();

    public TextQuoteSelector() {
    }

    public JsonObject getJson() {
        JsonObjectBuilder job = Json.createObjectBuilder();
        job.add("type", "TextQuoteSelector")
                .add("exact", getExact()).add("prefix", prefix.getString()).add("suffix", suffix.getString());
        return job.build();
    }

    public String getExact() {
        return exact.toString();
    }

    public void appendExact(String exact) {
        this.exact.append(exact);
    }

    public StringFragment getPrefix() {
        return prefix;
    }

    public void setPrefix(StringFragment prefix) {
        this.prefix = prefix;
    }

    public StringFragment getSuffix() {
        return suffix;
    }

    public void setSuffix(StringFragment suffix) {
        this.suffix = suffix;
    }

    @Override
    public void addText(String text) {
        if (!commentStarted) {
            prefix.addString(text);
        } else {
            if (inComment) {
                exact.append(text);
            } else {
                suffix.addString(text);
            }
        }
    }
}
