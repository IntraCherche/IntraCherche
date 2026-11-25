package eu.intracherche.intracherche.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns an {@link IndexesConfigException} into the "APPLICATION FAILED TO START" block, without the stack trace. */
public class IndexesConfigFailureAnalyzer extends AbstractFailureAnalyzer<IndexesConfigException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, IndexesConfigException cause) {
        return new FailureAnalysis(cause.getMessage(),
                "Correct indexes.yaml (the file named by indexing-config.indexesConfigPath), then start IntraCherche again.",
                cause);
    }
}
