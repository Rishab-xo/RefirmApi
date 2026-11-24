package com.app.refirm.inspector;

import com.app.refirm.inspector.dto.InspectorDTOs.DrawerPage;
import com.app.refirm.inspector.repository.PrecedentIndexRepository;
import com.app.refirm.inspector.service.PostgresFallbackPrecedentContentStore;
import com.app.refirm.inspector.service.R2PrecedentContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class R2PrecedentContentStoreTest {

    private ObjectMapper objectMapper;
    private PrecedentIndexRepository precedentIndexRepository;
    private S3Client s3Client;
    private R2PrecedentContentStore r2ContentStore;
    private PostgresFallbackPrecedentContentStore postgresFallbackStore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        precedentIndexRepository = mock(PrecedentIndexRepository.class);
        s3Client = mock(S3Client.class);

        r2ContentStore = new R2PrecedentContentStore(
                "acc123",
                "key123",
                "sec123",
                "refirm-precedents",
                objectMapper,
                precedentIndexRepository
        );
        r2ContentStore.setS3Client(s3Client);

        postgresFallbackStore = new PostgresFallbackPrecedentContentStore(precedentIndexRepository);
    }

    private byte[] gzipCompress(String json) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("R2PrecedentContentStore correctly decompresses .json.gz and deserializes DrawerPages")
    void shouldLoadAndDecompressGzipPagesFromR2() throws Exception {
        List<DrawerPage> expectedPages = List.of(
                new DrawerPage(1, "# Landmark Title\n[PARA 1] First page holding.", List.of("First page holding.")),
                new DrawerPage(2, "[PARA 2] Final conclusion allowed.", List.of())
        );

        String json = objectMapper.writeValueAsString(expectedPages);
        byte[] gzippedBytes = gzipCompress(json);

        ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(gzippedBytes))
        );

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

        Optional<List<DrawerPage>> result = r2ContentStore.loadPages("SC_1985_001", "judgments/SC/SC_1985_001.json.gz");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertEquals(1, result.get().get(0).pageNumber());
        assertEquals("First page holding.", result.get().get(0).highlightedRatioPassages().get(0));
        assertEquals(2, result.get().get(1).pageNumber());

        // Verifies Table 2 was NOT queried since R2 had the object
        verify(precedentIndexRepository, never()).findDrawerPagesByAuthorityId(any());
    }

    @Test
    @DisplayName("R2PrecedentContentStore gracefully falls back to Table 2 when R2 returns NoSuchKeyException")
    void shouldFallbackToTable2OnNoSuchKeyException() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("Key not found").build());

        List<DrawerPage> fallbackPages = List.of(
                new DrawerPage(1, "Table 2 fallback markdown", List.of("Ratio in table 2"))
        );
        when(precedentIndexRepository.findDrawerPagesByAuthorityId("SC_1985_MISSING"))
                .thenReturn(fallbackPages);

        Optional<List<DrawerPage>> result = r2ContentStore.loadPages("SC_1985_MISSING", "judgments/SC/SC_1985_MISSING.json.gz");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        assertEquals("Table 2 fallback markdown", result.get().get(0).pageMarkdown());
        verify(precedentIndexRepository, times(1)).findDrawerPagesByAuthorityId("SC_1985_MISSING");
    }

    @Test
    @DisplayName("R2PrecedentContentStore gracefully falls back to Table 2 when r2ObjectKey is null or blank")
    void shouldFallbackToTable2OnNullOrBlankKey() {
        List<DrawerPage> fallbackPages = List.of(
                new DrawerPage(1, "Legacy table 2 content", List.of())
        );
        when(precedentIndexRepository.findDrawerPagesByAuthorityId("SC_LEGACY_001"))
                .thenReturn(fallbackPages);

        Optional<List<DrawerPage>> resultNull = r2ContentStore.loadPages("SC_LEGACY_001", null);
        Optional<List<DrawerPage>> resultBlank = r2ContentStore.loadPages("SC_LEGACY_001", "   ");

        assertTrue(resultNull.isPresent());
        assertTrue(resultBlank.isPresent());
        verify(s3Client, never()).getObject(any(GetObjectRequest.class));
        verify(precedentIndexRepository, times(2)).findDrawerPagesByAuthorityId("SC_LEGACY_001");
    }

    @Test
    @DisplayName("PostgresFallbackPrecedentContentStore loads directly from Table 2")
    void shouldLoadDirectlyFromPostgresFallbackStore() {
        List<DrawerPage> dbPages = List.of(
                new DrawerPage(1, "Direct postgres content", List.of())
        );
        when(precedentIndexRepository.findDrawerPagesByAuthorityId("auth-123"))
                .thenReturn(dbPages);

        Optional<List<DrawerPage>> result = postgresFallbackStore.loadPages("auth-123", "judgments/SC/auth-123.json.gz");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().size());
        assertEquals("Direct postgres content", result.get().get(0).pageMarkdown());
        verify(precedentIndexRepository, times(1)).findDrawerPagesByAuthorityId("auth-123");
    }
}
