package com.devassisthub.sql.config;

import com.devassisthub.sql.rag.CollectionBootstrap;
import com.devassisthub.sql.rag.EmbeddingClient;
import com.devassisthub.sql.rag.EmbeddingProperties;
import com.devassisthub.sql.rag.QdrantClient;
import com.devassisthub.sql.rag.QdrantHealthIndicator;
import com.devassisthub.sql.rag.QdrantProperties;
import com.devassisthub.sql.rag.QueryIndexer;
import com.devassisthub.sql.rag.Reconciler;
import com.devassisthub.sql.repository.SavedQueryRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/** Qdrant REST client (1 s connect/read timeout), collection bootstrap with retry, and the {@code qdrant} indicator. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({QdrantProperties.class, EmbeddingProperties.class})
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(QdrantProperties props) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.timeout());
        RestClient rest = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        return new QdrantClient(rest, props.collection());
    }

    @Bean
    public CollectionBootstrap collectionBootstrap(QdrantClient client,
                                                   @Value("${hf.embedding-dimension}") int dimension) {
        return new CollectionBootstrap(client, dimension);
    }

    @Bean("qdrant")
    public QdrantHealthIndicator qdrantHealthIndicator(QdrantClient client, CollectionBootstrap bootstrap) {
        return new QdrantHealthIndicator(client, bootstrap);
    }

    /** Hugging Face embedding client (Story 5.2): {@code hf.embedding-timeout} connect/read, no retries. */
    @Bean
    public EmbeddingClient embeddingClient(EmbeddingProperties props) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(props.embeddingTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.embeddingTimeout());
        RestClient rest = RestClient.builder().baseUrl(props.embeddingBaseUrl()).requestFactory(factory).build();
        return new EmbeddingClient(rest, props);
    }

    /** Story 5.4: Reconciliation, triggered by {@code CollectionReadyEvent} and {@code POST /api/sql/admin/reindex}. */
    @Bean(destroyMethod = "shutdown")
    public Reconciler reconciler(QdrantClient client, CollectionBootstrap bootstrap, QueryIndexer indexer,
                                 SavedQueryRepository repository, PlatformTransactionManager txManager,
                                 @Value("${sql.reconcile.batch-size:100}") int batchSize) {
        return new Reconciler(client, bootstrap, indexer, repository, new TransactionTemplate(txManager), batchSize);
    }
}
