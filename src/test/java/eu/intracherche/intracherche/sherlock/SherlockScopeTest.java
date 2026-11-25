package eu.intracherche.intracherche.sherlock;

import eu.intracherche.intracherche.registry.RegistrySchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SherlockScopeTest {

    private SherlockQuestionDao dao;
    private SherlockService service;

    @BeforeEach
    void setUp() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.setAutoCommit(false);
        RegistrySchema.ensureSchema(conn);
        dao = new SherlockQuestionDao(conn);
        service = new SherlockService(dao, new SherlockQueue(10));
    }

    private SherlockQuestion ask(String user, String parentId, List<String> indices) throws Exception {
        return service.ask(user, "", List.of("S-1"), "Question ?", parentId, indices, null, null);
    }

    private SherlockQuestion askAbout(String documentId, String parentId) throws Exception {
        return service.ask("alice", "", List.of("S-1"), "Question ?", parentId, List.of("Contrats"),
                documentId, documentId == null ? null : "rapport.pdf");
    }

    @Test
    void a_question_on_one_document_keeps_it() throws Exception {
        SherlockQuestion q = dao.findById(askAbout("file-42", null).id());

        assertThat(q.documentId()).isEqualTo("file-42");
        assertThat(q.documentName()).isEqualTo("rapport.pdf");
        assertThat(q.indices()).containsExactly("Contrats");
    }

    @Test
    void a_follow_up_keeps_talking_about_the_same_document() throws Exception {
        SherlockQuestion parent = askAbout("file-42", null);

        SherlockQuestion child = dao.findById(askAbout(null, parent.id()).id());

        assertThat(child.documentId()).isEqualTo("file-42");
        assertThat(child.documentName()).isEqualTo("rapport.pdf");
    }

    @Test
    void a_question_without_a_document_has_none() throws Exception {
        SherlockQuestion q = dao.findById(ask("alice", null, null).id());

        assertThat(q.documentId()).isNull();
        assertThat(q.documentName()).isNull();
    }

    @Test
    void scope_is_stored_and_read_back_normalized() throws Exception {
        SherlockQuestion q = ask("alice", null, Arrays.asList(" Contrats ", "RH", "", null, "RH"));

        assertThat(dao.findById(q.id()).indices()).containsExactly("Contrats", "RH");
    }

    @Test
    void no_scope_means_every_index() throws Exception {
        assertThat(dao.findById(ask("alice", null, null).id()).indices()).isEmpty();
        assertThat(dao.findById(ask("alice", null, List.of()).id()).indices()).isEmpty();
    }

    @Test
    void follow_up_without_scope_inherits_the_parent_scope() throws Exception {
        SherlockQuestion parent = ask("alice", null, List.of("Contrats"));

        SherlockQuestion child = ask("alice", parent.id(), null);

        assertThat(dao.findById(child.id()).indices()).containsExactly("Contrats");
    }

    @Test
    void follow_up_with_explicit_empty_scope_searches_everything() throws Exception {
        // "Relancer sur tous les index" keeps the conversation but widens the scope
        SherlockQuestion parent = ask("alice", null, List.of("Contrats"));

        SherlockQuestion child = ask("alice", parent.id(), List.of());

        assertThat(dao.findById(child.id()).indices()).isEmpty();
    }

    @Test
    void follow_up_on_someone_elses_question_is_rejected() throws Exception {
        // The parent's question and answer become LLM history: another user's must never be reachable
        SherlockQuestion bobs = ask("bob", null, null);

        assertThatThrownBy(() -> ask("alice", bobs.id(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ask("alice", "no-such-id", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void worker_keeps_only_requested_cores_that_are_live_in_configured_order() {
        List<String> live = List.of("Index_1", "Index_2", "Index_3");

        assertThat(SherlockRetriever.scopeCoreNames(live, List.of())).isEqualTo(live);
        assertThat(SherlockRetriever.scopeCoreNames(live, null)).isEqualTo(live);
        assertThat(SherlockRetriever.scopeCoreNames(live, List.of("Index_3", "Index_1"))).containsExactly("Index_1", "Index_3");
        // A name Solr doesn't know (removed index, or injection attempt) never survives
        assertThat(SherlockRetriever.scopeCoreNames(live, List.of("Index_9", "*:* OR x"))).isEmpty();
    }
}
