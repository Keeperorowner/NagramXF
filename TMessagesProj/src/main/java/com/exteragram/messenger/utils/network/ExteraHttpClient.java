package com.exteragram.messenger.utils.network;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

public final class ExteraHttpClient {

    public static final ExteraHttpClient INSTANCE = new ExteraHttpClient();

    private final OkHttpClient client;

    private ExteraHttpClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public OkHttpClient getClient() {
        return client;
    }
}
