package eu.intracherche.intracherche.process;

import eu.intracherche.intracherche.config.EmbeddingConfig;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.config.NerConfig;
import eu.intracherche.intracherche.config.OcrConfig;
import eu.intracherche.intracherche.config.RerankerConfig;
import eu.intracherche.intracherche.config.ResourceConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Python services are allocated from "total RAM minus what Java, Solr and the OS
 * need". Java's share is its heap, which the installer sizes from the server's RAM — so
 * the reservation has to be read from the running JVM, not from a constant written for
 * one machine size.
 */
class ResourceReservationTest {

    private static final long SOLR_AND_OS_MB = 512 + 1024;
    /** 9 billion parameters at 0.75 GB each, plus 1 GB of KV cache and runtime. */
    private static final long QWEN_9B_MB = Math.round(9 * 0.75 * 1024) + 1024;

    @Test
    void the_reservation_covers_this_jvm_heap_even_when_the_configured_floor_is_lower() {
        ResourceValidator validator = validatorWithReservedGb(1);

        validator.validateAndAllocate();
        ResourceValidator.AllocationResult alloc = validator.getLastAllocation();

        assertEquals(heapMb(), alloc.heapRamMb());
        assertEquals(heapMb() + SOLR_AND_OS_MB, alloc.reservedRamMb(),
                "1 GB configured is below the heap alone, so the derived reservation wins");
        assertEquals(alloc.totalRamMb() - alloc.reservedRamMb(), alloc.budgetRamMb(),
                "the services' budget is what the reservation leaves");
    }

    /** An admin who reserves more than Java, Solr and the OS need keeps their number. */
    @Test
    void a_larger_configured_reservation_is_kept() {
        int configuredGb = (int) (heapMb() / 1024) + 4;
        ResourceValidator validator = validatorWithReservedGb(configuredGb);

        validator.validateAndAllocate();

        assertEquals(configuredGb * 1024L, validator.getLastAllocation().reservedRamMb());
    }

    /** Whatever the machine, the budget never includes memory the JVM may already hold. */
    @Test
    void the_budget_never_overlaps_the_heap() {
        ResourceValidator validator = validatorWithReservedGb(1);

        validator.validateAndAllocate();
        ResourceValidator.AllocationResult alloc = validator.getLastAllocation();

        assertTrue(alloc.budgetRamMb() + alloc.heapRamMb() <= alloc.totalRamMb(),
                "budget " + alloc.budgetRamMb() + " MB + heap " + alloc.heapRamMb()
                        + " MB must fit in " + alloc.totalRamMb() + " MB");
    }

    /**
     * Ollama is not one of the allocated services: it holds the model resident on its own,
     * and whatever is not reserved for it is handed to the Python workers instead.
     */
    @Test
    void sherlocks_model_is_reserved_when_it_runs_on_this_server() {
        ResourceValidator validator = validatorWith(1, llmConfig(true, "http://localhost:11434", "qwen3.5:9b"));

        validator.validateAndAllocate();
        ResourceValidator.AllocationResult alloc = validator.getLastAllocation();

        assertEquals(QWEN_9B_MB, alloc.llmRamMb());
        assertEquals(heapMb() + SOLR_AND_OS_MB + QWEN_9B_MB, alloc.reservedRamMb());
    }

    /** A GPU host's memory is not this server's to set aside. */
    @Test
    void a_model_on_another_host_reserves_nothing() {
        ResourceValidator validator = validatorWith(1, llmConfig(true, "http://192.0.2.34:11434", "qwen3.5:9b"));

        validator.validateAndAllocate();

        assertEquals(0, validator.getLastAllocation().llmRamMb());
    }

    @Test
    void sherlock_switched_off_reserves_nothing() {
        ResourceValidator validator = validatorWith(1, llmConfig(false, "http://localhost:11434", "qwen3.5:9b"));

        validator.validateAndAllocate();

        assertEquals(0, validator.getLastAllocation().llmRamMb());
    }

    /**
     * Everything but a confident "another host" counts as local: a server that names
     * itself is the case that used to go unreserved, and that is the expensive mistake.
     */
    @Test
    void anything_not_provably_remote_counts_as_local() {
        assertTrue(ResourceValidator.isLocalOllama("http://localhost:11434"));
        assertTrue(ResourceValidator.isLocalOllama("http://127.0.0.1:11434"));
        assertTrue(ResourceValidator.isLocalOllama("http://[::1]:11434"));
        assertTrue(ResourceValidator.isLocalOllama("http://" + localHostName() + ":11434"),
                "a server naming itself is still itself");
        assertTrue(ResourceValidator.isLocalOllama("not a url at all"), "unreadable: assume it is here");
        assertTrue(ResourceValidator.isLocalOllama("http://no-such-host.invalid:11434"), "unresolvable: assume it is here");
        assertFalse(ResourceValidator.isLocalOllama("http://192.0.2.34:11434"));
    }

    /** A reranker on a GPU node is not started here, so it takes none of this server's RAM. */
    @Test
    void a_reranker_on_a_gpu_node_is_not_allocated_here() {
        RerankerConfig reranker = rerankerConfig(false);
        ResourceValidator validator = validatorWith(1, llmConfig(false, "http://localhost:11434", "qwen3.5:9b"), reranker);

        validator.validateAndAllocate();

        assertTrue(validator.getLastAllocation().services().stream().noneMatch(s -> s.key().equals("reranker")));
        verify(reranker, never()).setWorkers(org.mockito.ArgumentMatchers.anyInt());
    }

    /**
     * llm.enabled: false. The reranker serves Sherlock alone: allocating it anyway took a
     * worker's worth of RAM away from OCR, NER and embedding on a server that never asks it
     * anything.
     */
    @Test
    void sherlock_switched_off_allocates_no_reranker() {
        LlmConfig llm = llmConfig(false, "http://localhost:11434", "qwen3.5:9b");
        RerankerConfig reranker = new RerankerConfig();
        reranker.useLlm(llm);
        ResourceValidator validator = validatorWith(1, llm, reranker);

        validator.validateAndAllocate();

        assertTrue(validator.getLastAllocation().services().stream().noneMatch(s -> s.key().equals("reranker")));
    }

    @Test
    void sherlock_switched_on_allocates_the_reranker() {
        LlmConfig llm = llmConfig(true, "http://localhost:11434", "qwen3.5:9b");
        RerankerConfig reranker = new RerankerConfig();
        reranker.useLlm(llm);
        ResourceValidator validator = validatorWith(1, llm, reranker);

        validator.validateAndAllocate();

        assertTrue(validator.getLastAllocation().services().stream().anyMatch(s -> s.key().equals("reranker")));
    }

    /** embedding.workers / reranker.workers: an explicit count is kept, not re-derived. */
    @Test
    void an_explicit_worker_count_is_kept() {
        LlmConfig llm = llmConfig(true, "http://localhost:11434", "qwen3.5:9b");
        RerankerConfig reranker = new RerankerConfig();
        reranker.useLlm(llm);
        reranker.setWorkers(1);
        ResourceValidator validator = validatorWith(1, llm, reranker);

        validator.validateAndAllocate();

        ResourceValidator.ServiceAllocation allocated = validator.getLastAllocation().services().stream()
                .filter(s -> s.key().equals("reranker")).findFirst().orElseThrow();
        assertEquals(1, allocated.workers());
        assertFalse(allocated.auto());
        assertEquals(1, reranker.getWorkers());
    }

    /** The size comes from the tag, so changing the model changes the reservation. */
    @Test
    void the_reservation_follows_the_models_parameter_count() {
        assertEquals(Math.round(4 * 0.75 * 1024) + 1024, ResourceValidator.modelRamMb("qwen3.5:4b"));
        assertEquals(Math.round(9 * 0.75 * 1024) + 1024, ResourceValidator.modelRamMb("qwen3.5:9b"));
        assertEquals(Math.round(70 * 0.75 * 1024) + 1024, ResourceValidator.modelRamMb("llama3.1:70b-instruct-q4_K_M"));
        assertEquals(7 * 1024, ResourceValidator.modelRamMb("mistral:latest"), "no size in the tag: assume ours");
        assertEquals(7 * 1024, ResourceValidator.modelRamMb(null));
    }

    /**
     * The reservation alone exceeds the machine (Sherlock's model on an 8 GB server): workers
     * are already at one each, so the message must point at what is really too big, not
     * suggest worker counts that change nothing.
     */
    @Test
    void a_reservation_that_does_not_fit_says_so_and_does_not_suggest_workers() {
        ResourceValidator validator = validatorWith(100_000, llmConfig(true, "http://localhost:11434", "qwen3.5:9b"));

        ResourceValidator.InsufficientRamException e = org.junit.jupiter.api.Assertions.assertThrows(
                ResourceValidator.InsufficientRamException.class, validator::validateAndAllocate);

        String message = e.getMessage();
        assertTrue(message.contains("reducing workers cannot help"), message);
        assertTrue(message.contains("llm.enabled: false"), message);
        assertTrue(message.contains("Left for these services: none"), message);
        assertFalse(message.contains("reduce workers in application.yaml"), message);
        assertFalse(message.contains("workers=0"), message);
    }

    /** Workers fixed too high on a machine that could hold fewer: the old advice still applies. */
    @Test
    void too_many_fixed_workers_suggest_fewer_or_auto() {
        ResourceConfig resourceConfig = new ResourceConfig();
        resourceConfig.setReservedRamGb(1);
        OcrConfig ocr = mock(OcrConfig.class);
        when(ocr.getWorkers()).thenReturn(100_000);
        NerConfig ner = mock(NerConfig.class);
        when(ner.getWorkers()).thenReturn(0);
        when(ner.getNerModel()).thenReturn("fr_core_news_md");
        EmbeddingConfig embedding = mock(EmbeddingConfig.class);
        when(embedding.getWorkers()).thenReturn(0);
        when(embedding.getEmbeddingModel()).thenReturn("intfloat/multilingual-e5-small");
        ResourceValidator validator = new ResourceValidator(resourceConfig, ocr, ner, embedding, rerankerConfig(true),
                llmConfig(false, "http://localhost:11434", "qwen3.5:9b"));

        ResourceValidator.InsufficientRamException e = org.junit.jupiter.api.Assertions.assertThrows(
                ResourceValidator.InsufficientRamException.class, validator::validateAndAllocate);

        String message = e.getMessage();
        assertTrue(message.contains("Option 1 — reduce workers in application.yaml"), message);
        assertTrue(message.contains("ocr.workers="), message);
        assertTrue(message.contains("set to 0 (auto) the ones you fixed"), message);
        assertFalse(message.contains("embedding.workers=0"), "only the services whose count was fixed: " + message);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static String localHostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private static long heapMb() {
        return Runtime.getRuntime().maxMemory() / (1024L * 1024L);
    }

    /** Every service on auto, so the allocation always resolves to something that fits. */
    private static ResourceValidator validatorWithReservedGb(int reservedGb) {
        return validatorWith(reservedGb, llmConfig(false, "http://localhost:11434", "qwen3.5:9b"));
    }

    private static LlmConfig llmConfig(boolean enabled, String url, String model) {
        LlmConfig llm = new LlmConfig();
        llm.setEnabled(enabled);
        llm.setOllamaUrl(url);
        llm.setModel(model);
        return llm;
    }

    private static RerankerConfig rerankerConfig(boolean runsHere) {
        RerankerConfig reranker = mock(RerankerConfig.class);
        when(reranker.getWorkers()).thenReturn(0);
        when(reranker.getRerankerModel()).thenReturn("BAAI/bge-reranker-v2-m3");
        when(reranker.startsHere()).thenReturn(runsHere);
        return reranker;
    }

    private static ResourceValidator validatorWith(int reservedGb, LlmConfig llm) {
        return validatorWith(reservedGb, llm, rerankerConfig(true));
    }

    private static ResourceValidator validatorWith(int reservedGb, LlmConfig llm, RerankerConfig reranker) {
        ResourceConfig resourceConfig = new ResourceConfig();
        resourceConfig.setReservedRamGb(reservedGb);

        OcrConfig ocr = mock(OcrConfig.class);
        when(ocr.getWorkers()).thenReturn(0);
        NerConfig ner = mock(NerConfig.class);
        when(ner.getWorkers()).thenReturn(0);
        when(ner.getNerModel()).thenReturn("fr_core_news_md");
        EmbeddingConfig embedding = mock(EmbeddingConfig.class);
        when(embedding.getWorkers()).thenReturn(0);
        when(embedding.getEmbeddingModel()).thenReturn("intfloat/multilingual-e5-small");
        return new ResourceValidator(resourceConfig, ocr, ner, embedding, reranker, llm);
    }
}
