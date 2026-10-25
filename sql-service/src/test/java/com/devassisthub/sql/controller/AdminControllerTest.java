package com.devassisthub.sql.controller;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devassisthub.sql.exception.GlobalExceptionHandler;
import com.devassisthub.sql.rag.CollectionBootstrap;
import com.devassisthub.sql.rag.CollectionInfo;
import com.devassisthub.sql.rag.QdrantClient;
import com.devassisthub.sql.rag.Reconciler;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

/** Story 5.4: {@code POST /api/sql/admin/reindex} answers 202 or 503 in the standard envelope. */
@WebMvcTest(controllers = AdminController.class)
@Import(GlobalExceptionHandler.class)
class AdminControllerTest {

    @Autowired MockMvc mvc;
    @MockBean CollectionBootstrap bootstrap;
    @MockBean QdrantClient qdrant;
    @MockBean Reconciler reconciler;

    @Test
    void qdrantUpQueuesReconciliationAnd202() throws Exception {
        when(bootstrap.tryBootstrap()).thenReturn(true);
        when(qdrant.getCollection()).thenReturn(Optional.of(new CollectionInfo("sql_embeddings", 384, "Cosine", 0)));

        mvc.perform(post("/api/sql/admin/reindex"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"));
        verify(reconciler).requestAsync();
    }

    @Test
    void bootstrapImpossibleIs503() throws Exception {
        when(bootstrap.tryBootstrap()).thenReturn(false);

        mvc.perform(post("/api/sql/admin/reindex"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(AdminController.RAG_INDEX_UNAVAILABLE))
                .andExpect(jsonPath("$.message").value(startsWith("RAG index unavailable")))
                .andExpect(jsonPath("$.field").isEmpty());
        verify(reconciler, never()).requestAsync();
    }

    @Test
    void qdrantGoneAfterBootstrapIs503() throws Exception {
        when(bootstrap.tryBootstrap()).thenReturn(true);
        when(qdrant.getCollection()).thenThrow(new ResourceAccessException("Connection refused"));

        mvc.perform(post("/api/sql/admin/reindex"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(AdminController.RAG_INDEX_UNAVAILABLE));
        verify(reconciler, never()).requestAsync();
    }
}
