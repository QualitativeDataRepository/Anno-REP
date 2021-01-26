package edu.syr.qdr.annorep.core;

public class User {
    String apiKey = null;

    public User(String apiKey) {
        this.apiKey = apiKey;
    }

    String getApiKey() {
        return apiKey;
    }
}
