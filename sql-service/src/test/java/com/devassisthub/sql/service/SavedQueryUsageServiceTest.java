package com.devassisthub.sql.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devassisthub.sql.exception.ApiException;
import com.devassisthub.sql.repository.SavedQueryRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SavedQueryUsageServiceTest {

    SavedQueryRepository repository = mock(SavedQueryRepository.class);
    SavedQueryService service = new SavedQueryService(repository, new com.devassisthub.sql.validation.ReadOnlyValidator(), org.mockito.Mockito.mock(com.devassisthub.sql.rag.QueryIndexer.class));

    @Test
    void recordsUsageAtomically() {
        when(repository.recordUsage(eq(5L), any(OffsetDateTime.class))).thenReturn(1);
        service.recordUsage(5L);
        verify(repository).recordUsage(eq(5L), any(OffsetDateTime.class));
    }

    @Test
    void noRowUpdatedIs404() {
        when(repository.recordUsage(eq(6L), any(OffsetDateTime.class))).thenReturn(0);
        assertThatThrownBy(() -> service.recordUsage(6L))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getError()).isEqualTo("NOT_FOUND");
                });
    }
}
