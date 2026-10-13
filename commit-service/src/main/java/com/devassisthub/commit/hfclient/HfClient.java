package com.devassisthub.commit.hfclient;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HfClient {
    private final RestClient restClient;

    public HfClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    // TODO: Add timeout, retry, quota/key error mapping, and validated response handling.
}
