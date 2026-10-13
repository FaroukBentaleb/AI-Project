package com.devassisthub.commit.rag;

import org.springframework.stereotype.Component;

@Component
public class VectorStoreService {
    public static final String COLLECTION_NAME = "commit_embeddings";
    public static final int VECTOR_SIZE = 384;
    public static final String DISTANCE = "Cosine";

    // TODO: Upsert embedding vectors into commit_embeddings.
    // TODO: Search the top-k similar commits using cosine distance.
}
