package eu.intracherche.intracherche.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Regression test for a Solr local-params ACL bypass: {!join from=X to=X fromIndex=OtherCore}
// lets a user learn whether a core they have zero accessTokens overlap with contains a given
// word — the join's "from" subquery runs before this request's own fq is applied. Confirmed
// exploitable live (36 hits for a real word vs 0 for a nonexistent one, queried through a core
// the test SID had no legitimate access to). SolrQuerySyntaxGuard is the fix; this test is what
// keeps it from regressing, not what discovered the bug.
class SolrQuerySyntaxGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "{!join from=lang to=lang fromIndex=Index_Demo_OCR}content:attentats",
            "budget {!join from=id to=id fromIndex=Other}x:y municipal",  // not just a leading position
            "*:* AND _query_:\"{!join from=lang to=lang fromIndex=Other}content:x\"",  // nested form
            "_val_:\"1\"",
            "_QUERY_:\"{!dismax qf=content}x\"",  // case-insensitivity
    })
    void rejects_local_params_and_magic_fields(String malicious) {
        assertThatThrownBy(() -> SolrQuerySyntaxGuard.check(malicious))
                .isInstanceOf(InvalidSearchQueryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "budget municipal",
            "\"remise en état\"",           // quoted phrase search
            "toulouse AND mairie",           // boolean
            "chat* wildcard",                 // wildcard
            "1234 AB 56",                     // plate-number-like query seen elsewhere in this app
            "prix: 12,50 €",                  // a literal colon that isn't _query_/_val_
            "",
    })
    void leaves_ordinary_search_syntax_alone(String legitimate) {
        assertThatCode(() -> SolrQuerySyntaxGuard.check(legitimate)).doesNotThrowAnyException();
    }

    @Test
    void null_is_allowed_through_ie_treated_like_an_empty_query() {
        assertThatCode(() -> SolrQuerySyntaxGuard.check(null)).doesNotThrowAnyException();
    }
}
